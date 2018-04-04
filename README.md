> __OBSOLETE__: ClojureScript now uses the `clj -m cljs.main` command for its [Quick Start], which is a great first-run experience supported by the core team that didn't exist when I started this tool.  Also, [shadow-cljs] does a great job of being both easy to use and supports a lot more production-level features not supported by core. 

[Quick Start]:https://clojurescript.org/guides/quick-start
[shadow-cljs]:https://github.com/thheller/shadow-cljs

# ClojureScript starter tool

A tool to answer the following questions:

- What would a great first run of ClojureScript look like?
- How do we create that in the clearest and cheapest way possible?
- Should it do everything?

## Install

This installs the `cljs` command:

```
$ npm install -g cljs
```

_\* cljs is the abbreviation for ClojureScript_

## Basics

Using __[Lumo]__, fast experimenting is the default experience.
Try the most basic things as fast as possible.

- REPL

  ```
  $ cljs

  cljs.user=> (+ 1 2 3)
  6
  ```

- Run script

  ```clojure
  ;; my_file.cljs
  (println (+ 1 2 3))
  ```

  ```sh
  $ cljs my_file.cljs
  6
  ```

## Use dependencies

You can pull in external libraries by specifying them in a plain config file, `cljs.edn`.
Dependencies are automatically downloaded when running any `cljs` command or explicitly
with `cljs install`.

- Dependencies

  ```edn
  ;; cljs.edn
  {:dependencies
   [[markdown-clj "0.9.94"]]}
  ```

  ```clojure
  $ cljs

  cljs.user=> (require '[markdown.core :refer [md->html]])
  cljs.user=> (md->html "## Hello World")
  "<h2>Hello World</h2>"
  ```

- In Script

  ```clojure
  ;; my_file.cljs
  (require '[markdown.core :refer [md->html]])

  (println (md->html "## Hello World"))
  ```

  ```html
  $ cljs my_file.cljs
  <h2>Hello World</h2>
  ```

## Use Namespaces

If you create a build name that points to a source directory, you can
start organizing files into canonical namespaces.

- Specify src directory

  ```edn
  ;; cljs.edn
  {:dependencies [...]
   :builds {:main {:src "src"}}} ;; <-- Source at "src" directory,
                                 ;;     or use ["src" ...] for multiple.
                                 ;;     (:main can be any name for the build)
  ```

- Use namespaces

  ```clojure
  ;; src/example/core.cljs
  (ns example.core)

  (defn hello []
    (println "Hello World"))
  ```

  ```clojure
  $ cljs

  cljs.user=> (require 'example.core)
  cljs.user=> (in-ns 'example.core)
  example.core=> (hello)
  Hello World
  ```

## Compile to JavaScript

To run your ClojureScript code without the `cljs` command, you can
compile it to a JavaScript output file for use in a browser or elsewhere.
Specify extra config for compiler:

- Compiler config

  ```edn
  ;; cljs.edn
  {:cljs-version "1.9.456"  ;; <-- compiler version
   :dependencies [...]
   :builds {:main {:src "src"
                   :compiler {:output-to "main.js"}}}} ;; <-- compiler options
  ```

- Build or watch (using the production JVM compiler)

  <pre>
  $ cljs build      # using this <a href="https://github.com/cljs/tool/blob/master/target/script/build.clj">build</a> script
  $ cljs watch      # using this <a href="https://github.com/cljs/tool/blob/master/target/script/watch.clj">watch</a> script
  </pre>

- Pretty errors (borrowed from __[Figwheel]__)

  ```clojure
  ;; modify src/example/core.cljs
  (ns example.core)

  (defn hello)  ;; <-- make an incomplete function
  ```

  ```
  Compiling src/foo/core.cljs
  ----  Could not Analyze  src/foo/core.cljs   line:3  column:1  ----

    Parameter declaration missing

    1  (ns example.core)
    2
    3  (defn hello)
       ^--- Parameter declaration missing

  ----  Analysis Error : Please see src/foo/core.cljs  ----
  ```

## Develop for the web

Using __[Figwheel]__, you can compile your project with a much more fluid and interactive
developer experience. You get a browser-connected REPL, hot-loading of files
as they change, and an in-page status display.

- Figwheel config

  ```edn
  ;; cljs.edn
  {:cljs-version "1.9.456"
   :dependencies [...]
   :figwheel {...} ;; <-- optional server-level config
   :builds {:main {:src "src"
                   :figwheel ... ;; <-- optional build-level config
                   :compiler {...}}}}
  ```

- Run Figwheel

  ```sh
  $ cljs figwheel main
  ```

> Try the example provided in this repo:
>
> ```sh
> $ cljs figwheel example
>
> cljs.user=>
> ```
>
> Open `public/index.html`, then modify `src-example/example/core.cljs` to see
> status messages on your page:
>
> <img src="img/figwheel-success.png" width=32%> <img src="img/figwheel-error.png" width=32%> <img src="img/figwheel-warning.png" width=32%>

## Customize build scripts

For direct access to the ClojureScript compiler API,
run with a Clojure file (`.clj` not `.cljs`).  
Your Clojure program will be given access to the compiler API and
your config in a `*cljs-config*` var.

- Custom Build

  ```clojure
  ;; build.clj
  (require '[cljs.build.api :as b]) ;; <-- official cljs compiler api

  (let [{:keys [src compiler]} (-> *cljs-config* :builds :main)]
    (b/build src compiler))
  ```

- Run

  ```
  $ cljs build.clj
  ```

[Lumo]:https://github.com/anmonteiro/lumo
[Figwheel Sidecar]:https://github.com/bhauman/lein-figwheel/tree/master/sidecar
[Figwheel]:https://github.com/bhauman/lein-figwheel
[Quick Start]:https://clojurescript.org/guides/quick-start
