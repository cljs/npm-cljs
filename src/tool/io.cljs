(ns tool.io
  (:require
    [cljs.core.async :refer [chan put!]]
    [cljs.reader :refer [read-string]]
    [clojure.string :refer [starts-with?]]))

;; Node Implementation

(def fs (js/require "fs"))
(def fs-extra (js/require "fs-extra"))
(def request-sync (js/require "sync-request"))
(def request (js/require "request"))
(def colors (js/require "colors/safe"))
(def ProgressBar (js/require "progress"))

(def request-opts
  #js{:headers
       #js{:user-agent "cljs-tool"}})

(defn url? [path]
  (or (starts-with? path "http://")
      (starts-with? path "https://")))

(defn path-exists? [path]
  (.existsSync fs path))

(defn slurp [path]
  (if (url? path)
    (.toString (.getBody (request-sync "GET" path request-opts)))
    (when (path-exists? path)
      (.toString (.readFileSync fs path)))))

(defn spit [path text]
  (.writeFileSync fs path text))

(defn mkdirs [path]
  (.mkdirsSync fs-extra path))

(defn rm [path]
  (try (.unlink fs path)
       (catch js/Error e nil)))

(defn download [url path]
  (let [response (request-sync "GET" url request-opts)
        buffer (.getBody response)]
    (.writeFileSync fs path buffer)))

(defn hook-progress-bar [req label]
  (.on req "response"
    (fn [response]
      (let [total (js/parseInt (aget response "headers" "content-length") 10)
            bar (ProgressBar. (str "Downloading " label "  [:bar] :percent :etas")
                  #js{:complete "=" :incomplete " " :width 40 :total total})]
        (.on response "data" #(.tick bar (.-length %)))
        (.on response "end" #(println))))))

(defn download-progress [url path label]
  (let [partial-path (str path ".partial")
        file (.createWriteStream fs partial-path)
        req (request url)
        c (chan)]
    (hook-progress-bar req label)
    (.pipe req file)
    (.on req "error"
      #(do (.close file)
           (.exit js/process -1)))
    (.on req "end"
      #(do (.moveSync fs-extra partial-path path)
           (put! c 1)))
    c))

(defn color [col text]
  (let [f (aget colors (name col))]
    (f text)))

;; Helpers

(defn slurp-json [path]
  (when-let [text (slurp path)]
    (-> (js/JSON.parse text)
        (js->clj :keywordize-keys true))))

(defn slurp-edn [path]
  (when-let [text (slurp path)]
    (read-string text)))
