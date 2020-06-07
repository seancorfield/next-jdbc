;; copyright (c) 2020 Sean Corfield, all rights reserved

(ns next.jdbc.datafy-test
  "Tests for the datafy extensions over JDBC types."
  (:require [clojure.datafy :as d]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.datafy]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs]
            [next.jdbc.test-fixtures
             :refer [with-test-db db ds
                      derby? jtds? mysql? postgres? sqlite?]]))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(def ^:private basic-connection-keys
  "Generic JDBC Connection fields."
  #{:autoCommit :catalog :clientInfo :holdability :metaData
    :networkTimeout :schema :transactionIsolation :typeMap :warnings
    ;; boolean properties
    :closed :readOnly
    ;; configured to be added as if by clojure.core/bean
    :class})

(deftest connection-datafy-tests
  (testing "connection datafication"
    (with-open [con (jdbc/get-connection (ds))]
      (let [reference-keys (cond-> basic-connection-keys
                             (derby?) (-> (disj :networkTimeout)
                                          (conj :networkTimeout/exception))
                             (jtds?)  (-> (disj :clientInfo :networkTimeout :schema)
                                          (conj :clientInfo/exception
                                                :networkTimeout/exception
                                                :schema/exception)))
            data (set (keys (d/datafy con)))]
        (when-let [diff (seq (set/difference data reference-keys))]
          (println (format "%6s :%-10s %s"
                           (:dbtype (db)) "connection" (str (sort diff)))))
        (is (= reference-keys
               (set/intersection reference-keys data)))))))

(def ^:private basic-database-metadata-keys
  "Generic JDBC Connection fields."
  #{:JDBCMajorVersion :JDBCMinorVersion :SQLKeywords :SQLStateType :URL
    :catalogSeparator :catalogTerm :catalogs
    :clientInfoProperties :connection
    :databaseMajorVersion :databaseMinorVersion
    :databaseProductName :databaseProductVersion
    :defaultTransactionIsolation
    :driverMajorVersion :driverMinorVersion :driverName :driverVersion
    :extraNameCharacters :identifierQuoteString
    :maxBinaryLiteralLength :maxCatalogNameLength :maxCharLiteralLength
    :maxColumnNameLength :maxColumnsInGroupBy :maxColumnsInIndex
    :maxColumnsInOrderBy :maxColumnsInSelect :maxColumnsInTable
    :maxConnections
    :maxCursorNameLength :maxIndexLength
    :maxProcedureNameLength :maxRowSize :maxSchemaNameLength
    :maxStatementLength :maxStatements :maxTableNameLength
    :maxTablesInSelect :maxUserNameLength :numericFunctions
    :procedureTerm :resultSetHoldability :rowIdLifetime
    :schemaTerm :schemas :searchStringEscape :stringFunctions
    :systemFunctions :tableTypes :timeDateFunctions
    :typeInfo :userName
    ;; boolean properties
    :catalogAtStart :readOnly
    ;; configured to be added as if by clojure.core/bean
    :class
    ;; added by next.jdbc.datafy if the datafication succeeds
    :all-tables})

(deftest database-metadata-datafy-tests
  (testing "database metadata datafication"
    (with-open [con (jdbc/get-connection (ds))]
      (let [reference-keys (cond-> basic-database-metadata-keys
                             (jtds?)     (-> (disj :clientInfoProperties :rowIdLifetime)
                                             (conj :clientInfoProperties/exception
                                                   :rowIdLifetime/exception))
                             (postgres?) (-> (disj :rowIdLifetime)
                                             (conj :rowIdLifetime/exception))
                             (sqlite?)   (-> (disj :clientInfoProperties :rowIdLifetime)
                                             (conj :clientInfoProperties/exception
                                                   :rowIdLifetime/exception)))
            data (set (keys (d/datafy (.getMetaData con))))]
        (when-let [diff (seq (set/difference data reference-keys))]
          (println (format "%6s :%-10s %s"
                           (:dbtype (db)) "db-meta" (str (sort diff)))))
        (is (= reference-keys
               (set/intersection reference-keys data))))))
  (testing "nav to catalogs yields object"
    (with-open [con (jdbc/get-connection (ds))]
      (let [data (d/datafy (.getMetaData con))]
        (doseq [k (cond-> #{:catalogs :clientInfoProperties :schemas :tableTypes :typeInfo}
                    (jtds?)   (disj :clientInfoProperties)
                    (sqlite?) (disj :clientInfoProperties))]
          (let [rs (d/nav data k nil)]
            (is (vector? rs))
            (is (every? map? rs))))))))

(deftest result-set-metadata-datafy-tests
  (testing "result set metadata datafication"
    (let [data (reduce (fn [_ row] (reduced (rs/metadata row)))
                       nil
                       (jdbc/plan (ds) [(str "SELECT * FROM "
                                             (if (mysql?) "fruit" "FRUIT"))]))]
      (is (vector? data))
      (is (= 5 (count data)))
      (is (every? map? data))
      (is (every? :label data)))))

(comment
  (def con (jdbc/get-connection (ds)))
  (rs/datafiable-result-set (.getTables (.getMetaData con) nil nil nil nil) con {})
  (def ps (jdbc/prepare con ["SELECT * FROM fruit WHERE grade > ?"]))
  (require '[next.jdbc.prepare :as prep])
  (prep/set-parameters ps [30])
  (.execute ps)
  (.getResultSet ps)
  (.close ps)
  (.close con))
