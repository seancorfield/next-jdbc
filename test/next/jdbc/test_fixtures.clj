;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.test-fixtures
  "Multi-database testing fixtures."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prep]
            [next.jdbc.sql :as sql])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(set! *warn-on-reflection* true)

(def ^:private test-derby {:dbtype "derby" :dbname "clojure_test_derby" :create true})

(def ^:private test-h2-mem {:dbtype "h2:mem" :dbname "clojure_test_h2_mem"})

(def ^:private test-h2 {:dbtype "h2" :dbname "clojure_test_h2"})

(def ^:private test-hsql {:dbtype "hsqldb" :dbname "clojure_test_hsqldb"})

(def ^:private test-sqlite {:dbtype "sqlite" :dbname "clojure_test_sqlite"})

;; this is just a dummy db-spec -- it's handled in with-test-db below
(def ^:private test-postgres {:dbtype "embedded-postgres"})
;; it takes a while to spin up so we kick it off at startup
(defonce embedded-pg (future (EmbeddedPostgres/start)))

(def ^:private test-mysql-map
  (merge (if (System/getenv "NEXT_JDBC_TEST_MARIADB")
           {:dbtype "mariadb"}
           {:dbtype "mysql" :disableMariaDbDriver true})
         {:dbname "clojure_test" :useSSL false
          :user "root" :password (System/getenv "MYSQL_ROOT_PASSWORD")}))
(def ^:private test-mysql
  (when (System/getenv "NEXT_JDBC_TEST_MYSQL") test-mysql-map))

(def ^:private test-mssql-map
  {:dbtype "mssql" :dbname "model"
   :user "sa" :password (System/getenv "MSSQL_SA_PASSWORD")})
(def ^:private test-mssql
  (when (System/getenv "NEXT_JDBC_TEST_MSSQL") test-mssql-map))

(def ^:private test-jtds-map
  {:dbtype "jtds" :dbname "model"
   :user "sa" :password (System/getenv "MSSQL_SA_PASSWORD")})
(def ^:private test-jtds
  (when (System/getenv "NEXT_JDBC_TEST_MSSQL") test-jtds-map))

(def ^:private test-db-specs
  (cond-> [test-derby test-h2-mem test-h2 test-hsql test-sqlite test-postgres]
    test-mysql (conj test-mysql)
    test-mssql (conj test-mssql test-jtds)))

(def ^:private test-db-spec (atom nil))

(defn derby? [] (= "derby" (:dbtype @test-db-spec)))

(defn jtds? [] (= "jtds" (:dbtype @test-db-spec)))

(defn maria? [] (= "mariadb" (:dbtype @test-db-spec)))

