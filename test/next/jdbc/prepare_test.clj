;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.prepare-test
  "Stub test namespace for PreparedStatement creation etc.

  Most of this functionality is core to all of the higher-level stuff
  so it gets tested that way, but there are some specific tests for
  `execute-batch!` here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.test-fixtures
             :refer [with-test-db ds jtds? postgres? sqlite?]]
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
    (when-not (or (jtds?) (postgres?) (sqlite?))
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
      (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))))
