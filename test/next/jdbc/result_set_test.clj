;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set-test
  "Stub test namespace for the result set functions.

  There's so much that should be tested here:
  * column name generation functions
  * ReadableColumn protocol extension point
  * RowBuilder and ResultSetBuilder machinery
  * ResultSet-as-map for reducible! / -execute protocol
  * -execute-one and -execute-all implementations"
  (:require [clojure.datafy :as d]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.test-fixtures :refer [with-test-db ds]]))

(use-fixtures :once with-test-db)

(deftest test-datafy-nav
  (testing "default schema"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:table/fruit_id 1} connectable {})
          data (d/datafy test-row)
          v (get data :table/fruit_id)]
      ;; check datafication is sane
      (is (= 1 v))
      (let [object (d/nav data :table/fruit_id v)]
        ;; check nav produces a single map with the expected key/value data
        ;; and remember H2 is all UPPERCASE!
        (is (= 1 (:FRUIT/ID object)))
        (is (= "Apple" (:FRUIT/NAME object))))))
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
          test-row (rs/datafiable-row {:foo/bar 3} connectable
                                      {:schema {:foo/bar [:fruit :id :many]}})
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 3 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a result set with the expected key/value data
        ;; and remember H2 is all UPPERCASE!
        (is (vector? object))
        (is (= 3 (:FRUIT/ID (first object))))
        (is (= "Peach" (:FRUIT/NAME (first object))))))))


(defn get-lower-column-names [^java.sql.ResultSetMetaData rsmeta opts]
  (let [idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (mapv (fn [^Integer i]
            (keyword (str/lower-case (.getColumnLabel rsmeta i))))
          idxs)))

(defn as-lower-maps [^java.sql.ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (get-lower-column-names rsmeta opts)]
    (rs/->MapResultSetBuilder rs rsmeta cols)))

(deftest test-map-row-builder
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 1]
                              {})]
      (is (map? row))
      (is (= 1 (:FRUIT/ID row)))
      (is (= "Apple" (:FRUIT/NAME row)))))
  (testing "unqualified row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 2]
                              {:gen-fn rs/as-unqualified-maps})]
      (is (map? row))
      (is (= 2 (:ID row)))
      (is (= "Banana" (:NAME row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              {:gen-fn as-lower-maps})]
      (is (map? row))
      (is (= 3 (:id row)))
      (is (= "Peach" (:name row))))))
