#!/usr/bin/env bb

(require '[babashka.process :as p])

(defn- run-tests [env v]
  (when v (println "\nTesting Clojure" v))
  (let [{:keys [exit]}
        (p/shell {:extra-env env} "clojure" (str "-X"
                                                 (when v (str ":" v))
                                                 ":test"))]
    (when-not (zero? exit)
      (System/exit exit))))

(let [maria? (some #(= "maria" %) *command-line-args*)
      all?   (some #(= "all"   %) *command-line-args*)
      env
      (cond-> {"NEXT_JDBC_TEST_MSSQL" "yes"
               "NEXT_JDBC_TEST_MYSQL" "yes"
               "MSSQL_SA_PASSWORD"    "Str0ngP4ssw0rd"}
        maria?
        (assoc "NEXT_JDBC_TEST_MARIA" "yes"))]
  (doseq [v (if all? ["1.10" "1.11" "1.12"] [nil])]
    (run-tests env v)))
