;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.sql.builder-test
  "Tests for the SQL string building functions in next.jdbc.sql.builder."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc.quoted :refer [mysql sql-server]]
            [next.jdbc.sql.builder :as builder]))

(set! *warn-on-reflection* true)

(deftest test-by-keys
  (testing ":where clause"
    (is (= (builder/by-keys {:a nil :b 42 :c "s"} :where {})
           ["WHERE a IS NULL AND b = ? AND c = ?" 42 "s"])))
  (testing ":set clause"
    (is (= (builder/by-keys {:a nil :b 42 :c "s"} :set {})
           ["SET a = ?, b = ?, c = ?" nil 42 "s"]))))

(deftest test-as-cols
  (is (= (builder/as-cols [:a :b :c] {})
         "a, b, c"))
  (is (= (builder/as-cols [[:a :aa] :b ["count(*)" :c]] {})
         "a AS aa, b, count(*) AS c"))
  (is (= (builder/as-cols [[:a :aa] :b ["count(*)" :c]] {:column-fn mysql})
         "`a` AS `aa`, `b`, count(*) AS `c`")))

(deftest test-as-keys
  (is (= (builder/as-keys {:a nil :b 42 :c "s"} {})
         "a, b, c")))

(deftest test-as-?
  (is (= (builder/as-? {:a nil :b 42 :c "s"} {})
         "?, ?, ?")))

(deftest test-for-query
  (testing "by example"
    (is (= (builder/for-query
            :user
            {:id 9}
            {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]})
           ["SELECT * FROM [user] WHERE `id` = ? ORDER BY `a`, `b` DESC" 9]))
    (is (= (builder/for-query :user {:id nil} {:table-fn sql-server :column-fn mysql})
           ["SELECT * FROM [user] WHERE `id` IS NULL"]))
    (is (= (builder/for-query :user
                              {:id nil}
                              {:table-fn sql-server :column-fn mysql
                               :suffix "FOR UPDATE"})
           ["SELECT * FROM [user] WHERE `id` IS NULL FOR UPDATE"])))
  (testing "by where clause"
    (is (= (builder/for-query
            :user
            ["id = ? and opt is null" 9]
            {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]})
           [(str "SELECT * FROM [user] WHERE id = ? and opt is null"
                 " ORDER BY `a`, `b` DESC") 9])))
  (testing "by :all"
    (is (= (builder/for-query
            :user
            :all
            {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]})
           ["SELECT * FROM [user] ORDER BY `a`, `b` DESC"])))
  (testing "top N"
    (is (= (builder/for-query
            :user
            {:id 9}
            {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]
             :top 42})
           ["SELECT TOP ? * FROM [user] WHERE `id` = ? ORDER BY `a`, `b` DESC"
            42 9])))
  (testing "limit"
    (testing "without offset"
      (is (= (builder/for-query
              :user
              {:id 9}
              {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]
               :limit 42})
             [(str "SELECT * FROM [user] WHERE `id` = ?"
                   " ORDER BY `a`, `b` DESC LIMIT ?")
              9 42])))
    (testing "with offset"
      (is (= (builder/for-query
              :user
              {:id 9}
              {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]
               :limit 42 :offset 13})
             [(str "SELECT * FROM [user] WHERE `id` = ?"
                   " ORDER BY `a`, `b` DESC LIMIT ? OFFSET ?")
              9 42 13]))))
  (testing "offset"
    (testing "without fetch"
      (is (= (builder/for-query
              :user
              {:id 9}
              {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]
               :offset 13})
             [(str "SELECT * FROM [user] WHERE `id` = ?"
                   " ORDER BY `a`, `b` DESC OFFSET ? ROWS")
              9 13])))
    (testing "with fetch"
      (is (= (builder/for-query
              :user
              {:id 9}
              {:table-fn sql-server :column-fn mysql :order-by [:a [:b :desc]]
               :offset 13 :fetch 42})
             [(str "SELECT * FROM [user] WHERE `id` = ?"
                   " ORDER BY `a`, `b` DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")
              9 13 42])))))

(deftest test-for-delete
  (testing "by example"
    (is (= (builder/for-delete
            :user
            {:opt nil :id 9}
            {:table-fn sql-server :column-fn mysql})
           ["DELETE FROM [user] WHERE `opt` IS NULL AND `id` = ?" 9])))
  (testing "by where clause"
    (is (= (builder/for-delete
            :user
            ["id = ? and opt is null" 9]
            {:table-fn sql-server :column-fn mysql})
           ["DELETE FROM [user] WHERE id = ? and opt is null" 9]))))

(deftest test-for-update
  (testing "empty example (would be a SQL error)"
    (is (thrown? AssertionError ; changed in #44
                 (builder/for-update :user
                                     {:status 42}
                                     {}
                                     {:table-fn sql-server :column-fn mysql}))))
  (testing "by example"
    (is (= (builder/for-update :user
                               {:status 42}
                               {:id 9}
                               {:table-fn sql-server :column-fn mysql})
           ["UPDATE [user] SET `status` = ? WHERE `id` = ?" 42 9])))
  (testing "by where clause, with nil set value"
    (is (= (builder/for-update :user
                               {:status 42, :opt nil}
                               ["id = ?" 9]
                               {:table-fn sql-server :column-fn mysql})
           ["UPDATE [user] SET `status` = ?, `opt` = ? WHERE id = ?" 42 nil 9]))))

(deftest test-for-inserts
  (testing "single insert"
    (is (= (builder/for-insert :user
                               {:id 9 :status 42 :opt nil}
                               {:table-fn sql-server :column-fn mysql})
           ["INSERT INTO [user] (`id`, `status`, `opt`) VALUES (?, ?, ?)" 9 42 nil])))
  (testing "multi-row insert (normal mode)"
    (is (= (builder/for-insert-multi :user
                                     [:id :status]
                                     [[42 "hello"]
                                      [35 "world"]
                                      [64 "dollars"]]
                                     {:table-fn sql-server :column-fn mysql})
           ["INSERT INTO [user] (`id`, `status`) VALUES (?, ?), (?, ?), (?, ?)" 42 "hello" 35 "world" 64 "dollars"])))
  (testing "multi-row insert (batch mode)"
    (is (= (builder/for-insert-multi :user
                                     [:id :status]
                                     [[42 "hello"]
                                      [35 "world"]
                                      [64 "dollars"]]
                                     {:table-fn sql-server :column-fn mysql :batch true})
           ["INSERT INTO [user] (`id`, `status`) VALUES (?, ?)" [42 "hello"] [35 "world"] [64 "dollars"]]))))
