;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.test-fixtures
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def ^:private test-db-spec {:dbtype "h2:mem" :dbname "clojure_test_fixture"})

(def ^:private test-datasource (atom nil))

(defn ds
  "Tests should call this to get the DataSource to use inside a fixture."
  []
  @test-datasource)

(defn with-test-db
  "Given a test function (or suite), run it in the context of an in-memory
  H2 database set up with a simple fruit table containing four rows of data.

  Tests can reach into here and call ds (above) to get a DataSource for use
  in test functions (that operate inside this fixture)."
  [t]
  (reset! test-datasource (jdbc/get-datasource test-db-spec))
  (with-open [con (jdbc/get-connection (ds))]
    (try
      (jdbc/execute-one! con ["DROP TABLE fruit"])
      (catch Exception _))
    (jdbc/execute-one! con ["
CREATE TABLE fruit (
  id int auto_increment primary key,
  name varchar(32),
  appearance varchar(32),
  cost int,
  grade real
)"])
    (sql/insert-multi! con :fruit
                       [:id :name :appearance :cost :grade]
                       [[1 "Apple" "red" 59 87]
                        [2,"Banana","yellow",29,92.2]
                        [3,"Peach","fuzzy",139,90.0]
                        [4,"Orange","juicy",89,88.6]]
                       {:return-keys false})
    (t)))
