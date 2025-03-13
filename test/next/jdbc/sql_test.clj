;; copyright (c) 2019-2025 Sean Corfield, all rights reserved

(ns next.jdbc.sql-test
  "Tests for the syntactic sugar SQL functions."
  (:require [lazytest.core :refer [around set-ns-context!]]
            [lazytest.experimental.interfaces.clojure-test :refer [deftest is testing thrown?]]
            [next.jdbc :as jdbc]
            [next.jdbc.specs :as specs]
            [next.jdbc.sql :as sql]
            [next.jdbc.test-fixtures
             :refer [col-kw column default-options derby? ds index jtds?
                     maria? mssql? mysql? postgres? sqlite? with-test-db xtdb?]]
            [next.jdbc.types :refer [as-other as-real as-varchar]]))

(set! *warn-on-reflection* true)

(set-ns-context! [(around [f] (with-test-db f))])

(specs/instrument)

(deftest test-query
  (let [ds-opts (jdbc/with-options (ds) (default-options))
        rs      (sql/query ds-opts [(str "select * from fruit order by " (index))])]
    (is (= 4 (count rs)))
    (is (every? map? rs))
    (is (every? meta rs))
    (is (= 1 ((column :FRUIT/ID) (first rs))))
    (is (= 4 ((column :FRUIT/ID) (last rs))))))

(deftest test-find-all-offset
  (let [ds-opts (jdbc/with-options (ds) (default-options))
        rs      (sql/find-by-keys
                 ds-opts :fruit :all
                 (assoc
                  (if (or (mysql?) (sqlite?))
                    {:limit  2 :offset 1}
                    {:offset 1 :fetch  2})
                  :columns [(col-kw :ID)
                            ["CASE WHEN grade > 91 THEN 'ok ' ELSE 'bad' END"
                             :QUALITY]]
                  :order-by [(col-kw :id)]))]
    (is (= 2 (count rs)))
    (is (every? map? rs))
    (is (every? meta rs))
    (is (every? #(= 2 (count %)) rs))
    (is (= 2 ((column :FRUIT/ID) (first rs))))
    (is (= "ok " ((column :QUALITY) (first rs))))
    (is (= 3 ((column :FRUIT/ID) (last rs))))
    (is (= "bad" ((column :QUALITY) (last rs))))))

(deftest test-find-by-keys
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (let [rs (sql/find-by-keys ds-opts :fruit {:appearance "neon-green"})]
      (is (vector? rs))
      (is (= [] rs)))
    (let [rs (sql/find-by-keys ds-opts :fruit {:appearance "yellow"})]
      (is (= 1 (count rs)))
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 2 ((column :FRUIT/ID) (first rs)))))))

(deftest test-aggregate-by-keys
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (let [count-v (sql/aggregate-by-keys ds-opts :fruit "count(*)" {:appearance "neon-green"})]
      (is (number? count-v))
      (is (= 0 count-v)))
    (let [count-v (sql/aggregate-by-keys ds-opts :fruit "count(*)" {:appearance "yellow"})]
      (is (= 1 count-v)))
    (let [count-v (sql/aggregate-by-keys ds-opts :fruit "count(*)" :all)]
      (is (= 4 count-v)))
    (let [max-id (sql/aggregate-by-keys ds-opts :fruit (str "max(" (index) ")") :all)]
      (is (= 4 max-id)))
    (when-not (xtdb?) ; XTDB does not support min/max on strings?
      (let [min-name (sql/aggregate-by-keys ds-opts :fruit "min(name)" :all)]
        (is (= "Apple" min-name))))
    (is (thrown? IllegalArgumentException
                 (sql/aggregate-by-keys ds-opts :fruit "count(*)" :all {:columns []})))))

