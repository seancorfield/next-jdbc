;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set-test
  "Test namespace for the result set functions.

  What's left to be tested:
  * ReadableColumn protocol extension point"
  (:require [clojure.core.protocols :as core-p]
            [clojure.datafy :as d]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs]
            [next.jdbc.test-fixtures :refer [with-test-db ds column
                                              default-options
                                              mssql? mysql? postgres?]])
  (:import (java.sql ResultSet ResultSetMetaData)))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest test-datafy-nav
  (testing "default schema"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:TABLE/FRUIT_ID 1} connectable
                                      (default-options))
          data (d/datafy test-row)
          v (get data :TABLE/FRUIT_ID)]
      ;; check datafication is sane
      (is (= 1 v))
      (let [object (d/nav data :table/fruit_id v)]
        ;; check nav produces a single map with the expected key/value data
        (is (= 1 ((column :FRUIT/ID) object)))
        (is (= "Apple" ((column :FRUIT/NAME) object))))))
  (testing "custom schema *-to-1"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:foo/bar 2} connectable
                                      (assoc (default-options)
                                             :schema {:foo/bar :fruit/id}))
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 2 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a single map with the expected key/value data
        (is (= 2 ((column :FRUIT/ID) object)))
        (is (= "Banana" ((column :FRUIT/NAME) object))))))
  (testing "custom schema *-to-many"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:foo/bar 3} connectable
                                      (assoc (default-options)
                                             :schema {:foo/bar [:fruit/id]}))
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 3 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a result set with the expected key/value data
        (is (vector? object))
        (is (= 3 ((column :FRUIT/ID) (first object))))
        (is (= "Peach" ((column :FRUIT/NAME) (first object)))))))
  (testing "legacy schema tuples"
    (let [connectable (ds)
          test-row (rs/datafiable-row {:foo/bar 2} connectable
                                      (assoc (default-options)
                                             :schema {:foo/bar [:fruit :id]}))
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 2 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a single map with the expected key/value data
        (is (= 2 ((column :FRUIT/ID) object)))
        (is (= "Banana" ((column :FRUIT/NAME) object)))))
    (let [connectable (ds)
          test-row (rs/datafiable-row {:foo/bar 3} connectable
                                      (assoc (default-options)
                                             :schema {:foo/bar [:fruit :id :many]}))
          data (d/datafy test-row)
          v (get data :foo/bar)]
      ;; check datafication is sane
      (is (= 3 v))
      (let [object (d/nav data :foo/bar v)]
        ;; check nav produces a result set with the expected key/value data
        (is (vector? object))
        (is (= 3 ((column :FRUIT/ID) (first object))))
        (is (= "Peach" ((column :FRUIT/NAME) (first object))))))))

(deftest test-map-row-builder
  (testing "default row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 1]
                              (default-options))]
      (is (map? row))
      (is (contains? row (column :FRUIT/GRADE)))
      (is (nil? ((column :FRUIT/GRADE) row)))
      (is (= 1 ((column :FRUIT/ID) row)))
      (is (= "Apple" ((column :FRUIT/NAME) row))))
    (let [rs (p/-execute-all (ds)
                             ["select * from fruit order by id"]
                             (default-options))]
      (is (every? map? rs))
      (is (= 1 ((column :FRUIT/ID) (first rs))))
      (is (= "Apple" ((column :FRUIT/NAME) (first rs))))
      (is (= 4 ((column :FRUIT/ID) (last rs))))
      (is (= "Orange" ((column :FRUIT/NAME) (last rs))))))
  (testing "unqualified row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 2]
                              {:builder-fn rs/as-unqualified-maps})]
      (is (map? row))
      (is (contains? row (column :COST)))
      (is (nil? ((column :COST) row)))
      (is (= 2 ((column :ID) row)))
      (is (= "Banana" ((column :NAME) row)))))
  (testing "lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc (default-options)
                                     :builder-fn rs/as-lower-maps))]
      (is (map? row))
      (is (contains? row :fruit/appearance))
      (is (nil? (:fruit/appearance row)))
      (is (= 3 (:fruit/id row)))
      (is (= "Peach" (:fruit/name row)))))
  (testing "unqualified lower-case row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 4]
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (is (map? row))
      (is (= 4 (:id row)))
      (is (= "Orange" (:name row)))))
  (testing "custom row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc (default-options)
                                     :builder-fn rs/as-modified-maps
                                     :label-fn str/lower-case
                                     :qualifier-fn identity))]
      (is (map? row))
      (is (contains? row (column :FRUIT/appearance)))
      (is (nil? ((column :FRUIT/appearance) row)))
      (is (= 3 ((column :FRUIT/id) row)))
      (is (= "Peach" ((column :FRUIT/name) row)))))
  (testing "adapted row builder"
    (let [row (p/-execute-one (ds)
                              ["select * from fruit where id = ?" 3]
                              (assoc
                               (default-options)
                               :builder-fn (rs/as-maps-adapter
                                            rs/as-modified-maps
                                            (fn [^ResultSet rs
                                                 ^ResultSetMetaData rsmeta
                                                 ^Integer i]
                                              (condp = (.getColumnType rsmeta i)
                                                     java.sql.Types/VARCHAR
                                                     (.getString rs i)
                                                     java.sql.Types/INTEGER
                                                     (.getLong rs i)
                                                     (.getObject rs i))))
                               :label-fn str/lower-case
                               :qualifier-fn identity))]
      (is (map? row))
      (is (contains? row (column :FRUIT/appearance)))
      (is (nil? ((column :FRUIT/appearance) row)))
      (is (= 3 ((column :FRUIT/id) row)))
      (is (= "Peach" ((column :FRUIT/name) row))))))

