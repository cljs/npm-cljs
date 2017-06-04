(ns tool.core
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:require
    [cljs.core.async :refer [<!]]
    [clojure.string :as string]
    [cljs.pprint :refer [pprint]]
    [tool.io :as io]))

;; Filenames of dependent resources
(def file-config-edn "cljs.edn")
(def file-deps-cache ".deps-cache.edn")
(def file-dep-retriever (str js/__dirname "/cdr.jar"))
(def file-build-script (str js/__dirname "/script/build.clj"))
(def file-watch-script (str js/__dirname "/script/watch.clj"))
(def file-repl-script (str js/__dirname "/script/repl.clj"))
(def file-figwheel-script (str js/__dirname "/script/figwheel.clj"))

;;---------------------------------------------------------------------------
;; User Config
;;---------------------------------------------------------------------------

(def config nil)

(defn transform-builds
  "Add :id to each build"
  [builds]
  (reduce-kv
    (fn [m k v]
      (assoc m k (assoc v :id k)))
    {} builds))

(defn transform-config
  [cfg]
  (cond-> cfg
    (:builds cfg) (update :builds transform-builds)))

(defn load-config! []
  (when (io/path-exists? file-config-edn)
    (set! config (transform-config (io/slurp-edn file-config-edn)))))

;;---------------------------------------------------------------------------
;; Java
;;---------------------------------------------------------------------------

(def jre (js/require "node-jre"))
(def java-path (jre.driver))

