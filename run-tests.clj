#!/usr/bin/env bb

(require '[babashka.process :as p])

(defn- run-tests [env v]
  (when v (println "\nTesting Clojure" v))
  (let [{:keys [exit]}
        (p/shell {:extra-env env}
                 "clojure"
                 (str "-M"
                      (when v (str ":" v))
                      ":test:runner"
                      ;; jdk21+ adds xtdb:
                      (when (get env "NEXT_JDBC_TEST_XTDB")
                        ":jdk21")
                      ;; to suppress native access warnings on JDK24:
                      ":jdk24")
                 "--output" "dots")]
    (when-not (zero? exit)
      (System/exit exit))))

(let [maria? (some #(= "maria" %) *command-line-args*)
      all?   (some #(= "all"   %) *command-line-args*)
      env
      (cond-> {"NEXT_JDBC_TEST_MSSQL" "yes"
               "NEXT_JDBC_TEST_MYSQL" "yes"
               "NEXT_JDBC_TEST_XTDB"  "yes"
               "MSSQL_SA_PASSWORD"    "Str0ngP4ssw0rd"}
        maria?
        (assoc "NEXT_JDBC_TEST_MARIADB" "yes"))]
  ;; since we test xtdb by default now, we default to 1.12:
  (doseq [v (if all? ["1.10" "1.11" "1.12"] ["1.12"])]
    (run-tests env v)))
