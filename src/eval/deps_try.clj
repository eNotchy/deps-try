(ns eval.deps-try
  {:clj-kondo/config '{:lint-as {babashka.fs/with-temp-dir clojure.core/let}}}
  (:require
   [babashka.classpath :as cp :refer [get-classpath]]
   [babashka.deps :as deps]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def init-cp (get-classpath))

(require '[eval.deps-try.deps :as try-deps]
         '[babashka.fs :as fs] :reload
         '[babashka.http-client] :reload) ;; reload so we use the dep, not the built-in

(defn parse-cp-file [s]
  (some #(when (str/includes? % "cp_file")
           (str/trim (second (str/split % #"=")))) (str/split-lines s)))

(defn deps->cp [tmp deps]
  (str/trim (with-out-str (deps/clojure {:dir (str tmp)} "-Spath" "-Sdeps" (str {:deps deps})))))

(defn print-usage []
  (println "Usage:
  deps-try [dep-name [dep-version] [dep2-name ...] ...]

Supported dep-name types:
- maven
  e.g. `metosin/malli`, `org.clojure/cache`.
- git
  - infer-notation, e.g. `com.github.user/project`, `ht.sr.~user/project`.
  - url, e.g. `https://github.com/user/project`, `https://anything.org/user/project.git`.
- local
  - path to project containing `deps.edn`, e.g. `.`, `~/projects/my-project`, `./path/to/project`.

Examples:
# A REPL using the latest Clojure version
$ deps-try

# A REPL with specific dependencies (latest version implied)
$ deps-try metosin/malli criterium/criterium

# ...specific version
$ deps-try metosin/malli 0.9.2

# Dependency from GitHub/GitLab/SourceHut (gets you the latest SHA from the default branch)
$ deps-try https://github.com/metosin/malli

# ...a specific branch/tag/SHA
$ deps-try https://github.com/metosin/malli some-branch-tag-or-sha

# ...using the 'infer' notation, e.g.
# com.github.<user>/<project>, com.gitlab.<user>/<project>, ht.sr.~<user>/<project>
$ deps-try com.github.metosin/malli

# A local project
$ deps-try . ~/some/project ../some/other/project

During a REPL-session:
# add additional dependencies
user=> :deps/try dev.weavejester/medley \"~/some/project\"

# see help for all options
user=> :repl/help
"))

(defn print-version []
  (let [dev?    (nil? (io/resource "VERSION"))
        bin     (if dev? "deps-try-dev" "deps-try")
        version (str/trim
                 (if dev?
                   (let [git-dir (fs/file (io/resource ".git"))]
                     (:out (p/sh {} "git" "--git-dir" (str git-dir) "describe" "--tags")))
                   (slurp (io/resource "VERSION"))))]
    (println (str bin " " version))))

(defn- print-usage? [args]
  (contains? #{"-h" "--help" "help"} (first args)))

(defn- print-version? [args]
  (contains? #{"-v" "--version" "version"} (first args)))


(def ^:private clojure-cli-version-re #"^(\d+)\.(\d+)\.(\d+)\.(\d+)")

(defn- clojure-cli-version []
  (peek (str/split (str/trimr (:out (p/sh "clojure" "--version"))) #"\s+")))

(defn- parse-clojure-cli-version [s]
  (map parse-long (rest (re-find clojure-cli-version-re s))))

(defn- at-least-version? [version-or-above version]
  (let [[major1 minor1 patch1 build1] (parse-clojure-cli-version version-or-above)
        [major2 minor2 patch2 build2] (parse-clojure-cli-version version)]
    (or (< major1 major2)
        (and (= major1 major2) (< minor1 minor2))
        (and (= major1 major2) (= minor1 minor2) (< patch1 patch2))
        (and (= major1 major2) (= minor1 minor2) (= patch1 patch2) (or (= build1 build2)
                                                                       (< build1 build2))))))

(defn- print-message [msg {:keys [msg-type]}]
  (let [no-color?        (or (System/getenv "NO_COLOR") (= "dumb" (System/getenv "TERM")))
        color-by-type    {:warning {"WARNING" ["\033[1m" "\033[33m" :msg "\033[0m"]}
                          :error   {"ERROR" ["\033[1m" "\033[31m" :msg "\033[0m"]}}
        [no-color color] (first (color-by-type msg-type))
        maybe-color-wrap #(if no-color?
                            (str no-color %)
                            (apply str (replace {:msg %} color)))]
    (println (maybe-color-wrap msg))))

(defn- warn [m]
  (print-message m {:msg-type :warning}))

(defn- error [m]
  (print-message m {:msg-type :error}))

(defn- warn-unless-minimum-clojure-cli-version [minimum version]
  (when-not (at-least-version? minimum version)
    (warn (str "Adding (additional) libraries to this REPL-session via ':deps/try some/lib' won't work as it requires Clojure CLI version >= " minimum " (current: " version ")."))))

(defn -main [& args]
  (cond
    (print-version? args) (print-version)
    (print-usage? args)   (print-usage)

    :else (let [{requested-deps :deps parse-error :error} (try-deps/parse-dep-args args)]
            (if parse-error
              (do (error parse-error) (System/exit 1))
              (fs/with-temp-dir [tmp {}]
                (let [verbose-output (with-out-str (deps/clojure {:dir (str tmp)} "-Sverbose" "-Spath"))
                      cp-file        (parse-cp-file verbose-output)
                      basis-file     (str/replace cp-file #".cp$" ".basis")
                      default-cp     (deps->cp tmp '{org.clojure/clojure {:mvn/version "1.12.0-alpha3"}})
                      requested-cp   (deps->cp tmp requested-deps)
                      classpath      (str default-cp fs/path-separator init-cp fs/path-separator requested-cp)]
                  (warn-unless-minimum-clojure-cli-version "1.11.1.1273" (clojure-cli-version))
                  (p/exec "java" "-classpath" classpath
                          (str "-Dclojure.basis=" basis-file)
                          "clojure.main" "-m" "eval.deps-try.try")))))))