(deftest test-mapify
  (testing "no row builder is used"
    (is (= [true]
           (into [] (map map?) ; it looks like a real map now
                 (p/-execute (ds) ["select * from fruit where id = ?" 1]
                             {:builder-fn (constantly nil)}))))
    (is (= ["Apple"]
           (into [] (map :name) ; keyword selection works
                 (p/-execute (ds) ["select * from fruit where id = ?" 1]
                             {:builder-fn (constantly nil)}))))
    (is (= [[2 [:name "Banana"]]]
           (into [] (map (juxt #(get % "id") ; get by string key works
                               #(find % :name))) ; get MapEntry works
                 (p/-execute (ds) ["select * from fruit where id = ?" 2]
                             {:builder-fn (constantly nil)}))))
    (is (= [{:id 3 :name "Peach"}]
           (into [] (map #(select-keys % [:id :name])) ; select-keys works
                 (p/-execute (ds) ["select * from fruit where id = ?" 3]
                             {:builder-fn (constantly nil)}))))
    (is (= [[:orange 4]]
           (into [] (map #(vector (if (contains? % :name) ; contains works
                                    (keyword (str/lower-case (:name %)))
                                    :unnamed)
                                  (get % :id 0))) ; get with not-found works
                 (p/-execute (ds) ["select * from fruit where id = ?" 4]
                             {:builder-fn (constantly nil)}))))
    (is (= [{}]
           (into [] (map empty) ; return empty map without building
                 (p/-execute (ds) ["select * from fruit where id = ?" 1]
                             {:builder-fn (constantly nil)})))))
  (testing "count does not build a map"
    (let [count-builder (fn [_1 _2]
                          (reify rs/RowBuilder
                            (column-count [_] 13)))]
      (is (= [13]
             (into [] (map count) ; count relies on columns, not row fields
                   (p/-execute (ds) ["select * from fruit where id = ?" 1]
                               {:builder-fn count-builder}))))))
  (testing "assoc, dissoc, cons, seq, and = build maps"
    (is (map? (reduce (fn [_ row] (reduced (assoc row :x 1)))
                      nil
                      (p/-execute (ds) ["select * from fruit"] {}))))
    (is (= 6 (count (reduce (fn [_ row] (reduced (assoc row :x 1)))
                            nil
                            (p/-execute (ds) ["select * from fruit"] {})))))
    (is (map? (reduce (fn [_ row] (reduced
                                   (dissoc row (column :FRUIT/NAME))))
                      nil
                      (p/-execute (ds) ["select * from fruit"]
                                  (default-options)))))
    (is (= 4 (count (reduce (fn [_ row] (reduced
                                         (dissoc row (column :FRUIT/NAME))))
                            nil
                            (p/-execute (ds) ["select * from fruit"]
                                        (default-options))))))
    (is (seq? (reduce (fn [_ row] (reduced (seq row)))
                      nil
                      (p/-execute (ds) ["select * from fruit"] {}))))
    (is (seq? (reduce (fn [_ row] (reduced (cons :seq row)))
                      nil
                      (p/-execute (ds) ["select * from fruit"] {}))))
    (is (= :seq (first (reduce (fn [_ row] (reduced (cons :seq row)))
                               nil
                               (p/-execute (ds) ["select * from fruit"] {})))))
    (is (false? (reduce (fn [_ row] (reduced (= row {})))
                        nil
                        (p/-execute (ds) ["select * from fruit"] {}))))
    (is (map-entry? (second (reduce (fn [_ row] (reduced (cons :seq row)))
                                    nil
                                    (p/-execute (ds) ["select * from fruit"] {})))))
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
                            {:builder-fn fruit-builder})]
    (is (instance? Fruit row))
    (is (= 1 (:id row))))
  (let [rs (p/-execute-all (ds)
                           ["select * from fruit where appearance = ?" "red"]
                           {:builder-fn fruit-builder})]
    (is (every? #(instance? Fruit %) rs))
    (is (= 1 (count rs)))
    (is (= 1 (:id (first rs))))))

(deftest metadata-result-set
  (let [metadata (with-open [con (p/get-connection (ds) {})]
                   (-> (.getMetaData con)
                       (.getTables nil nil nil (into-array ["TABLE" "VIEW"]))
                       (rs/datafiable-result-set (ds) {})))]
    (is (vector? metadata))
    (is (map? (first metadata)))
    ;; we should find :something/table_name with a value of "fruit"
    ;; may be upper/lower-case, could have any qualifier
    (is (some (fn [row]
                (some #(and (= "table_name" (-> % key name str/lower-case))
                            (= "fruit" (-> % val name str/lower-case)))
                      row))
              metadata))))

(deftest clob-reading
  (when-not (or (mssql?) (mysql?) (postgres?)) ; no clob in these
    (with-open [con (p/get-connection (ds) {})]
      (try
        (p/-execute-one con ["DROP TABLE CLOBBER"] {})
        (catch Exception _))
      (p/-execute-one con [(str "
CREATE TABLE CLOBBER (
  ID INTEGER,
  STUFF CLOB
)")]
                      {})
      (p/-execute-one con
                      [(str "insert into clobber (id, stuff)"
                            "values (?,?), (?,?)")
                       1 "This is some long string"
                       2 "This is another long string"]
                      {})
      (is (= "This is some long string"
             (-> (p/-execute-all con
                                 ["select * from clobber where id = ?" 1]
                                 {:builder-fn (rs/as-maps-adapter
                                               rs/as-unqualified-lower-maps
                                               rs/clob-column-reader)})
                 (first)
                 :stuff))))))
