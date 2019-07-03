;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc-test
  "Not exactly a test suite -- more a series of examples."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.test-fixtures :refer [with-test-db ds]]
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs])
  (:import (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest basic-tests
  (testing "plan"
    (is (= "Apple"
           (reduce (fn [_ row] (reduced (:name row)))
                   nil
                   (jdbc/plan
                    (ds)
                    ["select * from fruit where appearance = ?" "red"])))))
  (testing "execute-one!"
    (is (= "Apple" (:FRUIT/NAME
                    (jdbc/execute-one!
                     (ds)
                     ["select * from fruit where appearance = ?" "red"])))))
  (testing "execute!"
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit where appearance = ?" "red"])]
      (is (= 1 (count rs)))
      (is (= 1 (:FRUIT/ID (first rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-maps})]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 (:FRUIT/ID (first rs))))
      (is (= 4 (:FRUIT/ID (last rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-arrays})]
      (is (every? vector? rs))
      (is (= 5 (count rs)))
      (is (every? #(= 5 (count %)) rs))
      ;; columns come first
      (is (every? qualified-keyword? (first rs)))
      ;; :FRUIT/ID should be first column
      (is (= :FRUIT/ID (ffirst rs)))
      ;; and all its corresponding values should be ints
      (is (every? int? (map first (rest rs))))
      (is (every? string? (map second (rest rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-unqualified-maps})]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 (:ID (first rs))))
      (is (= 4 (:ID (last rs)))))
    (let [rs (jdbc/execute!
              (ds)
              ["select * from fruit order by id"]
              {:builder-fn rs/as-unqualified-arrays})]
      (is (every? vector? rs))
      (is (= 5 (count rs)))
      (is (every? #(= 5 (count %)) rs))
      ;; columns come first
      (is (every? simple-keyword? (first rs)))
      ;; :ID should be first column
      (is (= :ID (ffirst rs)))
      ;; and all its corresponding values should be ints
      (is (every? int? (map first (rest rs))))
      (is (every? string? (map second (rest rs))))))
  (testing "prepare"
    (let [rs (with-open [con (jdbc/get-connection (ds))]
               (with-open [ps (jdbc/prepare
                               con
                               ["select * from fruit order by id"])]
                 (jdbc/execute! ps)))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 (:FRUIT/ID (first rs))))
      (is (= 4 (:FRUIT/ID (last rs)))))
    (let [rs (with-open [con (jdbc/get-connection (ds))]
               (with-open [ps (jdbc/prepare
                               con
                               ["select * from fruit where id = ?"])]
                 (jdbc/execute! (prep/set-parameters ps [4]))))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 1 (count rs)))
      (is (= 4 (:FRUIT/ID (first rs))))))
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
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction exception"
    (is (thrown? Throwable
           (jdbc/with-transaction [t (ds)]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])
             (throw (ex-info "abort" {})))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction call rollback"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction with unnamed save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t)
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
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
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))))

(deftest plan-misuse
  (let [s (pr-str (jdbc/plan (ds) ["select * from fruit"]))]
    (is (re-find #"missing reduction" s)))
  (let [s (pr-str (into [] (jdbc/plan (ds) ["select * from fruit"])))]
    (is (re-find #"missing `map` or `reduce`" s)))
  (let [s (pr-str (into [] (take 3) (jdbc/plan (ds) ["select * from fruit"])))]
    (is (re-find #"missing `map` or `reduce`" s)))
  (is (thrown? IllegalArgumentException
               (doall (take 3 (jdbc/plan (ds) ["select * from fruit"]))))))
