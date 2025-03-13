;; copyright (c) 2019-2025 Sean Corfield, all rights reserved

(ns next.jdbc.connection-string-test
  "Tests for the main hash map spec to JDBC URL logic and the get-datasource
  and get-connection protocol implementations.

  At some point, the datasource/connection tests should probably be extended
  to accept EDN specs from an external source (environment variables?)."
  (:require [clojure.string :as str]
            [lazytest.core :refer [around set-ns-context!]]
            [lazytest.experimental.interfaces.clojure-test :refer [deftest is testing]]
            [next.jdbc.connection :as c]
            [next.jdbc.protocols :as p]
            [next.jdbc.specs :as specs]
            [next.jdbc.test-fixtures :refer [db with-test-db]])
  (:import [java.util Properties]))

(set! *warn-on-reflection* true)

(set-ns-context! [(around [f] (with-test-db f))])

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

(deftest property-tests
  (is (string? (.getProperty ^Properties (#'c/as-properties {:foo [42]}) "foo")))
  (is (string? (.get ^Properties (#'c/as-properties {:foo [42]}) "foo")))
  (is (vector? (.get ^Properties (#'c/as-properties
                                  {:foo [42]
                                   :next.jdbc/as-is-properties [:foo]})
                     "foo")))
  ;; because .getProperty drops non-string values!
  (is (nil? (.getProperty ^Properties (#'c/as-properties
                                       {:foo [42]
                                        :next.jdbc/as-is-properties [:foo]})
                          "foo"))))