(defn mssql? [] (#{"jtds" "mssql"} (:dbtype @test-db-spec)))

(defn mysql? [] (#{"mariadb" "mysql"} (:dbtype @test-db-spec)))

(defn postgres? [] (= "embedded-postgres" (:dbtype @test-db-spec)))

(defn sqlite? [] (= "sqlite" (:dbtype @test-db-spec)))

(defn stored-proc? [] (not (#{"derby" "h2" "h2:mem" "sqlite"} (:dbtype @test-db-spec))))

(defn column [k]
  (let [n (namespace k)]
    (keyword (when n (cond (postgres?) (str/lower-case n)
                           (mssql?)    (str/lower-case n)
                           (mysql?)    (str/lower-case n)
                           :else       n))
             (cond (postgres?) (str/lower-case (name k))
                   :else       (name k)))))

(defn default-options []
  (if (mssql?) ; so that we get table names back from queries
    {:result-type :scroll-insensitive :concurrency :read-only}
    {}))

(def ^:private test-datasource (atom nil))

(defn db
  "Tests should call this to get the db-spec to use inside a fixture."
  []
  @test-db-spec)

(defn ds
  "Tests should call this to get the DataSource to use inside a fixture."
  []
  @test-datasource)

(defn- do-commands
  "Example from migration docs: this serves as a test for it."
  [connectable commands]
  (if (instance? java.sql.Connection connectable)
    (with-open [stmt (prep/statement connectable)]
      (run! #(.addBatch stmt %) commands)
      (into [] (.executeBatch stmt)))
    (with-open [conn (jdbc/get-connection connectable)]
      (do-commands conn commands))))

(defn with-test-db
  "Given a test function (or suite), run it in the context of an in-memory
  H2 database set up with a simple fruit table containing four rows of data.

  Tests can reach into here and call ds (above) to get a DataSource for use
  in test functions (that operate inside this fixture)."
  [t]
  (doseq [db test-db-specs]
    (reset! test-db-spec db)
    (if (= "embedded-postgres" (:dbtype db))
      (reset! test-datasource
              (.getPostgresDatabase ^EmbeddedPostgres @embedded-pg))
      (reset! test-datasource (jdbc/get-datasource db)))
    (let [fruit (if (mysql?) "fruit" "FRUIT") ; MySQL is case sensitive!
          auto-inc-pk
          (cond (or (derby?) (= "hsqldb" (:dbtype db)))
                (str "GENERATED ALWAYS AS IDENTITY"
                     " (START WITH 1, INCREMENT BY 1)"
                     " PRIMARY KEY")
                (postgres?)
                (str "GENERATED ALWAYS AS IDENTITY"
                     " PRIMARY KEY")
                (mssql?)
                "IDENTITY PRIMARY KEY"
                (sqlite?)
                "PRIMARY KEY AUTOINCREMENT"
                :else
                "AUTO_INCREMENT PRIMARY KEY")]
      (with-open [con (jdbc/get-connection (ds))]
        (when (stored-proc?)
          (try
            (jdbc/execute-one! con ["DROP PROCEDURE FRUITP"])
            (catch Throwable _)))
        (try
          (do-commands con [(str "DROP TABLE " fruit)])
          (catch Throwable _))
        (do-commands con [(str "
CREATE TABLE " fruit " (
  ID INTEGER " auto-inc-pk ",
  NAME VARCHAR(32),
  APPEARANCE VARCHAR(32) DEFAULT NULL,
  COST INT DEFAULT NULL,
  GRADE REAL DEFAULT NULL
)")])
        (when (stored-proc?)
          (let [[begin end] (if (postgres?) ["$$" "$$"] ["BEGIN" "END"])]
            (try
              (do-commands con [(str "
CREATE PROCEDURE FRUITP" (cond (= "hsqldb" (:dbtype db)) "() READS SQL DATA DYNAMIC RESULT SETS 2 "
                               (mssql?) " AS "
                               (postgres?) "() LANGUAGE SQL AS "
                               :else "() ") "
 " begin " " (if (= "hsqldb" (:dbtype db))
               (str "ATOMIC
  DECLARE result1 CURSOR WITH RETURN FOR SELECT * FROM " fruit " WHERE COST < 90;
  DECLARE result2 CURSOR WITH RETURN FOR SELECT * FROM " fruit " WHERE GRADE >= 90.0;
  OPEN result1;
  OPEN result2;")
               (str "
  SELECT * FROM " fruit " WHERE COST < 90;
  SELECT * FROM " fruit " WHERE GRADE >= 90.0;")) "
 " end "
")])
              (catch Throwable t
                (println 'procedure (:dbtype db) (ex-message t))))))
       (sql/insert-multi! con :fruit
                          [:name :appearance :cost :grade]
                          [["Apple" "red" 59 nil]
                           ["Banana" "yellow" nil 92.2]
                           ["Peach" nil 139 90.0]
                           ["Orange" "juicy" 89 88.6]]
                          {:return-keys false})
       (t)))))

(comment
  ;; this is a convenience to bring next.jdbc's test dependencies
  ;; into any REPL that has the add-lib branch of tools.deps.alpha
  ;; which allows me to develop and test next.jdbc inside my work's
  ;; "everything" REBL environment
  (require '[clojure.tools.deps.alpha.repl :refer [add-lib]]
           '[clojure.java.io :as io]
           '[clojure.edn :as edn])
  (def repo-path "/Developer/workspace/next.jdbc")
  (def test-deps (-> (io/reader (str repo-path "/deps.edn"))
                     (java.io.PushbackReader.)
                     (edn/read)
                     :aliases
                     :test
                     :extra-deps))
  (doseq [[coord version] test-deps]
    (add-lib coord version))
  ;; now you can load this file... and then you can load other test
  ;; files and run their tests as needed... which will leave (ds)
  ;; set to the embedded PostgreSQL datasource -- reset it with this:
  (let [db test-h2-mem #_test-mysql-map]
    (reset! test-db-spec db)
    (reset! test-datasource (jdbc/get-datasource db))))
