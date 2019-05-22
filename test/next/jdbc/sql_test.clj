;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.sql-test
  "Tests for the (private) SQL string building functions in next.jdbc.sql.

  At some future date, tests for the syntactic sugar functions will be added."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.quoted :refer [mysql sql-server]]
            [next.jdbc.specs :as specs]
            [next.jdbc.sql :as sql]
            [next.jdbc.test-fixtures
             :refer [with-test-db ds derby? sqlite?]]))

(use-fixtures :once with-test-db)

(specs/instrument)

(deftest test-by-keys
  (testing ":where clause"
    (is (= (#'sql/by-keys {:a nil :b 42 :c "s"} :where {})
           ["WHERE a IS NULL AND b = ? AND c = ?" 42 "s"])))
  (testing ":set clause"
    (is (= (#'sql/by-keys {:a nil :b 42 :c "s"} :set {})
           ["SET a = ?, b = ?, c = ?" nil 42 "s"]))))

(deftest test-as-keys
  (is (= (#'sql/as-keys {:a nil :b 42 :c "s"} {})
         "a, b, c")))

(deftest test-as-?
  (is (= (#'sql/as-? {:a nil :b 42 :c "s"} {})
         "?, ?, ?")))

(deftest test-for-query
  (testing "by example"
    (is (= (#'sql/for-query :user {:id 9}
             {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]})
           ["SELECT * FROM [user] WHERE `id` = ? ORDER BY `a`, `b` DESC" 9]))
    (is (= (#'sql/for-query :user {:id nil} {:table-fn sql-server :column-fn mysql})
           ["SELECT * FROM [user] WHERE `id` IS NULL"])))
  (testing "by where clause"
    (is (= (#'sql/for-query :user ["id = ? and opt is null" 9]
             {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]})
           [(str  "SELECT * FROM [user] WHERE id = ? and opt is null"
                  " ORDER BY `a`, `b` DESC") 9]))))

(deftest test-for-delete
  (testing "by example"
    (is (= (#'sql/for-delete :user {:opt nil :id 9} {:table-fn sql-server :column-fn mysql})
           ["DELETE FROM [user] WHERE `opt` IS NULL AND `id` = ?" 9])))
  (testing "by where clause"
    (is (= (#'sql/for-delete :user ["id = ? and opt is null" 9] {:table-fn sql-server :column-fn mysql})
           ["DELETE FROM [user] WHERE id = ? and opt is null" 9]))))

(deftest test-for-update
  (testing "empty example (SQL error)"
    (is (= (#'sql/for-update :user {:status 42} {} {:table-fn sql-server :column-fn mysql})
           ["UPDATE [user] SET `status` = ? WHERE " 42])))
  (testing "by example"
    (is (= (#'sql/for-update :user {:status 42} {:id 9} {:table-fn sql-server :column-fn mysql})
           ["UPDATE [user] SET `status` = ? WHERE `id` = ?" 42 9])))
  (testing "by where clause, with nil set value"
    (is (= (#'sql/for-update :user {:status 42, :opt nil} ["id = ?" 9] {:table-fn sql-server :column-fn mysql})
           ["UPDATE [user] SET `status` = ?, `opt` = ? WHERE id = ?" 42 nil 9]))))

(deftest test-for-inserts
  (testing "single insert"
    (is (= (#'sql/for-insert :user {:id 9 :status 42 :opt nil} {:table-fn sql-server :column-fn mysql})
           ["INSERT INTO [user] (`id`, `status`, `opt`) VALUES (?, ?, ?)" 9 42 nil])))
  (testing "multi-row insert"
    (is (= (#'sql/for-insert-multi :user [:id :status]
                             [[42 "hello"]
                              [35 "world"]
                              [64 "dollars"]]
                             {:table-fn sql-server :column-fn mysql})
           ["INSERT INTO [user] (`id`, `status`) VALUES (?, ?), (?, ?), (?, ?)" 42 "hello" 35 "world" 64 "dollars"]))))

(deftest test-query
  (let [rs (sql/query (ds) ["select * from fruit order by id"])]
    (is (= 4 (count rs)))
    (is (every? map? rs))
    (is (every? meta rs))
    (is (= 1 (:FRUIT/ID (first rs))))
    (is (= 4 (:FRUIT/ID (last rs))))))

(deftest test-find-by-keys
  (let [rs (sql/find-by-keys (ds) :fruit {:appearance "yellow"})]
    (is (= 1 (count rs)))
    (is (every? map? rs))
    (is (every? meta rs))
    (is (= 2 (:FRUIT/ID (first rs))))))

(deftest test-get-by-id
  (let [row (sql/get-by-id (ds) :fruit 3)]
    (is (map? row))
    (is (= "Peach" (:FRUIT/NAME row))))
  (let [row (sql/get-by-id (ds) :fruit "juicy" :appearance {})]
    (is (map? row))
    (is (= 4 (:FRUIT/ID row)))
    (is (= "Orange" (:FRUIT/NAME row))))
  (let [row (sql/get-by-id (ds) :fruit "Banana" :FRUIT/NAME {})]
    (is (map? row))
    (is (= 2 (:FRUIT/ID row)))))

(deftest test-update!
  (try
    (is (= {:next.jdbc/update-count 1}
           (sql/update! (ds) :fruit {:appearance "brown"} {:id 2})))
    (is (= "brown" (:FRUIT/APPEARANCE
                    (sql/get-by-id (ds) :fruit 2))))
    (finally
      (sql/update! (ds) :fruit {:appearance "yellow"} {:id 2})))
  (try
    (is (= {:next.jdbc/update-count 1}
           (sql/update! (ds) :fruit {:appearance "green"}
                        ["name = ?" "Banana"])))
    (is (= "green" (:FRUIT/APPEARANCE
                    (sql/get-by-id (ds) :fruit 2))))
    (finally
      (sql/update! (ds) :fruit {:appearance "yellow"} {:id 2}))))

(deftest test-insert-delete
  (testing "single insert/delete"
    (is (= (cond (derby?)
                 {:1 5M}
                 (sqlite?)
                 {(keyword "last_insert_rowid()") 5}
                 :else
                 {:FRUIT/ID 5})
           (sql/insert! (ds) :fruit
                        {:name "Kiwi" :appearance "green & fuzzy"
                         :cost 100 :grade 99.9})))
    (is (= 5 (count (sql/query (ds) ["select * from fruit"]))))
    (is (= {:next.jdbc/update-count 1}
           (sql/delete! (ds) :fruit {:id 5})))
    (is (= 4 (count (sql/query (ds) ["select * from fruit"])))))
  (testing "multiple insert/delete"
    (is (= (cond (derby?)
                 [{:1 nil}] ; WTF Apache Derby?
                 (sqlite?)
                 [{(keyword "last_insert_rowid()") 8}]
                 :else
                 [{:FRUIT/ID 6} {:FRUIT/ID 7} {:FRUIT/ID 8}])
           (sql/insert-multi! (ds) :fruit
                              [:name :appearance :cost :grade]
                              [["Kiwi" "green & fuzzy" 100 99.9]
                               ["Grape" "black" 10 50]
                               ["Lemon" "yellow" 20 9.9]])))
    (is (= 7 (count (sql/query (ds) ["select * from fruit"]))))
    (is (= {:next.jdbc/update-count 1}
           (sql/delete! (ds) :fruit {:id 6})))
    (is (= 6 (count (sql/query (ds) ["select * from fruit"]))))
    (is (= {:next.jdbc/update-count 2}
           (sql/delete! (ds) :fruit ["id > ?" 4])))
    (is (= 4 (count (sql/query (ds) ["select * from fruit"]))))))
