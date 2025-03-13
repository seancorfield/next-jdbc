;; copyright (c) 2019-2025 Sean Corfield, all rights reserved

(ns next.jdbc.optional-test
  "Test namespace for the optional builder functions."
  (:require [clojure.string :as str]
            [lazytest.core :refer [around set-ns-context!]]
            [lazytest.experimental.interfaces.clojure-test :refer [deftest is testing]]
            [next.jdbc.optional :as opt]
            [next.jdbc.protocols :as p]
            [next.jdbc.test-fixtures :refer [col-kw column default-options ds index
                                             with-test-db]])
  (:import
   (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

(set-ns-context! [(around [f] (with-test-db f))])

(deftest test-map-row-builder
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 1]
                              (assoc (default-options)
                                     :builder-fn opt/as-maps))]
      (is (map? row))
      (is (not (contains? row (column :FRUIT/GRADE))))
      (is (= 1 ((column :FRUIT/ID) row)))
      (is (= "Apple" ((column :FRUIT/NAME) row)))))
  (testing "unqualified row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 2]
                              {:builder-fn opt/as-unqualified-maps})]
      (is (map? row))
      (is (not (contains? row (column :COST))))
      (is (= 2 ((column :ID) row)))
      (is (= "Banana" ((column :NAME) row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 3]
                              (assoc (default-options)
                                     :builder-fn opt/as-lower-maps))]
      (is (map? row))
      (is (not (contains? row (col-kw :fruit/appearance))))
      (is (= 3 ((col-kw :fruit/id) row)))
      (is (= "Peach" ((col-kw :fruit/name) row)))))
  (testing "unqualified lower-case row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 4]
                              {:builder-fn opt/as-unqualified-lower-maps})]
      (is (map? row))
      (is (= 4 ((col-kw :id) row)))
      (is (= "Orange" ((col-kw :name) row)))))
  (testing "custom row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 3]
                              (assoc (default-options)
                                     :builder-fn opt/as-modified-maps
                                     :label-fn str/lower-case
                                     :qualifier-fn identity))]
      (is (map? row))
      (is (not (contains? row (column :FRUIT/appearance))))
      (is (= 3 ((column :FRUIT/id) row)))
      (is (= "Peach" ((column :FRUIT/name) row))))))

(defn- default-column-reader
  [^ResultSet rs ^ResultSetMetaData _ ^Integer i]
  (.getObject rs i))

(deftest test-map-row-adapter
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 1]
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
                              [(str "select * from fruit where " (index) " = ?") 2]
                              {:builder-fn (opt/as-maps-adapter
                                            opt/as-unqualified-maps
                                            default-column-reader)})]
      (is (map? row))
      (is (not (contains? row (column :COST))))
      (is (= 2 ((column :ID) row)))
      (is (= "Banana" ((column :NAME) row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 3]
                              (assoc (default-options)
                                     :builder-fn (opt/as-maps-adapter
                                                  opt/as-lower-maps
                                                  default-column-reader)))]
      (is (map? row))
      (is (not (contains? row (col-kw :fruit/appearance))))
      (is (= 3 ((col-kw :fruit/id) row)))
      (is (= "Peach" ((col-kw :fruit/name) row)))))
  (testing "unqualified lower-case row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 4]
                              {:builder-fn (opt/as-maps-adapter
                                            opt/as-unqualified-lower-maps
                                            default-column-reader)})]
      (is (map? row))
      (is (= 4 ((col-kw :id) row)))
      (is (= "Orange" ((col-kw :name) row)))))
  (testing "custom row builder"
    (let [row (p/-execute-one (ds)
                              [(str "select * from fruit where " (index) " = ?") 3]
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