(deftest test-get-by-id
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (is (nil? (sql/get-by-id ds-opts :fruit -1 (col-kw :id) {})))
    (let [row (sql/get-by-id ds-opts :fruit 3 (col-kw :id) {})]
      (is (map? row))
      (is (= "Peach" ((column :FRUIT/NAME) row))))
    (let [row (sql/get-by-id ds-opts :fruit "juicy" :appearance {})]
      (is (map? row))
      (is (= 4 ((column :FRUIT/ID) row)))
      (is (= "Orange" ((column :FRUIT/NAME) row))))
    (let [row (sql/get-by-id ds-opts :fruit "Banana" :FRUIT/NAME {})]
      (is (map? row))
      (is (= 2 ((column :FRUIT/ID) row))))))

(defn- update-count [n]
  (if (xtdb?)
    {:next.jdbc/update-count 0}
    {:next.jdbc/update-count n}))

(deftest test-update!
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (try
      (is (= (update-count 1)
             (sql/update! ds-opts :fruit {:appearance "brown"} {(col-kw :id) 2})))
      (is (= "brown" ((column :FRUIT/APPEARANCE)
                      (sql/get-by-id ds-opts :fruit 2 (col-kw :id) {}))))
      (finally
        (sql/update! ds-opts :fruit {:appearance "yellow"} {(col-kw :id) 2})))
    (try
      (is (= (update-count 1)
             (sql/update! ds-opts :fruit {:appearance "green"}
                          ["name = ?" "Banana"])))
      (is (= "green" ((column :FRUIT/APPEARANCE)
                      (sql/get-by-id ds-opts :fruit 2 (col-kw :id) {}))))
      (finally
        (sql/update! ds-opts :fruit {:appearance "yellow"} {(col-kw :id) 2})))))

