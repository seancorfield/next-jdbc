;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.prepare-test
  "Stub test namespace for PreparedStatement creation etc.

  Most of this functionality is core to all of the higher-level stuff
  so it gets tested that way.

  The tests for the deprecated version of `execute-batch!` are here
  as a guard against regressions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.test-fixtures
             :refer [with-test-db ds jtds? mssql? sqlite?]]
            [next.jdbc.prepare :as prep]
            [next.jdbc.specs :as specs]))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest execute-batch-tests
  (testing "simple batch insert"
    (is (= [1 1 1 1 1 1 1 1 1 13]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"])]
               (let [result (prep/execute-batch! ps [["fruit1" "one"]
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
               (let [result (prep/execute-batch! ps [["fruit1" "one"]
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
               (let [result (prep/execute-batch! ps [["fruit1" "one"]
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
                 (let [result (prep/execute-batch! ps [["fruit1" "one"]
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
    (when-not (or (mssql?) (sqlite?))
      (let [results
            (jdbc/with-transaction [t (ds) {:rollback-only true}]
              (with-open [ps (jdbc/prepare t ["
INSERT INTO fruit (name, appearance) VALUES (?,?)
"]
                                           {:return-keys true})]
                (let [result (prep/execute-batch! ps [["fruit1" "one"]
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
