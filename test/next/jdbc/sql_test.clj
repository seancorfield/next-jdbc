;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.sql-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc.quoted :refer [mysql sql-server]]
            [next.jdbc.sql :as sql]))

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
    (is (= (#'sql/for-query :user {:id 9} {:table-fn sql-server :column-fn mysql})
           ["SELECT * FROM [user] WHERE `id` = ?" 9]))
    (is (= (#'sql/for-query :user {:id nil} {:table-fn sql-server :column-fn mysql})
           ["SELECT * FROM [user] WHERE `id` IS NULL"])))
  (testing "by where clause"
    (is (= (#'sql/for-query :user ["id = ? and opt is null" 9] {:table-fn sql-server :column-fn mysql})
           ["SELECT * FROM [user] WHERE id = ? and opt is null" 9]))))

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
