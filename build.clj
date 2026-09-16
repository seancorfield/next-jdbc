(ns build
  "next.jdbc's build script. Entirely driven by `bb`."
  (:require [babashka.deps-deploy :as dd]
            [babashka.tasks :refer [shell]]
            [clojure.tools.build.api :as b]))

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

(defn jar "Build the JAR file."
  {:org.babashka/cli {:spec {:snapshot {:coerce :boolean}}}}
  [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding" (:jar-file opts) "...")
    (b/jar opts))
  opts)

(defn deploy "Deploy the JAR to Clojars."
  {:org.babashka/cli {:spec {:snapshot {:coerce :boolean}}}}
  [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)

;; test-related tasks:

(defn run-tests "Run the test suite for various Clojure versions and databases."
  {:org.babashka/cli {:spec {:all-versions {:coerce :boolean}
                             :jdk          {} ; string
                             :local        {:coerce :boolean}
                             :maria        {:coerce :boolean}}}}
  [opts]
  (let [versions (if (:all-versions opts)
                   ["1.10" "1.11" "1.12" "1.13"]
                   ["1.12"])
        env
        (cond (:local opts)
              {}
              (:maria opts)
              {"NEXT_JDBC_TEST_MARIADB" "yes"
               "NEXT_JDBC_TEST_MYSQL"   "yes"}
              :else
              {"NEXT_JDBC_TEST_MSSQL" "yes"
               "NEXT_JDBC_TEST_MYSQL" "yes"
               "NEXT_JDBC_TEST_XTDB"  "yes"
               "MSSQL_SA_PASSWORD"    "Str0ngP4ssw0rd"})]
    (doseq [v versions]
      (println "\nTesting Clojure" v)
      (shell {:extra-env env}
             "clojure"
             (str "-M"
                  ":" v
                  ":test:runner"
                  ;; 11 17 -- no xtdb
                  ;; 21 -- xtdb
                  ;; 25 -- xtdb, native access all unnamed
                  (if-let [jdk (:jdk opts)]
                    (str ":jdk" jdk)
                    ;; sean's local default
                    ":jdk25"))))))

;; low-level build tasks:

(defn docker "Start or stop Docker."
  {:org.babashka/cli {:spec {:down {:coerce :boolean}
                             :up   {:coerce :boolean}}}}
  [opts]
  (cond (:up opts)
        (shell "docker compose up -d")
        (:down opts)
        (shell "docker compose down")
        :else
        (throw (ex-info "docker task requires either --up or --down" opts))))
