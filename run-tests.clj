#!/usr/bin/env bb

(require '[babashka.process :as p])

(let [maria? (= "maria" (first *command-line-args*))
      env
      (cond-> {"NEXT_JDBC_TEST_MSSQL" "yes"
               "NEXT_JDBC_TEST_MYSQL" "yes"
               "MSSQL_SA_PASSWORD"    "Str0ngP4ssw0rd"}
        maria?
        (assoc "NEXT_JDBC_TEST_MARIA" "yes"))
      {:keys [exit]}
      (p/shell {:extra-env env} "clojure" "-X:test")]
  (when-not (zero? exit)
    (System/exit exit)))
