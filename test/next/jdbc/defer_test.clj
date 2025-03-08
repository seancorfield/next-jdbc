;; copyright (c) 2024-2025 Sean Corfield, all rights reserved

(ns next.jdbc.defer-test
  "The idea behind the next.jdbc.defer namespace is to provide a
   way to defer the execution of a series of SQL statements until
   a later time, but still provide a way for inserted keys to be
   used in later SQL statements.

   The principle is to provide a core subset of the next.jdbc
   and next.jdbc.sql API that produces a data structure that
   describes a series of SQL operations to be performed, that
   are held in a dynamic var, and that can be executed at a
   later time, in a transaction."
  (:require [lazytest.core :refer [around set-ns-context!]]
            [lazytest.experimental.interfaces.clojure-test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.defer :as sut]
            [next.jdbc.test-fixtures
             :refer [ds with-test-db xtdb?]]))

(set! *warn-on-reflection* true)

(set-ns-context! [(around [f] (with-test-db f))])

(deftest basic-test
  (when-not (xtdb?)
    (testing "data structures"
      (is (= [{:sql-p ["INSERT INTO foo (name) VALUES (?)" "Sean"]
               :key-fn :GENERATED_KEY
               :key    :id
               :opts   {:key-fn :GENERATED_KEY :key :id}}]
             @(sut/defer-ops
               #(sut/insert! :foo {:name "Sean"} {:key-fn :GENERATED_KEY :key :id})))))
    (testing "execution"
      (let [effects (sut/with-deferred (ds)
                      (sut/insert! :fruit {:name "Mango"} {:key :test}))]
        (is (= {:test 1} @effects))
        (is (= 1 (count (jdbc/execute! (ds)
                                       ["select * from fruit where name = ?"
                                        "Mango"])))))
      (let [effects (sut/with-deferred (ds)
                      (sut/insert! :fruit {:name "Dragonfruit"} {:key :test})
                      (sut/update! :fruit {:cost 123} {:name "Dragonfruit"})
                      (sut/delete! :fruit {:name "Dragonfruit"}))]
        (is (= {:test 1} @effects))
        (is (= 0 (count (jdbc/execute! (ds)
                                       ["select * from fruit where name = ?"
                                        "Dragonfruit"])))))
      (let [effects (sut/with-deferred (ds)
                      (sut/insert! :fruit {:name "Grapefruit" :bad_column 0} {:key :test}))]
        (is (= :failed (try @effects
                            (catch Exception _ :failed))))
        (is (= 0 (count (jdbc/execute! (ds)
                                       ["select * from fruit where name = ?"
                                        "Grapefruit"]))))))))
