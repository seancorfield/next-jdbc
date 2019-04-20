;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set-test
  "Stub test namespace for the result set functions.

  What's left to be tested:
  * ReadableColumn protocol extension point"
  (:require [clojure.core.protocols :as core-p]
            [clojure.datafy :as d]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.test-fixtures :refer [with-test-db ds]])
  (:import (java.sql ResultSet ResultSetMetaData)))

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

(defn lower-case-cols [^ResultSetMetaData rsmeta opts]
  (mapv (fn [^Integer i]
          (keyword (str/lower-case (.getColumnLabel rsmeta i))))
        (range 1 (inc (.getColumnCount rsmeta)))))

(defn as-lower-case [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols   (lower-case-cols rsmeta opts)]
    (rs/->MapResultSetBuilder rs rsmeta cols)))

(deftest test-map-row-builder
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 1]
                              {})]
      (is (map? row))
      (is (= 1 (:FRUIT/ID row)))
      (is (= "Apple" (:FRUIT/NAME row))))
    (let [rs (p/-execute-all (ds)
                             ["select * from fruit order by id"]
                             {})]
      (is (every? map? rs))
      (is (= 1 (:FRUIT/ID (first rs))))
      (is (= "Apple" (:FRUIT/NAME (first rs))))
      (is (= 4 (:FRUIT/ID (last rs))))
      (is (= "Orange" (:FRUIT/NAME (last rs))))))
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
                              {:gen-fn as-lower-case})]
      (is (map? row))
      (is (= 3 (:id row)))
      (is (= "Peach" (:name row))))))

(deftest test-mapify
  (testing "no row builder is used"
    (is (= [false]
           (into [] (map map?) ; it is not a real map
                 (p/-execute (ds) ["select * from fruit where id = ?" 1]
                             {:gen-fn (constantly nil)}))))
    (is (= ["Apple"]
           (into [] (map :name) ; but keyword selection works
                 (p/-execute (ds) ["select * from fruit where id = ?" 1]
                             {:gen-fn (constantly nil)}))))
    (is (= [[2 [:name "Banana"]]]
           (into [] (map (juxt #(get % "id") ; get by string key works
                               #(find % :name))) ; get MapEntry works
                 (p/-execute (ds) ["select * from fruit where id = ?" 2]
                             {:gen-fn (constantly nil)}))))
    (is (= [{:id 3 :name "Peach"}]
           (into [] (map #(select-keys % [:id :name])) ; select-keys works
                 (p/-execute (ds) ["select * from fruit where id = ?" 3]
                             {:gen-fn (constantly nil)}))))
    (is (= [[:orange 4]]
           (into [] (map #(vector (if (contains? % :name) ; contains works
                                    (keyword (str/lower-case (:name %)))
                                    :unnamed)
                                  (get % :id 0))) ; get with not-found works
                 (p/-execute (ds) ["select * from fruit where id = ?" 4]
                             {:gen-fn (constantly nil)})))))
  (testing "assoc and seq build maps"
    (is (map? (reduce (fn [_ row] (reduced (assoc row :x 1)))
                      nil
                      (p/-execute (ds) ["select * from fruit"] {}))))
    (is (seq? (reduce (fn [_ row] (reduced (seq row)))
                      nil
                      (p/-execute (ds) ["select * from fruit"] {}))))
    (is (every? map-entry? (reduce (fn [_ row] (reduced (seq row)))
                                   nil
                                   (p/-execute (ds) ["select * from fruit"] {})))))
  (testing "datafiable-row builds map; with metadata"
    (is (map? (reduce (fn [_ row] (reduced (rs/datafiable-row row (ds) {})))
                      nil
                      (p/-execute (ds) ["select * from fruit"] {}))))
    (is (contains? (meta (reduce (fn [_ row] (reduced (rs/datafiable-row row (ds) {})))
                                 nil
                                 (p/-execute (ds) ["select * from fruit"] {})))
                   `core-p/datafy))))

;; test that we can create a record-based result set builder:

(defrecord Fruit [id name appearance cost grade])

(defn fruit-builder [^ResultSet rs opts]
  (reify
    rs/RowBuilder
    (->row [_] (->Fruit (.getObject rs "id")
                        (.getObject rs "name")
                        (.getObject rs "appearance")
                        (.getObject rs "cost")
                        (.getObject rs "grade")))
    (with-column [_ row i] row)
    (column-count [_] 0) ; no need to iterate over columns
    (row! [_ row] row)
    rs/ResultSetBuilder
    (->rs [_] (transient []))
    (with-row [_ rs row] (conj! rs row))
    (rs! [_ rs] (persistent! rs))))

(deftest custom-map-builder
  (let [row (p/-execute-one (ds)
                            ["select * from fruit where appearance = ?" "red"]
                            {:gen-fn fruit-builder})]
    (is (instance? Fruit row))
    (is (= 1 (:id row))))
  (let [rs (p/-execute-all (ds)
                           ["select * from fruit where appearance = ?" "red"]
                           {:gen-fn fruit-builder})]
    (is (every? #(instance? Fruit %) rs))
    (is (= 1 (count rs)))
    (is (= 1 (:id (first rs))))))
