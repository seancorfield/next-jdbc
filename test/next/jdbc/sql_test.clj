;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.sql-test
  "Tests for the syntactic sugar SQL functions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.specs :as specs]
            [next.jdbc.sql :as sql]
            [next.jdbc.test-fixtures
             :refer [with-test-db ds column default-options
                      derby? jtds? maria? mssql? mysql? postgres? sqlite?]]
            [next.jdbc.types :refer [as-other as-real as-varchar]]))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest test-query
  (let [ds-opts (jdbc/with-options (ds) (default-options))
        rs      (sql/query ds-opts ["select * from fruit order by id"])]
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
                  :columns [:ID
                            ["CASE WHEN grade > 91 THEN 'ok ' ELSE 'bad' END"
                             :QUALITY]]
                  :order-by [:id]))]
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

(deftest test-get-by-id
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (is (nil? (sql/get-by-id ds-opts :fruit -1)))
    (let [row (sql/get-by-id ds-opts :fruit 3)]
      (is (map? row))
      (is (= "Peach" ((column :FRUIT/NAME) row))))
    (let [row (sql/get-by-id ds-opts :fruit "juicy" :appearance {})]
      (is (map? row))
      (is (= 4 ((column :FRUIT/ID) row)))
      (is (= "Orange" ((column :FRUIT/NAME) row))))
    (let [row (sql/get-by-id ds-opts :fruit "Banana" :FRUIT/NAME {})]
      (is (map? row))
      (is (= 2 ((column :FRUIT/ID) row))))))

(deftest test-update!
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (try
      (is (= {:next.jdbc/update-count 1}
             (sql/update! ds-opts :fruit {:appearance "brown"} {:id 2})))
      (is (= "brown" ((column :FRUIT/APPEARANCE)
                      (sql/get-by-id ds-opts :fruit 2))))
      (finally
        (sql/update! ds-opts :fruit {:appearance "yellow"} {:id 2})))
    (try
      (is (= {:next.jdbc/update-count 1}
             (sql/update! ds-opts :fruit {:appearance "green"}
                          ["name = ?" "Banana"])))
      (is (= "green" ((column :FRUIT/APPEARANCE)
                      (sql/get-by-id ds-opts :fruit 2))))
      (finally
        (sql/update! ds-opts :fruit {:appearance "yellow"} {:id 2})))))

(deftest test-insert-delete
  (let [new-key (cond (derby?)    :1
                      (jtds?)     :ID
                      (maria?)    :insert_id
                      (mssql?)    :GENERATED_KEYS
                      (mysql?)    :GENERATED_KEY
                      (postgres?) :fruit/id
                      (sqlite?)   (keyword "last_insert_rowid()")
                      :else       :FRUIT/ID)]
    (testing "single insert/delete"
      (is (== 5 (new-key (sql/insert! (ds) :fruit
                                      {:name (as-varchar "Kiwi")
                                       :appearance "green & fuzzy"
                                       :cost 100 :grade (as-real 99.9)}))))
      (is (= 5 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= {:next.jdbc/update-count 1}
             (sql/delete! (ds) :fruit {:id 5})))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "multiple insert/delete"
      (is (= (cond (derby?)
                   [nil] ; WTF Apache Derby?
                   (mssql?)
                   [8M]
                   (sqlite?)
                   [8]
                   :else
                   [6 7 8])
             (mapv new-key
                   (sql/insert-multi! (ds) :fruit
                                      [:name :appearance :cost :grade]
                                      [["Kiwi" "green & fuzzy" 100 99.9]
                                       ["Grape" "black" 10 50]
                                       ["Lemon" "yellow" 20 9.9]]))))
      (is (= 7 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= {:next.jdbc/update-count 1}
             (sql/delete! (ds) :fruit {:id 6})))
      (is (= 6 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= {:next.jdbc/update-count 2}
             (sql/delete! (ds) :fruit ["id > ?" 4])))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "multiple insert/delete with sequential cols/rows" ; per #43
      (is (= (cond (derby?)
                   [nil] ; WTF Apache Derby?
                   (mssql?)
                   [11M]
                   (sqlite?)
                   [11]
                   :else
                   [9 10 11])
             (mapv new-key
                   (sql/insert-multi! (ds) :fruit
                                      '(:name :appearance :cost :grade)
                                      '(("Kiwi" "green & fuzzy" 100 99.9)
                                        ("Grape" "black" 10 50)
                                        ("Lemon" "yellow" 20 9.9))))))
      (is (= 7 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= {:next.jdbc/update-count 1}
             (sql/delete! (ds) :fruit {:id 9})))
      (is (= 6 (count (sql/query (ds) ["select * from fruit"]))))
      (is (= {:next.jdbc/update-count 2}
             (sql/delete! (ds) :fruit ["id > ?" 4])))
      (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
    (testing "empty insert-multi!" ; per #44
      (is (= [] (sql/insert-multi! (ds) :fruit
                                   [:name :appearance :cost :grade]
                                   []))))))

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

(deftest no-empty-order-by
  (is (thrown? clojure.lang.ExceptionInfo
               (sql/find-by-keys (ds) :fruit
                                 {:name "Apple"}
                                 {:order-by []}))))

(deftest array-in
  (when (postgres?)
    (let [data (sql/find-by-keys (ds) :fruit ["id = any(?)" (int-array [1 2 3 4])])]
      (is (= 4 (count data))))))

(deftest enum-pg
  (when (postgres?)
    (let [r (sql/insert! (ds) :lang-test {:lang (as-other "fr")}
                         jdbc/snake-kebab-opts)]
      (is (= {:lang-test/lang "fr"} r)))))
