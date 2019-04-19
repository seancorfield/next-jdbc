;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set-test
  "Stub test namespace for the result set functions.

  There's so much that should be tested here:
  * column name generation functions
  * ReadableColumn protocol extension point
  * RowBuilder and ResultSetBuilder machinery
  * datafy/nav support
  * ResultSet-as-map for reducible! / -execute protocol
  * -execute-one and -execute-all implementations"
  (:require [clojure.datafy :as d]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.test-fixtures :refer [with-test-db ds]]
            [next.jdbc.result-set :as rs]))

(use-fixtures :once with-test-db)

(deftest test-datafy-nav
  (testing "default schema"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:table/fruit_id 2} connectable {})
          data (d/datafy test-row)
          v (get data :table/fruit_id)]
      ;; check datafication is sane
      (is (= 2 v))
      (let [object (d/nav data :table/fruit_id v)]
        ;; check nav produces a single map with the expected key/value data
        ;; and remember H2 is all UPPERCASE!
        (is (= 2 (:FRUIT/ID object)))
        (is (= "Banana" (:FRUIT/NAME object))))))
  (testing "custom schema :one"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:foo/bar 2} connectable
                                      {:schema {:foo/bar [:fruit :id]}})
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 2 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a single map with the expected key/value data
        ;; and remember H2 is all UPPERCASE!
        (is (= 2 (:FRUIT/ID object)))
        (is (= "Banana" (:FRUIT/NAME object))))))
  (testing "custom schema :many"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:foo/bar 2} connectable
                                      {:schema {:foo/bar [:fruit :id :many]}})
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 2 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a result set with the expected key/value data
        ;; and remember H2 is all UPPERCASE!
        (is (vector? object))
        (is (= 2 (:FRUIT/ID (first object))))
        (is (= "Banana" (:FRUIT/NAME (first object))))))))
