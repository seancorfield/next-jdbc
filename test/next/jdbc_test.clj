;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc-test
  "Basic tests for the primary API of `next.jdbc`."
  (:require [clojure.core.reducers :as r]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as c]
            [next.jdbc.test-fixtures
             :refer [with-test-db db ds column
                     default-options stored-proc?
                     derby? hsqldb? jtds? mssql? mysql? postgres? sqlite?]]
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs]
            [next.jdbc.types :as types])
  (:import (com.zaxxer.hikari HikariDataSource)
           (com.mchange.v2.c3p0 ComboPooledDataSource PooledDataSource)
           (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

;; around each test because of the folding tests using 1,000 rows
(use-fixtures :each with-test-db)

(specs/instrument)

(deftest spec-tests
  (let [db-spec {:dbtype "h2:mem" :dbname "clojure_test"}]
    ;; some sanity checks on instrumented function calls:
    (jdbc/get-datasource db-spec)
    (jdbc/get-connection db-spec)
    ;; and again with options:
    (let [db-spec' (jdbc/with-options db-spec {})]
      (jdbc/get-datasource db-spec')
      (jdbc/get-connection db-spec'))))

(deftest basic-tests
  ;; use ds-opts instead of (ds) anywhere you want default options applied:
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (testing "plan"
      (is (= "Apple"
             (reduce (fn [_ row] (reduced (:name row)))
                     nil
                     (jdbc/plan
                      ds-opts
                      ["select * from fruit where appearance = ?" "red"]))))
      (is (= "Banana"
             (reduce (fn [_ row] (reduced (:no-such-column row "Banana")))
                     nil
                     (jdbc/plan
                      ds-opts
                      ["select * from fruit where appearance = ?" "red"])))))
    (testing "execute-one!"
      (is (nil? (jdbc/execute-one!
                 (ds)
                 ["select * from fruit where appearance = ?" "neon-green"])))
      (is (= "Apple" ((column :FRUIT/NAME)
                      (jdbc/execute-one!
                       ds-opts
                       ["select * from fruit where appearance = ?" "red"]))))
      (is (= "red" (:fruit/looks-like
                    (jdbc/execute-one!
                     ds-opts
                     ["select appearance as looks_like from fruit where id = ?" 1]
                     jdbc/snake-kebab-opts))))
      (let [ds' (jdbc/with-options ds-opts jdbc/snake-kebab-opts)]
        (is (= "red" (:fruit/looks-like
                      (jdbc/execute-one!
                       ds'
                       ["select appearance as looks_like from fruit where id = ?" 1])))))
      (jdbc/with-transaction+options [ds' (jdbc/with-options ds-opts jdbc/snake-kebab-opts)]
        (is (= (merge (default-options) jdbc/snake-kebab-opts)
               (:options ds')))
        (is (= "red" (:fruit/looks-like
                      (jdbc/execute-one!
                       ds'
                       ["select appearance as looks_like from fruit where id = ?" 1])))))
      (is (= "red" (:looks-like
                    (jdbc/execute-one!
                     ds-opts
                     ["select appearance as looks_like from fruit where id = ?" 1]
                     jdbc/unqualified-snake-kebab-opts)))))
    (testing "execute!"
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit where appearance = ?" "neon-green"])]
        (is (vector? rs))
        (is (= [] rs)))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit where appearance = ?" "red"])]
        (is (= 1 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn rs/as-maps})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 4 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs))))
        (is (= 4 ((column :FRUIT/ID) (last rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn rs/as-arrays})]
        (is (every? vector? rs))
        (is (= 5 (count rs)))
        (is (every? #(= 5 (count %)) rs))
        ;; columns come first
        (is (every? qualified-keyword? (first rs)))
        ;; :FRUIT/ID should be first column
        (is (= (column :FRUIT/ID) (ffirst rs)))
        ;; and all its corresponding values should be ints
        (is (every? int? (map first (rest rs))))
        (is (every? string? (map second (rest rs))))))
    (testing "execute! with adapter"
      (let [rs (jdbc/execute! ; test again, with adapter and lower columns
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn (rs/as-arrays-adapter
                              rs/as-lower-arrays
                              (fn [^ResultSet rs _ ^Integer i]
                                (.getObject rs i)))})]
        (is (every? vector? rs))
        (is (= 5 (count rs)))
        (is (every? #(= 5 (count %)) rs))
        ;; columns come first
        (is (every? qualified-keyword? (first rs)))
        ;; :fruit/id should be first column
        (is (= :fruit/id (ffirst rs)))
        ;; and all its corresponding values should be ints
        (is (every? int? (map first (rest rs))))
        (is (every? string? (map second (rest rs))))))
    (testing "execute! with unqualified"
      (let [rs (jdbc/execute!
                (ds)
                ["select * from fruit order by id"]
                {:builder-fn rs/as-unqualified-maps})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 4 (count rs)))
        (is (= 1 ((column :ID) (first rs))))
        (is (= 4 ((column :ID) (last rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn rs/as-unqualified-arrays})]
        (is (every? vector? rs))
        (is (= 5 (count rs)))
        (is (every? #(= 5 (count %)) rs))
        ;; columns come first
        (is (every? simple-keyword? (first rs)))
        ;; :ID should be first column
        (is (= (column :ID) (ffirst rs)))
        ;; and all its corresponding values should be ints
        (is (every? int? (map first (rest rs))))
        (is (every? string? (map second (rest rs))))))
    (testing "execute! with :max-rows / :maxRows"
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:max-rows 2})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 2 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs))))
        (is (= 2 ((column :FRUIT/ID) (last rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:statement {:maxRows 2}})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 2 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs))))
        (is (= 2 ((column :FRUIT/ID) (last rs)))))))
  (testing "prepare"
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))
                         ps  (jdbc/prepare
                              con
                              ["select * from fruit order by id"]
                              (default-options))]
                 (jdbc/execute! ps))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((column :FRUIT/ID) (first rs))))
      (is (= 4 ((column :FRUIT/ID) (last rs)))))
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))
                         ps  (jdbc/prepare
                              con
                              ["select * from fruit where id = ?"]
                              (default-options))]
                 (jdbc/execute! (prep/set-parameters ps [4]) nil {}))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 1 (count rs)))
      (is (= 4 ((column :FRUIT/ID) (first rs))))))
  (testing "statement"
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))]
               (jdbc/execute! (prep/statement con (default-options))
                              ["select * from fruit order by id"]))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((column :FRUIT/ID) (first rs))))
      (is (= 4 ((column :FRUIT/ID) (last rs)))))
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))]
               (jdbc/execute! (prep/statement con (default-options))
                              ["select * from fruit where id = 4"]))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 1 (count rs)))
      (is (= 4 ((column :FRUIT/ID) (first rs))))))
  (testing "transact"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/transact (ds)
                          (fn [t] (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))
                          {:rollback-only true})))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction rollback-only"
    (is (not (jdbc/active-tx?)) "should not be in a transaction")
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (is (jdbc/active-tx?) "should be in a transaction")
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (is (not (jdbc/active-tx?)) "should not be in a transaction")
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con {:rollback-only true}]
                 (is (jdbc/active-tx?) "should be in a transaction")
                 (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction exception"
    (is (thrown? Throwable
           (jdbc/with-transaction [t (ds)]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])
             (is (jdbc/active-tx?) "should be in a transaction")
             (throw (ex-info "abort" {})))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (is (not (jdbc/active-tx?)) "should not be in a transaction")
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (thrown? Throwable
               (jdbc/with-transaction [t con]
                 (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])
                 (is (jdbc/active-tx?) "should be in a transaction")
                 (throw (ex-info "abort" {})))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction call rollback"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t)
               ;; still in a next.jdbc TX even tho' we rolled back!
               (is (jdbc/active-tx?) "should be in a transaction")
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (is (not (jdbc/active-tx?)) "should not be in a transaction")
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con]
                 (let [result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
                   (.rollback t)
                   result))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction with unnamed save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t)
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               ;; still in a next.jdbc TX even tho' we rolled back to a save point!
               (is (jdbc/active-tx?) "should be in a transaction")
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (is (not (jdbc/active-tx?)) "should not be in a transaction")
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con]
                 (let [save-point (.setSavepoint t)
                       result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
                   (.rollback t save-point)
                   result))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction with named save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t (name (gensym)))
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con]
                 (let [save-point (.setSavepoint t (name (gensym)))
                       result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
                   (.rollback t save-point)
                   result))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con)))))))