;;---------------------------------------------------------------------------
;; JARs used for compiling w/ JVM
;; (they are AOT'd to reduce load time)
;;---------------------------------------------------------------------------

;; ClojureScript downloaded based on version in config
(defn file-cljs-jar [version] (str js/__dirname "/cljs-" version ".jar"))
(defn url-cljs-jar [version] (str "https://github.com/clojure/clojurescript/releases/download/r" version "/cljs.jar"))

;; We build/host our own AOT'd uberjar for figwheel sidecar
;; (FIXME: find a better way to host this, though changes may be infrequent
;;  since we are only using it for pretty errors/warnings, or move it to own
;;  error/warning prettier repo)
(defn file-fig-jar [version] (str js/__dirname "/figwheel-sidecar-" version ".jar"))
(def fig-version "0.5.8")

(defn get-jvm-jars []
  [(file-cljs-jar (:cljs-version config))
   (file-fig-jar fig-version)])

;;---------------------------------------------------------------------------
;; Misc
;;---------------------------------------------------------------------------

(def windows? (= "win32" js/process.platform))

(def child-process (js/require "child_process"))
(def spawn-sync (.-spawnSync child-process))

(defn exit-error [& args]
  (apply js/console.error args)
  (js/process.exit 1))

;;---------------------------------------------------------------------------
;; Emit errors or perform corrective actions if requirements not met
;;---------------------------------------------------------------------------

(defn ensure-cljs-version!
  "Download the ClojureScript compiler uberjar for the version given in config."
  []
  (go
    (let [version (:cljs-version config)
          jar-path (file-cljs-jar version)
          jar-url (url-cljs-jar version)]
      (or (io/path-exists? jar-path)
          (<! (io/download-progress jar-url jar-path (str "ClojureScript " version)))))))

(defn ensure-config!
  "Ask user to create a cljs.edn config file."
  []
  (or config
      (exit-error "No config found. Please create one in" file-config-edn)))

(defn ensure-build-map! []
  (when-not (seq (:builds config))
    (exit-error "No builds were found in the :builds map!")))

(defn ensure-build-imply! [id]
  (let [[build & others] (vals (:builds config))]
    (when (nil? id)
      (if others
        (exit-error (str "Please specify a build " (keys (:builds config)) " since there are more than one."))
        build))))

(defn ensure-build!
  "Emit error if the given build does not exist in config :builds."
  [id]
  (or
    (ensure-build-map!)
    (ensure-build-imply! id)
    (get-in config [:builds (keyword id)])
    (exit-error (str "Unrecognized build: '" id "' is not found in :builds map"))))

(declare install-deps)

(def dep-keys
  "Dependencies are found in these config keys"
  [:dependencies :dev-dependencies])

(defn ensure-deps!
  "If dependencies have changed since last run, resolve and download them."
  []
  (let [cache (io/slurp-edn file-deps-cache)
        stale? (not= (select-keys config dep-keys)
                     (select-keys cache dep-keys))]
    (if stale?
      (install-deps)
      cache)))

;;---------------------------------------------------------------------------
;; Dependency Resolution
;;---------------------------------------------------------------------------

(defn build-classpath
  "Create a standard string of dependency paths (i.e. the classpath)"
  [& {:keys [src jvm?]}]
  (let [{:keys [jars]} (ensure-deps!)
        source-paths (when src (if (sequential? src) src [src]))
        jars (cond->> jars jvm? (concat (get-jvm-jars)))
        all (concat jars source-paths)
        sep (if windows? ";" ":")]
    (string/join sep all)))

(defn install-deps
  "Use the JVM tool to download/resolve all dependencies. We associate the
  resulting list of JARs to our current dependency config in a cache to avoid
  this expensive task when possible."
  []
  (let [deps (apply concat (map config dep-keys))
        result (spawn-sync java-path
                 #js["-jar" file-dep-retriever (pr-str deps)]
                 #js{:stdio #js["pipe" "pipe" 2]})
        stdout-lines (when-let [output (.-stdout result)]
                       (string/split (.toString output) "\n"))
        success? (and (zero? (.-status result))
                      (not (.-error result)))]
    (when success?
      (let [cache (-> config
                      (select-keys dep-keys)
                      (assoc :jars stdout-lines))]
        (io/spit file-deps-cache (with-out-str (pprint cache)))
        cache))))

(defn all-sources
  "Whenever it is ambiguous which source directory we should use,
  this function allows us to just use them all."
  []
  (->> (:builds config)
       (vals)
       (map :src)
       (filter identity)
       (flatten)))

;;---------------------------------------------------------------------------
;; Running ClojureScript Compiler API scripts
;; (check 'target/script/' directory for build/watch/repl scripts)
;;---------------------------------------------------------------------------

(defn run-api-script
  "Run some Clojure file, presumably to build/watch/repl using ClojureScript's API.
   The file receives the following data:
     - *cljs-config*       (full config)
     - *build-config*      (specific build config, if specified)
     - *command-line-args* (as usual)"
  [& {:keys [build-id script-path args]}]
  (let [build (ensure-build! build-id)
        src (or (:src build) (all-sources))
        cp (build-classpath :src src :jvm? true)
        onload (str
                 "(do "
                 "  (def ^:dynamic *cljs-config* (quote " config "))"
                 "  (def ^:dynamic *build-config* (quote " build "))"
                 "  nil)")
        args (concat ["-cp" cp "clojure.main" "-e" onload script-path] args)]
    (spawn-sync java-path (clj->js args) #js{:stdio "inherit"})))

;;---------------------------------------------------------------------------
;; Lumo is the fastest way to run ClojureScript on Node.
;;---------------------------------------------------------------------------

(def lumo-path (str js/__dirname "/../node_modules/.bin/lumo"))

(defn build-lumo-args
  "We add args when calling lumo in order to integrate config file settings."
  [args]
  (apply array
    (concat
      ;; Add dependencies to classpath, and all source directories
      (when config
        ["-c" (build-classpath :src (all-sources))])
      args)))

(defn run-lumo
  "Lumo is an executable published on npm for running a REPL or a file."
  [args]
  (let [lumo-args (build-lumo-args args)]
    (spawn-sync lumo-path lumo-args #js{:stdio "inherit"})))

;;---------------------------------------------------------------------------
;; Entry
;;---------------------------------------------------------------------------

(defn print-welcome
  "Show something to the user immediately in case the JVM compiler goes silent
  during initial load, which can take some time."
  []
  (println)
  (println (str (io/color :green "(cl") (io/color :blue "js)")
                (io/color :grey " ClojureScript starting...")))
  (println))

(defn -main [task & args]
  (load-config!)
  (cond
    ;; Run Lumo REPL if no args provided
    (nil? task) (do (print-welcome) (run-lumo nil))

    ;; Run a ClojureScript source file if first arg
    (string/ends-with? task ".cljs") (run-lumo (cons task args))

    ;; Install ClojureScript dependencies
    (= task "install") (do (ensure-config!) (install-deps))

    ;; Otherwise, we will use the JVM ClojureScript compiler.
    :else
    (do
      (print-welcome)
      (ensure-config!)
      (go
        (<! (ensure-cljs-version!))
        (cond
          (= task "build") (run-api-script :build-id (first args) :script-path file-build-script)
          (= task "watch") (run-api-script :build-id (first args) :script-path file-watch-script)
          (= task "repl") (run-api-script :build-id (first args) :script-path file-repl-script)
          (= task "figwheel") (run-api-script :build-id (first args) :script-path file-figwheel-script)
          (string/ends-with? task ".clj") (run-api-script :script-path task :args args)
          :else (exit-error "Unrecognized task:" task))))))

(set! *main-cli-fn* -main)
(enable-console-print!)
