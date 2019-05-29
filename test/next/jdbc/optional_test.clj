;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.optional-test
  "Test namespace for the optional builder functions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.optional :as opt]
            [next.jdbc.protocols :as p]
            [next.jdbc.test-fixtures :refer [with-test-db ds]]))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(deftest test-map-row-builder
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 1]
                              {:builder-fn opt/as-maps})]
      (is (map? row))
      (is (not (contains? row :FRUIT/GRADE)))
      (is (= 1 (:FRUIT/ID row)))
      (is (= "Apple" (:FRUIT/NAME row)))))
  (testing "unqualified row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 2]
                              {:builder-fn opt/as-unqualified-maps})]
      (is (map? row))
      (is (not (contains? row :COST)))
      (is (= 2 (:ID row)))
      (is (= "Banana" (:NAME row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              {:builder-fn opt/as-lower-maps})]
      (is (map? row))
      (is (not (contains? row :fruit/appearance)))
      (is (= 3 (:fruit/id row)))
      (is (= "Peach" (:fruit/name row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 4]
                              {:builder-fn opt/as-unqualified-lower-maps})]
      (is (map? row))
      (is (= 4 (:id row)))
      (is (= "Orange" (:name row))))))
