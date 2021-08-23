(ns build
  "next.jdbc's build script.

  clojure -T:build run-tests

  clojure -T:build ci

  For more information, run:

  clojure -A:deps -T:build help/doc"
  (:require [clojure.tools.build.api :as b]
            [clojure.tools.deps.alpha :as t]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.seancorfield/next.jdbc)
(def version (format "1.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean "Remove the target folder." [_]
  (println "\nCleaning target...")
  (b/delete {:path "target"}))

(defn jar "Build the library JAR file." [_]
  (println "\nWriting pom.xml...")
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :scm {:tag (str "v" version)}
                :basis basis
                :src-dirs ["src"]})
  (println "Copying src...")
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (println (str "Building jar " jar-file "..."))
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn- run-task
  [aliases]
  (println "\nRunning task for:" aliases)
  (let [basis    (b/create-basis {:aliases aliases})
        combined (t/combine-aliases basis aliases)
        cmds     (b/java-command {:basis     basis
                                  :java-opts (:jvm-opts combined)
                                  :main      'clojure.main
                                  :main-args (:main-opts combined)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info (str "Task failed for: " aliases) {})))))


(defn run-tests
  "Run regular tests.

  Optionally specify :aliases:
  [:1.10] -- test against Clojure 1.10.3 (the default)
  [:master] -- test against Clojure 1.11 master snapshot"
  [{:keys [aliases] :as opts}]
  (run-task (into [:test] aliases))
  opts)

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (as-> opts
            (reduce (fn [opts alias]
                      (run-tests (assoc opts :aliases [alias :runner])))
                    opts
                    [:1.10 :master]))
      (clean)
      (jar)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (dd/deploy (merge {:installer :remote :artifact jar-file
                     :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
                    opts)))