(deftest issue-146
  ;; since we use an embedded PostgreSQL data source, we skip this:
  (when-not (or (postgres?)
                ;; and now we skip MS SQL because we can't use the db-spec
                ;; we'd need to build the jdbcUrl with encryption turned off:
                (and (mssql?) (not (jtds?))))
    (testing "Hikari and SavePoints"
      (with-open [^HikariDataSource ds (c/->pool HikariDataSource
                                        (let [db (db)]
                                          (cond-> db
                                            ;; jTDS does not support isValid():
                                            (jtds?)
                                            (assoc :connectionTestQuery "SELECT 1")
                                            ;; HikariCP needs username, not user:
                                            (contains? db :user)
                                            (assoc :username (:user db)))))]
        (testing "with-transaction with unnamed save point"
          (is (= [{:next.jdbc/update-count 1}]
                (jdbc/with-transaction [t ds]
                  (let [save-point (.setSavepoint t)
                        result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                    (.rollback t save-point)
                    result))))
          (is (= 4 (count (jdbc/execute! ds ["select * from fruit"]))))
          (with-open [con (jdbc/get-connection ds)]
            (let [ac (.getAutoCommit con)]
              (is (= [{:next.jdbc/update-count 1}]
                    (jdbc/with-transaction [t con]
                      (let [save-point (.setSavepoint t)
                            result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                        (.rollback t save-point)
                        result))))
              (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
              (is (= ac (.getAutoCommit con))))))
        (testing "with-transaction with named save point"
          (is (= [{:next.jdbc/update-count 1}]
                (jdbc/with-transaction [t ds]
                  (let [save-point (.setSavepoint t (name (gensym)))
                        result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                    (.rollback t save-point)
                    result))))
          (is (= 4 (count (jdbc/execute! ds ["select * from fruit"]))))
          (with-open [con (jdbc/get-connection ds)]
            (let [ac (.getAutoCommit con)]
              (is (= [{:next.jdbc/update-count 1}]
                    (jdbc/with-transaction [t con]
                      (let [save-point (.setSavepoint t (name (gensym)))
                            result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                        (.rollback t save-point)
                        result))))
              (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
              (is (= ac (.getAutoCommit con))))))))
    (testing "c3p0 and SavePoints"
      (with-open [^PooledDataSource ds (c/->pool ComboPooledDataSource (db))]
        (testing "with-transaction with unnamed save point"
          (is (= [{:next.jdbc/update-count 1}]
                (jdbc/with-transaction [t ds]
                  (let [save-point (.setSavepoint t)
                        result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                    (.rollback t save-point)
                    result))))
          (is (= 4 (count (jdbc/execute! ds ["select * from fruit"]))))
          (with-open [con (jdbc/get-connection ds)]
            (let [ac (.getAutoCommit con)]
              (is (= [{:next.jdbc/update-count 1}]
                    (jdbc/with-transaction [t con]
                      (let [save-point (.setSavepoint t)
                            result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                        (.rollback t save-point)
                        result))))
              (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
              (is (= ac (.getAutoCommit con))))))
        (testing "with-transaction with named save point"
          (is (= [{:next.jdbc/update-count 1}]
                (jdbc/with-transaction [t ds]
                  (let [save-point (.setSavepoint t (name (gensym)))
                        result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                    (.rollback t save-point)
                    result))))
          (is (= 4 (count (jdbc/execute! ds ["select * from fruit"]))))
          (with-open [con (jdbc/get-connection ds)]
            (let [ac (.getAutoCommit con)]
              (is (= [{:next.jdbc/update-count 1}]
                    (jdbc/with-transaction [t con]
                      (let [save-point (.setSavepoint t (name (gensym)))
                            result (jdbc/execute! t ["
      INSERT INTO fruit (name, appearance, cost, grade)
      VALUES ('Pear', 'green', 49, 47)
      "])]
                        (.rollback t save-point)
                        result))))
              (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
              (is (= ac (.getAutoCommit con))))))))))

#_
(deftest duplicate-insert-test
  ;; this is primarily a look at exception types/information for #226
  (try
    (jdbc/execute! (ds) ["
    INSERT INTO fruit (id, name, appearance, cost, grade)
    VALUES (1234, '1234', '1234', 1234, 1234)
    "])
    (try
      (jdbc/execute! (ds) ["
      INSERT INTO fruit (id, name, appearance, cost, grade)
      VALUES (1234, '1234', '1234', 1234, 1234)
      "])
      (println (:dbtype (db)) "allowed duplicate insert")
      (catch java.sql.SQLException t
        (println (:dbtype (db)) "duplicate insert threw" (type t)
                 "error" (.getErrorCode t) "state" (.getSQLState t)
                 "\n\t" (ex-message t))))
    (catch java.sql.SQLException t
      (println (:dbtype (db)) "will not allow specific ID" (type t)
               "error" (.getErrorCode t) "state" (.getSQLState t)
               "\n\t" (ex-message t)))))

(deftest bool-tests
  (doseq [[n b] [["zero" 0] ["one" 1] ["false" false] ["true" true]]
          :let [v-bit  (if (number? b) b (if b 1 0))
                v-bool (if (number? b) (pos? b) b)]]
    (jdbc/execute-one!
     (ds)
     ["insert into btest (name,is_it,twiddle) values (?,?,?)"
      n
      (if (postgres?)
        (types/as-boolean b)
        b) ; 0, 1, false, true are all acceptable
      (cond (hsqldb?)
            v-bool ; hsqldb requires a boolean here
            (postgres?)
            (types/as-other v-bit) ; really postgres??
            :else
            v-bit)]))
  (let [data (jdbc/execute! (ds) ["select * from btest"]
                            (default-options))]
    (if (sqlite?)
      (is (every? number?  (map (column :BTEST/IS_IT) data)))
      (is (every? boolean? (map (column :BTEST/IS_IT) data))))
    (if (or (sqlite?) (derby?))
      (is (every? number?  (map (column :BTEST/TWIDDLE) data)))
      (is (every? boolean? (map (column :BTEST/TWIDDLE) data)))))
  (let [data (jdbc/execute! (ds) ["select * from btest"]
                            (cond-> (default-options)
                              (sqlite?)
                              (assoc :builder-fn
                                     (rs/builder-adapter
                                      rs/as-maps
                                      (fn [builder ^ResultSet rs ^Integer i]
                                        (let [rsm ^ResultSetMetaData (:rsmeta builder)]
                                          (rs/read-column-by-index
                                           ;; we only use bit and bool for
                                           ;; sqlite (not boolean)
                                           (if (#{"BIT" "BOOL"} (.getColumnTypeName rsm i))
                                             (.getBoolean rs i)
                                             (.getObject rs i))
                                           rsm
                                           i)))))))]
    (is (every? boolean? (map (column :BTEST/IS_IT) data)))
    (if (derby?)
      (is (every? number?  (map (column :BTEST/TWIDDLE) data)))
      (is (every? boolean? (map (column :BTEST/TWIDDLE) data)))))
  (let [data (reduce (fn [acc row]
                       (conj acc (cond-> (select-keys row [:is_it :twiddle])
                                   (sqlite?)
                                   (update :is_it pos?)
                                   (or (sqlite?) (derby?))
                                   (update :twiddle pos?))))
                     []
                     (jdbc/plan (ds) ["select * from btest"]))]
    (is (every? boolean? (map :is_it data)))
    (is (every? boolean? (map :twiddle data)))))

(deftest execute-batch-tests
  (testing "simple batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"])]
               (let [result (jdbc/execute-batch! ps [["fruit1" "one"]
                                                     ["fruit2" "two"]
                                                     ["fruit3" "three"]
                                                     ["fruit4" "four"]
                                                     ["fruit5" "five"]
                                                     ["fruit6" "six"]
                                                     ["fruit7" "seven"]
                                                     ["fruit8" "eight"]
                                                     ["fruit9" "nine"]])]
                 (conj result (count (jdbc/execute! t ["select * from fruit"]))))))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "small batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"])]
               (let [result (jdbc/execute-batch! ps [["fruit1" "one"]
                                                     ["fruit2" "two"]
                                                     ["fruit3" "three"]
                                                     ["fruit4" "four"]
                                                     ["fruit5" "five"]
                                                     ["fruit6" "six"]
                                                     ["fruit7" "seven"]
                                                     ["fruit8" "eight"]
                                                     ["fruit9" "nine"]]
                                                 {:batch-size 3})]
                 (conj result (count (jdbc/execute! t ["select * from fruit"]))))))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "big batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"])]
               (let [result (jdbc/execute-batch! ps [["fruit1" "one"]
                                                     ["fruit2" "two"]
                                                     ["fruit3" "three"]
                                                     ["fruit4" "four"]
                                                     ["fruit5" "five"]
                                                     ["fruit6" "six"]
                                                     ["fruit7" "seven"]
                                                     ["fruit8" "eight"]
                                                     ["fruit9" "nine"]]
                                                 {:batch-size 8})]
                 (conj result (count (jdbc/execute! t ["select * from fruit"]))))))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "large batch insert"
    (when-not (or (jtds?) (sqlite?))
      (is (= [1 1 1 1 1 1 1 1 1 13]
             (jdbc/with-transaction [t (ds) {:rollback-only true}]
               (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"])]
                 (let [result (jdbc/execute-batch! ps [["fruit1" "one"]
                                                       ["fruit2" "two"]
                                                       ["fruit3" "three"]
                                                       ["fruit4" "four"]
                                                       ["fruit5" "five"]
                                                       ["fruit6" "six"]
                                                       ["fruit7" "seven"]
                                                       ["fruit8" "eight"]
                                                       ["fruit9" "nine"]]
                                                   {:batch-size 4
                                                    :large true})]
                   (conj result (count (jdbc/execute! t ["select * from fruit"]))))))))
      (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))))
  (testing "return generated keys"
    (when-not (mssql?)
      (let [results
            (jdbc/with-transaction [t (ds) {:rollback-only true}]
              (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"]
                                           {:return-keys true})]
                (let [result (jdbc/execute-batch! ps [["fruit1" "one"]
                                                      ["fruit2" "two"]
                                                      ["fruit3" "three"]
                                                      ["fruit4" "four"]
                                                      ["fruit5" "five"]
                                                      ["fruit6" "six"]
                                                      ["fruit7" "seven"]
                                                      ["fruit8" "eight"]
                                                      ["fruit9" "nine"]]
                                                  {:batch-size 4
                                                   :return-generated-keys true})]
                  (conj result (count (jdbc/execute! t ["select * from fruit"]))))))]
        (is (= 13 (last results)))
        (is (every? map? (butlast results)))
        ;; Derby and SQLite only return one generated key per batch so there
        ;; are only three keys, plus the overall count here:
        (is (< 3 (count results))))
      (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))))

(deftest execute-batch-connectable-tests
  (testing "simple batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (try
             (let [result (jdbc/execute-batch! (ds)
                                               "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                               [["fruit1" "one"]
                                                ["fruit2" "two"]
                                                ["fruit3" "three"]
                                                ["fruit4" "four"]
                                                ["fruit5" "five"]
                                                ["fruit6" "six"]
                                                ["fruit7" "seven"]
                                                ["fruit8" "eight"]
                                                ["fruit9" "nine"]]
                                               {})]
               (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
             (finally
               (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "batch with-options"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (try
             (let [result (jdbc/execute-batch! (jdbc/with-options (ds) {})
                                               "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                               [["fruit1" "one"]
                                                ["fruit2" "two"]
                                                ["fruit3" "three"]
                                                ["fruit4" "four"]
                                                ["fruit5" "five"]
                                                ["fruit6" "six"]
                                                ["fruit7" "seven"]
                                                ["fruit8" "eight"]
                                                ["fruit9" "nine"]]
                                               {})]
               (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
             (finally
               (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "batch with-logging"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (try
             (let [result (jdbc/execute-batch! (jdbc/with-logging (ds) println println)
                                               "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                               [["fruit1" "one"]
                                                ["fruit2" "two"]
                                                ["fruit3" "three"]
                                                ["fruit4" "four"]
                                                ["fruit5" "five"]
                                                ["fruit6" "six"]
                                                ["fruit7" "seven"]
                                                ["fruit8" "eight"]
                                                ["fruit9" "nine"]]
                                               {})]
               (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
             (finally
               (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "small batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (try
             (let [result (jdbc/execute-batch! (ds)
                                               "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                               [["fruit1" "one"]
                                                ["fruit2" "two"]
                                                ["fruit3" "three"]
                                                ["fruit4" "four"]
                                                ["fruit5" "five"]
                                                ["fruit6" "six"]
                                                ["fruit7" "seven"]
                                                ["fruit8" "eight"]
                                                ["fruit9" "nine"]]
                                               {:batch-size 3})]
               (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
             (finally
               (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "big batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (try
             (let [result (jdbc/execute-batch! (ds)
                                               "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                               [["fruit1" "one"]
                                                ["fruit2" "two"]
                                                ["fruit3" "three"]
                                                ["fruit4" "four"]
                                                ["fruit5" "five"]
                                                ["fruit6" "six"]
                                                ["fruit7" "seven"]
                                                ["fruit8" "eight"]
                                                ["fruit9" "nine"]]
                                               {:batch-size 8})]
               (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
             (finally
               (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "large batch insert"
    (when-not (or (jtds?) (sqlite?))
      (is (= [1 1 1 1 1 1 1 1 1 13]
             (try
               (let [result (jdbc/execute-batch! (ds)
                                                 "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                                 [["fruit1" "one"]
                                                  ["fruit2" "two"]
                                                  ["fruit3" "three"]
                                                  ["fruit4" "four"]
                                                  ["fruit5" "five"]
                                                  ["fruit6" "six"]
                                                  ["fruit7" "seven"]
                                                  ["fruit8" "eight"]
                                                  ["fruit9" "nine"]]
                                                 {:batch-size 4
                                                  :large true})]
                 (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
               (finally
                 (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))))
      (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))))
  (testing "return generated keys"
    (when-not (mssql?)
      (let [results
            (try
              (let [result (jdbc/execute-batch! (ds)
                                                "INSERT INTO fruit (name, appearance) VALUES (?,?)"
                                                [["fruit1" "one"]
                                                 ["fruit2" "two"]
                                                 ["fruit3" "three"]
                                                 ["fruit4" "four"]
                                                 ["fruit5" "five"]
                                                 ["fruit6" "six"]
                                                 ["fruit7" "seven"]
                                                 ["fruit8" "eight"]
                                                 ["fruit9" "nine"]]
                                                ;; note: we need both :return-keys true for creating
                                                ;; the PreparedStatement and :return-generated-keys
                                                ;; true to control the way batch execution happens:
                                                {:batch-size 4 :return-keys true
                                                 :return-generated-keys true})]
                (conj result (count (jdbc/execute! (ds) ["select * from fruit"]))))
              (finally
                (jdbc/execute-one! (ds) ["delete from fruit where id > 4"])))]
        (is (= 13 (last results)))
        (is (every? map? (butlast results)))
        ;; Derby and SQLite only return one generated key per batch so there
        ;; are only three keys, plus the overall count here:
        (is (< 3 (count results))))
      (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))))

(deftest folding-test
  (jdbc/execute-one! (ds) ["delete from fruit"])
  (with-open [con (jdbc/get-connection (ds))
              ps  (jdbc/prepare con ["insert into fruit(name) values (?)"])]
    (jdbc/execute-batch! ps (mapv #(vector (str "Fruit-" %)) (range 1 1001))))
  (testing "foldable result set"
    (testing "from a Connection"
      (let [result
            (with-open [con (jdbc/get-connection (ds))]
              (r/foldcat
               (r/map (column :FRUIT/NAME)
                      (jdbc/plan con ["select * from fruit order by id"]
                                 (default-options)))))]
        (is (= 1000 (count result)))
        (is (= "Fruit-1" (first result)))
        (is (= "Fruit-1000" (last result)))))
    (testing "from a DataSource"
      (doseq [n [2 3 4 5 100 300 500 700 900 1000 1100]]
        (testing (str "folding with n = " n)
          (let [result
                (try
                  (r/fold n r/cat r/append!
                          (r/map (column :FRUIT/NAME)
                                 (jdbc/plan (ds) ["select * from fruit order by id"]
                                            (default-options))))
                  (catch java.util.concurrent.RejectedExecutionException _
                    []))]
            (is (= 1000 (count result)))
            (is (= "Fruit-1" (first result)))
            (is (= "Fruit-1000" (last result)))))))
    (testing "from a PreparedStatement"
      (let [result
            (with-open [con (jdbc/get-connection (ds))
                        stmt (jdbc/prepare con
                                           ["select * from fruit order by id"]
                                           (default-options))]
              (r/foldcat
               (r/map (column :FRUIT/NAME)
                      (jdbc/plan stmt nil (default-options)))))]
        (is (= 1000 (count result)))
        (is (= "Fruit-1" (first result)))
        (is (= "Fruit-1000" (last result)))))
    (testing "from a Statement"
      (let [result
            (with-open [con (jdbc/get-connection (ds))
                        stmt (prep/statement con (default-options))]
              (r/foldcat
               (r/map (column :FRUIT/NAME)
                      (jdbc/plan stmt ["select * from fruit order by id"]
                                 (default-options)))))]
        (is (= 1000 (count result)))
        (is (= "Fruit-1" (first result)))
        (is (= "Fruit-1000" (last result)))))))

(deftest connection-tests
  (testing "datasource via jdbcUrl"
    (when-not (postgres?)
      (let [[url etc] (#'c/spec->url+etc (db))
            ds (jdbc/get-datasource (assoc etc :jdbcUrl url))]
        (cond (derby?) (is (= {:create true} etc))
              (mssql?) (is (= (cond-> #{:user :password}
                                (not (jtds?))
                                (conj :encrypt :trustServerCertificate))
                              (set (keys etc))))
              (mysql?) (is (= #{:user :password :useSSL :allowMultiQueries}
                              (disj (set (keys etc)) :disableMariaDbDriver)))
              :else    (is (= {} etc)))
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) (str "jdbc:"
                                           (cond (jtds?)
                                                 "jtds:sqlserver"
                                                 (mssql?)
                                                 "sqlserver"
                                                 :else
                                                 (:dbtype (db))))))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (jdbc/get-datasource ds)))
        (with-open [con (jdbc/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))))

(deftest multi-rs
  (when (mssql?)
    (testing "script with multiple result sets"
      (let [multi-rs
            (jdbc/execute! (ds)
                           [(str "begin"
                                 " select * from fruit;"
                                 " select * from fruit where id < 4;"
                                 " end")]
                           {:multi-rs true})]
        (is (= 2 (count multi-rs)))
        (is (= 4 (count (first multi-rs))))
        (is (= 3 (count (second multi-rs)))))))
  (when (mysql?)
    (testing "script with multiple result sets"
      (let [multi-rs
            (jdbc/execute! (ds)
                           [(str "select * from fruit;"
                                 " select * from fruit where id < 4")]
                           {:multi-rs true})]
        (is (= 2 (count multi-rs)))
        (is (= 4 (count (first multi-rs))))
        (is (= 3 (count (second multi-rs)))))))
  (when (stored-proc?)
    (testing "stored proc; multiple result sets"
      (try
        (let [multi-rs
              (jdbc/execute! (ds)
                             [(if (mssql?) "EXEC FRUITP" "CALL FRUITP()")]
                             {:multi-rs true})
              zero-updates [{:next.jdbc/update-count 0}]]
          (cond (postgres?) ; does not support multiple result sets yet
                (do
                  (is (= 1 (count multi-rs)))
                  (is (= zero-updates (first multi-rs))))
                (hsqldb?)
                (do
                  (is (= 3 (count multi-rs)))
                  (is (= zero-updates (first multi-rs))))
                (mysql?)
                (do
                  (is (= 3 (count multi-rs)))
                  (is (= zero-updates (last multi-rs))))
                :else
                (is (= 2 (count multi-rs)))))
        (catch Throwable t
          (println 'call-proc (:dbtype (db)) (ex-message t) (some-> t (ex-cause) (ex-message))))))))

(deftest plan-misuse
  (let [s (pr-str (jdbc/plan (ds) ["select * from fruit"]))]
    (is (re-find #"missing reduction" s)))
  (let [s (pr-str (into [] (jdbc/plan (ds) ["select * from fruit"])))]
    (is (re-find #"missing `map` or `reduce`" s)))
  ;; this may succeed or not, depending on how the driver handles things
  ;; most drivers will error because the ResultSet was closed before pr-str
  ;; is invoked (which will attempt to print each row)
  (let [s (pr-str (into [] (take 3) (jdbc/plan (ds) ["select * from fruit"]
                                               (default-options))))]
    (is (or (re-find #"missing `map` or `reduce`" s)
            (re-find #"(?i)^\[#:fruit\{.*:id.*\}\]$" s))))
  (is (every? #(re-find #"(?i)^#:fruit\{.*:id.*\}$" %)
              (into [] (map str) (jdbc/plan (ds) ["select * from fruit"]
                                            (default-options)))))
  (is (every? #(re-find #"(?i)^#:fruit\{.*:id.*\}$" %)
              (into [] (map pr-str) (jdbc/plan (ds) ["select * from fruit"]
                                               (default-options)))))
  (is (thrown? IllegalArgumentException
               (doall (take 3 (jdbc/plan (ds) ["select * from fruit"]))))))

(deftest issue-204
  (testing "against a Connection"
    (is (seq (with-open [con (jdbc/get-connection (ds))]
               (jdbc/on-connection
                [x con]
                (jdbc/execute! x ["select * from fruit"]))))))
  (testing "against a wrapped Connection"
    (is (seq (with-open [con (jdbc/get-connection (ds))]
               (jdbc/on-connection
                [x (jdbc/with-options con {})]
                (jdbc/execute! x ["select * from fruit"]))))))
  (testing "against a wrapped Datasource"
    (is (seq (jdbc/on-connection
              [x (jdbc/with-options (ds) {})]
              (jdbc/execute! x ["select * from fruit"])))))
  (testing "against a Datasource"
    (is (seq (jdbc/on-connection
              [x (ds)]
              (jdbc/execute! x ["select * from fruit"]))))))

(deftest issue-256
  (testing "against a Connection"
    (is (seq (with-open [con (jdbc/get-connection (ds))]
               (jdbc/on-connection+options
                [x con] ; raw connection stays raw
                (is (instance? java.sql.Connection x))
                (jdbc/execute! x ["select * from fruit"]))))))
  (testing "against a wrapped Connection"
    (is (seq (with-open [con (jdbc/get-connection (ds))]
               (jdbc/on-connection+options
                [x (jdbc/with-options con {:test-option 42})]
                ;; ensure we get the same wrapped connection
                (is (instance? java.sql.Connection (:connectable x)))
                (is (= {:test-option 42} (:options x)))
                (jdbc/execute! x ["select * from fruit"]))))))
  (testing "against a wrapped Datasource"
    (is (seq (jdbc/on-connection+options
              [x (jdbc/with-options (ds) {:test-option 42})]
              ;; ensure we get a wrapped connection
              (is (instance? java.sql.Connection (:connectable x)))
              (is (= {:test-option 42} (:options x)))
              (jdbc/execute! x ["select * from fruit"])))))
  (testing "against a Datasource"
    (is (seq (jdbc/on-connection+options
              [x (ds)] ; unwrapped datasource has no options
              ;; ensure we get a wrapped connection (empty options)
              (is (instance? java.sql.Connection (:connectable x)))
              (is (= {} (:options x)))
              (jdbc/execute! x ["select * from fruit"]))))))