(deftest test-insert-delete
  (let [new-key (cond (derby?)    :1
                      (jtds?)     :ID
                      (maria?)    :insert_id
                      (mssql?)    :GENERATED_KEYS
                      (mysql?)    :GENERATED_KEY
                      (postgres?) :fruit/id
                      ;; XTDB does not return the generated key so we fix it
                      ;; to be the one we insert here, and then fake it in all
                      ;; the other tests.
                      (xtdb?)     (constantly 5)
                      :else       :FRUIT/ID)]
    (testing "single insert/delete"
      (is (== 5 (new-key (sql/insert! (ds) :fruit
                                      (cond-> {:name (as-varchar "Kiwi")
                                               :appearance "green & fuzzy"
                                               :cost 100 :grade (as-real 99.9)}
                                        (xtdb?)
                                        (assoc :_id 5))
                                      {:suffix
                                       (when (sqlite?)
                                         "RETURNING *")}))))
      (is (= 5 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 1)
             (sql/delete! (ds) :fruit {(col-kw :id) 5})))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "multiple insert/delete"
      (is (= (cond (derby?)
                   [nil] ; WTF Apache Derby?
                   (mssql?)
                   [8M]
                   (maria?)
                   [6]
                   (xtdb?)
                   []
                   :else
                   [6 7 8])
             (mapv new-key
                   (sql/insert-multi! (ds) :fruit
                                      (cond->> [:name :appearance :cost :grade]
                                        (xtdb?) (cons :_id))
                                      (cond->> [["Kiwi" "green & fuzzy" 100 99.9]
                                                ["Grape" "black" 10 50]
                                                ["Lemon" "yellow" 20 9.9]]
                                        (xtdb?)
                                        (map cons [6 7 8]))
                                      {:suffix
                                       (when (sqlite?)
                                         "RETURNING *")}))))
      (is (= 7 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 1)
             (sql/delete! (ds) :fruit {(col-kw :id) 6})))
      (is (= 6 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 2)
             (sql/delete! (ds) :fruit [(str (index) " > ?") 4])))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "multiple insert/delete with sequential cols/rows" ; per #43
      (is (= (cond (derby?)
                   [nil] ; WTF Apache Derby?
                   (mssql?)
                   [11M]
                   (maria?)
                   [9]
                   (xtdb?)
                   []
                   :else
                   [9 10 11])
             (mapv new-key
                   (sql/insert-multi! (ds) :fruit
                                      (cond->> '(:name :appearance :cost :grade)
                                        (xtdb?) (cons :_id))
                                      (cond->> '(("Kiwi" "green & fuzzy" 100 99.9)
                                                 ("Grape" "black" 10 50)
                                                 ("Lemon" "yellow" 20 9.9))
                                        (xtdb?)
                                        (map cons [9 10 11]))
                                      {:suffix
                                       (when (sqlite?)
                                         "RETURNING *")}))))
      (is (= 7 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 1)
             (sql/delete! (ds) :fruit {(col-kw :id) 9})))
      (is (= 6 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 2)
             (sql/delete! (ds) :fruit [(str (index) " > ?") 4])))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "multiple insert/delete with maps"
      (is (= (cond (derby?)
                   [nil] ; WTF Apache Derby?
                   (mssql?)
                   [14M]
                   (maria?)
                   [12]
                   (xtdb?)
                   []
                   :else
                   [12 13 14])
             (mapv new-key
                   (sql/insert-multi! (ds) :fruit
                                      (cond->> [{:name       "Kiwi"
                                                 :appearance "green & fuzzy"
                                                 :cost       100
                                                 :grade      99.9}
                                                {:name       "Grape"
                                                 :appearance "black"
                                                 :cost       10
                                                 :grade      50}
                                                {:name       "Lemon"
                                                 :appearance "yellow"
                                                 :cost       20
                                                 :grade      9.9}]
                                        (xtdb?)
                                        (map #(assoc %2 :_id %1) [12 13 14]))
                                      {:suffix
                                       (when (sqlite?)
                                         "RETURNING *")}))))
      (is (= 7 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 1)
             (sql/delete! (ds) :fruit {(col-kw :id) 12})))
      (is (= 6 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= (update-count 2)
             (sql/delete! (ds) :fruit [(str (index) " > ?") 10])))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "empty insert-multi!" ; per #44 and #264
      (is (= [] (sql/insert-multi! (ds) :fruit
                                   [:name :appearance :cost :grade]
                                   []
                                   {:suffix
                                    (when (sqlite?)
                                      "RETURNING *")})))
      ;; per #264 the following should all be legal too:
      (is (= [] (sql/insert-multi! (ds) :fruit
                                   []
                                   {:suffix
                                    (when (sqlite?)
                                      "RETURNING *")})))
      (is (= [] (sql/insert-multi! (ds) :fruit
                                   []
                                   []
                                   {:suffix
                                    (when (sqlite?)
                                      "RETURNING *")})))
      (is (= [] (sql/insert-multi! (ds) :fruit [])))
      (is (= [] (sql/insert-multi! (ds) :fruit [] []))))))

(deftest no-empty-example-maps
  (is (thrown? clojure.lang.ExceptionInfo
               (sql/find-by-keys (ds) :fruit {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sql/update! (ds) :fruit {} {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sql/delete! (ds) :fruit {}))))

(deftest no-empty-columns
  (is (thrown? clojure.lang.ExceptionInfo
               (sql/insert-multi! (ds) :fruit [] [[] [] []]))))

(deftest no-mismatched-columns
  (is (thrown? IllegalArgumentException
               (sql/insert-multi! (ds) :fruit [{:name "Apple"} {:cost 1.23}]))))

(deftest no-empty-order-by
  (is (thrown? clojure.lang.ExceptionInfo
               (sql/find-by-keys (ds) :fruit
                                  {:name "Apple"}
                                  {:order-by []}))))

(deftest array-in
  (when (postgres?)
    (let [data (sql/find-by-keys (ds) :fruit [(str (index) " = any(?)") (int-array [1 2 3 4])])]
      (is (= 4 (count data))))))

(deftest enum-pg
  (when (postgres?)
    (let [r (sql/insert! (ds) :lang-test {:lang (as-other "fr")}
                         jdbc/snake-kebab-opts)]
      (is (= {:lang-test/lang "fr"} r)))))
