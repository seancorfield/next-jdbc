(ns build
  "next.jdbc's build script.

  clojure -T:build jar
  clojure -T:build deploy

  Run tests via:
  bb test

  For more information, run:

  clojure -T:deps:build help/doc"
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.seancorfield/next.jdbc)
(defn- the-version [patch] (format "1.3.%s" patch))
(def version (the-version (b/git-count-revs nil)))
(def snapshot (the-version "9999-SNAPSHOT"))
(def class-dir "target/classes")

(defn- pom-template [version]
  [[:description "The next generation of clojure.java.jdbc: a new low-level Clojure wrapper for JDBC-based access to databases."]
   [:url "https://github.com/seancorfield/next-jdbc"]
   [:licenses
    [:license
     [:name "Eclipse Public License 2.0"]
     [:url "https://www.eclipse.org/legal/epl-2.0"]]]
   [:developers
    [:developer
     [:name "Sean Corfield"]]]
   [:scm
    [:url "https://github.com/seancorfield/next-jdbc"]
    [:connection "scm:git:https://github.com/seancorfield/next-jdbc.git"]
    [:developerConnection "scm:git:ssh:git@github.com:seancorfield/next-jdbc.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (let [version (if (:snapshot opts) snapshot version)]
    (assoc opts
           :lib lib   :version version
           :jar-file  (format "target/%s-%s.jar" lib version)
           :basis     (b/create-basis {})
           :class-dir class-dir
           :target    "target"
           :src-dirs  ["src"]
           :pom-data  (pom-template version))))

(defn jar "Build the JAR file." [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding" (:jar-file opts) "...")
    (b/jar opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
