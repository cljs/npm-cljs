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
(def targz (js/require "targz"))

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
  (.mkdirsSync fs-extra path)
  path)

(defn rm [path]
  (try (.unlink fs path)
       (catch js/Error e nil)))

(defn child-dirs [parent-dir]
  (->> (.readdirSync fs parent-dir)
       (map #(str parent-dir "/" %))
       (filter #(.isDirectory (.statSync fs %)))))

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

(defn download-progress [url path label & {:keys [opts]}]
  (let [partial-path (str path ".partial")
        file (.createWriteStream fs partial-path)
        req (request url (clj->js opts))
        done-chan (chan)]
    (hook-progress-bar req label)
    (.pipe req file)
    (.on req "error"
      (fn []
        (println "Download failed! Please try again.")
        (.exit js/process -1)))
    (.on req "end"
      (fn []
        ;; Flush everything to the file so we can immediately access it.
        (.end file)))
    (.on file "finish"
      (fn []
        (.moveSync fs-extra partial-path path)
        (put! done-chan 1)))
    done-chan))

(defn extract-targz [src dest]
  (let [done-chan (chan)]
    (.decompress targz #js{:src src :dest dest}
      (fn [err]
        (when err
          (js/console.log "Failed to extract" src "due to error:" err)
          (.exit js/process -1))
        (put! done-chan 1)))
    done-chan))

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
