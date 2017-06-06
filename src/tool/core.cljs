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
(def file-dep-retriever (str js/__dirname "/dep-retriever.jar"))
(def file-build-script (str js/__dirname "/script/build.clj"))
(def file-watch-script (str js/__dirname "/script/watch.clj"))
(def file-repl-script (str js/__dirname "/script/repl.clj"))
(def file-figwheel-script (str js/__dirname "/script/figwheel.clj"))

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
;; User Config
;;---------------------------------------------------------------------------

(def config nil)

(def default-cljs-version "1.9.562")
(def default-fig-version "0.5.10")

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
    (:builds cfg) (update :builds transform-builds)
    true          (update :cljs-version #(or % default-cljs-version))
    true          (update :figwheel-version #(or % default-fig-version))))

(defn load-config! []
  (when (io/path-exists? file-config-edn)
    (set! config (transform-config (io/slurp-edn file-config-edn)))))

;;---------------------------------------------------------------------------
;; Java
;;
;; Adapted from `node-jre` package for auto-installing Java.
;; We are not using `node-jre` here because it requires performing the java
;; download at install time rather than conditionally at runtime.
;;---------------------------------------------------------------------------

(def java-path "java")
(def os (js/require "os"))

(defn java-installed? []
  (zero? (.-status (spawn-sync "java" #js["-version"]))))

;; version and url info from:
;; http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html
(def java-version "8u131")
(def java-build "b11")
(def java-hash "d54c1d3a095b4ff2b6607d096fa80163")

(defn java-url [platform arch]
  (str
    "https://download.oracle.com/otn-pub/java/jdk/"
    java-version "-" java-build "/" java-hash "/"
    "jre-" java-version "-" platform "-" arch ".tar.gz")) ; filename

(defn java-url-opts []
  {:rejectUnauthorized false
   :agent false
   :headers
   {:connection "keep-alive"
    :Cookie "gpw_e24=http://www.oracle.com/; oraclelicense=accept-securebackup-cookie"}})

(defn java-meta []
  (let [arch (.arch os)
        platform (.platform os)]
    (cond-> {}
      (= platform "darwin") (assoc :platform "macosx"  :binary "Contents/Home/bin/java")
      (= platform "win32")  (assoc :platform "windows" :binary "bin/javaw.exe")
      (= platform "linux")  (assoc :platform "linux"   :binary "bin/java")
      (= platform "sunos")  (assoc :platform "solaris" :binary "bin/java")
      (= arch "ia32")       (assoc :arch "i586")
      (= arch "x64")        (assoc :arch "x64"))))

(defn ensure-java-installable! [rationale]
  (let [{:keys [arch platform]} (java-meta)]
    (when (or (nil? arch)
              (nil? platform)
              (and (= platform "solaris") (not= arch "x64")))
      (exit-error
        rationale
        "Unfortunately we cannot auto-install Java on your platform."
        "Please manually install Java if possible and try again here afterwards."))))

(defn ensure-java-embed! [rationale]
  (go
    (ensure-java-installable! rationale)
    (let [{:keys [arch platform binary]} (java-meta)
          url (java-url platform arch)

          tar-path (str js/__dirname "/jre-" java-version "-" java-build ".tar.gz")
          extract-path (io/mkdirs (str js/__dirname "/java"))
          jre-path #(first (io/child-dirs extract-path))
          binary-path #(str (jre-path) "/" binary)]

      (when (or (nil? (jre-path))
                (not (io/path-exists? (binary-path))))
        (println)
        (println rationale "Let us install it for you!")
        (<! (io/download-progress url tar-path (str "Java Runtime " java-version "-" java-build)
              :opts (java-url-opts)))
        (<! (io/extract-targz tar-path extract-path)))

      (set! java-path (binary-path)))))

(defn ensure-java! [rationale]
  (go
    (when-not (java-installed?)
      (<! (ensure-java-embed! rationale)))))

;;---------------------------------------------------------------------------
;; JARs used for compiling w/ JVM
;; (they are AOT'd to reduce load time)
;;---------------------------------------------------------------------------

;; ClojureScript downloaded based on version in config
(defn file-cljs-jar [version] (str js/__dirname "/cljs-" version ".jar"))
(defn url-cljs-jar [version] (str "https://github.com/clojure/clojurescript/releases/download/r" version "/cljs.jar"))

(defn file-fig-jar [version] (str js/__dirname "/figwheel-sidecar-" version ".jar"))
(defn url-fig-jar [version] (str "https://github.com/cljs/figwheel-sidecar/releases/download/v" version "/figwheel-sidecar.jar"))

(defn get-jvm-jars []
  [(file-cljs-jar (:cljs-version config))
   (file-fig-jar (:figwheel-version config))])

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

(defn ensure-fig-version!
  "Download the Figwheel Sidecar uberjar for the version given in config."
  []
  (go
    (let [version (:figwheel-version config)
          jar-path (file-fig-jar version)
          jar-url (url-fig-jar version)]
      (or (io/path-exists? jar-path)
          (<! (io/download-progress jar-url jar-path (str "Figwheel Sidecar " version)))))))

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
  (go
    (load-config!)
    (when (:dependencies config)
      (<! (ensure-java! "Dependency resolution currently requires Java.")))

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
        (<! (ensure-cljs-version!))
        (when (#{"build" "watch" "figwheel"} task)
          (<! (ensure-fig-version!)))
        (<! (ensure-java! "Compilation to JavaScript currently requires Java."))
        (cond
          (= task "build") (run-api-script :build-id (first args) :script-path file-build-script)
          (= task "watch") (run-api-script :build-id (first args) :script-path file-watch-script)
          (= task "repl") (run-api-script :build-id (first args) :script-path file-repl-script)
          (= task "figwheel") (run-api-script :build-id (first args) :script-path file-figwheel-script)
          (string/ends-with? task ".clj") (run-api-script :script-path task :args args)
          :else (exit-error "Unrecognized task:" task))))))

(set! *main-cli-fn* -main)
(enable-console-print!)
