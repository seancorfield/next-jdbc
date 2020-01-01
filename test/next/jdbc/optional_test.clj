;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.optional-test
  "Test namespace for the optional builder functions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.optional :as opt]
            [next.jdbc.protocols :as p]
            [next.jdbc.test-fixtures :refer [with-test-db ds column
                                              default-options]])
  (:import (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(deftest test-map-row-builder
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 1]
                              (assoc (default-options)
                                     :builder-fn opt/as-maps))]
      (is (map? row))
      (is (not (contains? row (column :FRUIT/GRADE))))
      (is (= 1 ((column :FRUIT/ID) row)))
      (is (= "Apple" ((column :FRUIT/NAME) row)))))
  (testing "unqualified row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 2]
                              {:builder-fn opt/as-unqualified-maps})]
      (is (map? row))
      (is (not (contains? row (column :COST))))
      (is (= 2 ((column :ID) row)))
      (is (= "Banana" ((column :NAME) row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc (default-options)
                                     :builder-fn opt/as-lower-maps))]
      (is (map? row))
      (is (not (contains? row :fruit/appearance)))
      (is (= 3 (:fruit/id row)))
      (is (= "Peach" (:fruit/name row)))))
  (testing "unqualified lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 4]
                              {:builder-fn opt/as-unqualified-lower-maps})]
      (is (map? row))
      (is (= 4 (:id row)))
      (is (= "Orange" (:name row)))))
  (testing "custom row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc (default-options)
                                     :builder-fn opt/as-modified-maps
                                     :label-fn str/lower-case
                                     :qualifier-fn identity))]
      (is (map? row))
      (is (not (contains? row (column :FRUIT/appearance))))
      (is (= 3 ((column :FRUIT/id) row)))
      (is (= "Peach" ((column :FRUIT/name) row))))))

(defn- default-column-reader
  [^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (.getObject rs i))

(deftest test-map-row-adapter
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 1]
                              (assoc (default-options)
                                     :builder-fn (opt/as-maps-adapter
                                                  opt/as-maps
                                                  default-column-reader)))]
      (is (map? row))
      (is (not (contains? row (column :FRUIT/GRADE))))
      (is (= 1 ((column :FRUIT/ID) row)))
      (is (= "Apple" ((column :FRUIT/NAME) row)))))
  (testing "unqualified row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 2]
                              {:builder-fn (opt/as-maps-adapter
                                            opt/as-unqualified-maps
                                            default-column-reader)})]
      (is (map? row))
      (is (not (contains? row (column :COST))))
      (is (= 2 ((column :ID) row)))
      (is (= "Banana" ((column :NAME) row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc (default-options)
                                     :builder-fn (opt/as-maps-adapter
                                                  opt/as-lower-maps
                                                  default-column-reader)))]
      (is (map? row))
      (is (not (contains? row :fruit/appearance)))
      (is (= 3 (:fruit/id row)))
      (is (= "Peach" (:fruit/name row)))))
  (testing "unqualified lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 4]
                              {:builder-fn (opt/as-maps-adapter
                                            opt/as-unqualified-lower-maps
                                            default-column-reader)})]
      (is (map? row))
      (is (= 4 (:id row)))
      (is (= "Orange" (:name row)))))
  (testing "custom row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc (default-options)
                                     :builder-fn (opt/as-maps-adapter
                                                  opt/as-modified-maps
                                                  default-column-reader)
                                     :label-fn str/lower-case
                                     :qualifier-fn identity))]
      (is (map? row))
      (is (not (contains? row (column :FRUIT/appearance))))
      (is (= 3 ((column :FRUIT/id) row)))
      (is (= "Peach" ((column :FRUIT/name) row))))))
