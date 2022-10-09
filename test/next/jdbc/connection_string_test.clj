;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.connection-string-test
  "Tests for the main hash map spec to JDBC URL logic and the get-datasource
  and get-connection protocol implementations.

  At some point, the datasource/connection tests should probably be extended
  to accept EDN specs from an external source (environment variables?)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.connection :as c]
            [next.jdbc.protocols :as p]
            [next.jdbc.specs :as specs]
            [next.jdbc.test-fixtures :refer [with-test-db db]]))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest test-uri-strings
  (testing "datasource via String"
    (let [db-spec (db)
          db-spec (if (= "embedded-postgres" (:dbtype db-spec))
                    (assoc db-spec :dbtype "postgresql")
                    db-spec)
          [url etc] (#'c/spec->url+etc db-spec)
          {:keys [user password]} etc
          etc (dissoc etc :user :password)
          uri (-> url
                  ;; strip jdbc: prefix for fun
                  (str/replace #"^jdbc:" "")
                  (str/replace #";" "?") ; for SQL Server tests
                  (str/replace #":sqlserver" "") ; for SQL Server tests
                  (cond-> (and user password)
                    (str/replace #"://" (str "://" user ":" password "@"))))
          ds (p/get-datasource (assoc etc :jdbcUrl uri))]
      (when (and user password)
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))))
