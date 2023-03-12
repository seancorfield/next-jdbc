;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.connection-test
  "Tests for the main hash map spec to JDBC URL logic and the get-datasource
  and get-connection protocol implementations.

  At some point, the datasource/connection tests should probably be extended
  to accept EDN specs from an external source (environment variables?)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc.connection :as c]
            [next.jdbc.protocols :as p])
  (:import (com.zaxxer.hikari HikariDataSource)
           (com.mchange.v2.c3p0 ComboPooledDataSource PooledDataSource)))

(set! *warn-on-reflection* true)

(def ^:private db-name "clojure_test")

(deftest test-aliases-and-defaults
  (testing "aliases"
    (is (= (#'c/spec->url+etc {:dbtype "hsql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "hsqldb" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "jtds" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "mssql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "sqlserver" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:sid" :dbname db-name})
           (-> (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
               ;; oracle:sid uses : before DB name, not /
               (update 0 str/replace (re-pattern (str "/" db-name)) (str ":" db-name)))))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:oci" :dbname db-name})
           (-> (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
               ;; oracle:oci and oracle:thin only differ in the protocol
               (update 0 str/replace #":thin" ":oci"))))
    (is (= (#'c/spec->url+etc {:dbtype "postgres" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "postgresql" :dbname db-name}))))
  (testing "default ports"
    (is (= (#'c/spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name :port 1433})))
    (is (= (#'c/spec->url+etc {:dbtype "mysql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "mysql" :dbname db-name :port 3306})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:oci" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:oci" :dbname db-name :port 1521})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:sid" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:sid" :dbname db-name :port 1521})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name :port 1521})))
    (is (= (#'c/spec->url+etc {:dbtype "postgresql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "postgresql" :dbname db-name :port 5432})))
    (is (= (#'c/spec->url+etc {:dbtype "sqlserver" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "sqlserver" :dbname db-name :port 1433})))))

(deftest custom-dbtypes
  (is (= ["jdbc:acme:my-db" {} nil]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host :none})))
  (is (= ["jdbc:acme://127.0.0.1/my-db" {} nil]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db"})))
  (is (= ["jdbc:acme://12.34.56.70:1234/my-db" {} nil]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host "12.34.56.70" :port 1234})))
  (is (= ["jdbc:acme:dsn=my-db" {} nil]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host :none
                             :dbname-separator ":dsn="})))
  (is (= ["jdbc:acme:(*)127.0.0.1/my-db" {} nil]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db"
                             :host-prefix "(*)"})))
  (is (= ["jdbc:acme:(*)12.34.56.70:1234/my-db" {} nil]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host "12.34.56.70" :port 1234
                             :host-prefix "(*)"}))))

(deftest jdbc-url-tests
  (testing "basic URLs work"
    (is (= "jdbc:acme:my-db"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db" :host :none})))
    (is (= "jdbc:acme://127.0.0.1/my-db"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db"})))
    (is (= "jdbc:acme://12.34.56.70:1234/my-db"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db" :host "12.34.56.70" :port 1234})))
    (is (= "jdbc:acme:dsn=my-db"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db" :host :none
                        :dbname-separator ":dsn="})))
    (is (= "jdbc:acme:(*)127.0.0.1/my-db"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db"
                        :host-prefix "(*)"})))
    (is (= "jdbc:acme:(*)12.34.56.70:1234/my-db"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db" :host "12.34.56.70" :port 1234
                        :host-prefix "(*)"}))))
  (testing "URLs with properties work"
    (is (= "jdbc:acme:my-db?useSSL=true"
           (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                        :dbname "my-db" :host :none
                        :useSSL true})))
    (is (boolean (#{"jdbc:acme:my-db?useSSL=true&user=dba"
                    "jdbc:acme:my-db?user=dba&useSSL=true"}
                  (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db" :host :none
                               :useSSL true :user "dba"}))))

    (is (= "jdbc:jtds:sqlserver:my-db;useSSL=true"
           (c/jdbc-url {:dbtype "jtds"
                        :dbname "my-db" :host :none
                        :useSSL true})))
    (is (boolean (#{"jdbc:jtds:sqlserver:my-db;useSSL=true;user=dba"
                    "jdbc:jtds:sqlserver:my-db;user=dba;useSSL=true"}
                  (c/jdbc-url {:dbtype "jtds"
                               :dbname "my-db" :host :none
                               :useSSL true :user "dba"}))))))

;; these are the 'local' databases that we can always test against
(def test-db-type ["derby" "h2" "h2:mem" "hsqldb" "sqlite"])

(def test-dbs
  (for [db test-db-type]
    (cond-> {:dbtype db :dbname (str db-name "_" (str/replace db #":" "_"))}
      (= "derby" db)
      (assoc :create true))))

(deftest test-sourceable-via-metadata
  (doseq [db test-dbs]
    (let [ds (p/get-datasource
              ^{`p/get-datasource (fn [v] (p/get-datasource (first v)))} [db])]
      (is (instance? javax.sql.DataSource ds)))))

(deftest test-get-connection
  (doseq [db test-dbs]
    (println 'test-get-connection (:dbtype db))
    (testing "datasource via Associative"
      (let [ds (p/get-datasource db)]
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) (str "jdbc:" (:dbtype db))))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via String"
      (let [[url _] (#'c/spec->url+etc db)
            ds (p/get-datasource url)]
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) url))
        (.setLoginTimeout ds 0)
        (is (= 0 (.getLoginTimeout ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via jdbcUrl"
      (let [[url etc] (#'c/spec->url+etc db)
            ds (p/get-datasource (assoc etc :jdbcUrl url))]
        (if (= "derby" (:dbtype db))
          (is (= {:create true} etc))
          (is (= {} etc)))
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) (str "jdbc:" (:dbtype db))))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (.setLoginTimeout ds 1)
        (is (= 1 (.getLoginTimeout ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via HikariCP"
      ;; the type hint is only needed because we want to call .close
      (with-open [^HikariDataSource ds (c/->pool HikariDataSource db)]
        (is (instance? javax.sql.DataSource ds))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via c3p0"
      ;; the type hint is only needed because we want to call .close
      (with-open [^PooledDataSource ds (c/->pool ComboPooledDataSource db)]
        (is (instance? javax.sql.DataSource ds))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "connection via map (Object)"
      (with-open [con (p/get-connection db {})]
        (is (instance? java.sql.Connection con))))))

(deftest issue-243-uri->db-spec
  (is (= {:dbtype "mysql" :dbname "mydb"
          :host "myserver" :port 1234
          :user "foo" :password "bar"}
         (c/uri->db-spec "mysql://foo:bar@myserver:1234/mydb")))
  (is (= {:dbtype "mysql" :dbname "mydb"
          :host "myserver" :port 1234
          :user "foo" :password "bar"}
         (c/uri->db-spec "jdbc:mysql://myserver:1234/mydb?user=foo&password=bar"))))