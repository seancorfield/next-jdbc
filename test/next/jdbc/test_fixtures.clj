;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.test-fixtures
  "Multi-database testing fixtures."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def ^:private test-derby {:dbtype "derby" :dbname "clojure_test_derby" :create true})

(def ^:private test-h2-mem {:dbtype "h2:mem" :dbname "clojure_test_h2_mem"})

(def ^:private test-h2 {:dbtype "h2" :dbname "clojure_test_h2"})

(def ^:private test-hsql {:dbtype "hsqldb" :dbname "clojure_test_hsqldb"})

(def ^:private test-sqlite {:dbtype "sqlite" :dbname "clojure_test_sqlite"})

(def ^:private test-db-specs [test-derby test-h2-mem test-h2 test-hsql test-sqlite])

(def ^:private test-db-spec (atom nil))

(defn derby? [] (= "derby" (:dbtype @test-db-spec)))

(defn sqlite? [] (= "sqlite" (:dbtype @test-db-spec)))

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
  (doseq [db test-db-specs]
    (reset! test-db-spec db)
    (reset! test-datasource (jdbc/get-datasource db))
    (let [auto-inc-pk
          (cond (or (derby?) (= "hsqldb" (:dbtype db)))
                (str "GENERATED ALWAYS AS IDENTITY"
                     " (START WITH 1, INCREMENT BY 1)"
                     " PRIMARY KEY")
                (sqlite?)
                "PRIMARY KEY AUTOINCREMENT"
                :else
                "AUTO_INCREMENT PRIMARY KEY")]
      (with-open [con (jdbc/get-connection (ds))]
        (try
          (jdbc/execute-one! con ["DROP TABLE FRUIT"])
          (catch Exception _))
        (jdbc/execute-one! con [(str "
CREATE TABLE FRUIT (
  ID INTEGER " auto-inc-pk ",
  NAME VARCHAR(32),
  APPEARANCE VARCHAR(32) DEFAULT NULL,
  COST INT DEFAULT NULL,
  GRADE REAL DEFAULT NULL
)")])
        (sql/insert-multi! con :fruit
                           [:name :appearance :cost :grade]
                           [["Apple" "red" 59 nil]
                            ["Banana" "yellow" nil 92.2]
                            ["Peach" nil 139 90.0]
                            ["Orange" "juicy" 89 88.6]]
                           {:return-keys false})
        (t)))))
