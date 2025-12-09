;; copyright (c) 2019-2025 Sean Corfield, all rights reserved

(ns next.jdbc.connection-test
  "Tests for the main hash map spec to JDBC URL logic and the get-datasource
  and get-connection protocol implementations.

  At some point, the datasource/connection tests should probably be extended
  to accept EDN specs from an external source (environment variables?)."
  (:require [clojure.string :as str]
            [lazytest.core :refer [defdescribe expect it]]
            [next.jdbc.connection :as c]
            [next.jdbc.protocols :as p])
  (:import (com.mchange.v2.c3p0 ComboPooledDataSource PooledDataSource)
           (com.zaxxer.hikari HikariDataSource)))

(set! *warn-on-reflection* true)

(def ^:private db-name "clojure_test")

(def ^:private spec->url+etc #'c/spec->url+etc)

(defdescribe test-aliases-and-defaults
  "spec->url+etc dbtype aliases and default ports"
  (it "aliases"
    (expect (= (spec->url+etc {:dbtype "hsql" :dbname db-name})
               (spec->url+etc {:dbtype "hsqldb" :dbname db-name})))
    (expect (= (spec->url+etc {:dbtype "jtds" :dbname db-name})
               (spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name})))
    (expect (= (spec->url+etc {:dbtype "mssql" :dbname db-name})
               (spec->url+etc {:dbtype "sqlserver" :dbname db-name})))
    (expect (= (spec->url+etc {:dbtype "oracle" :dbname db-name})
               (spec->url+etc {:dbtype "oracle:thin" :dbname db-name})))
    (expect (= (spec->url+etc {:dbtype "oracle:sid" :dbname db-name})
               (-> (spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
                   ;; oracle:sid uses : before DB name, not /
                   (update 0 str/replace (re-pattern (str "/" db-name)) (str ":" db-name)))))
    (expect (= (spec->url+etc {:dbtype "oracle:oci" :dbname db-name})
               (-> (spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
                   ;; oracle:oci and oracle:thin only differ in the protocol
                   (update 0 str/replace #":thin" ":oci"))))
    (expect (= (spec->url+etc {:dbtype "postgres" :dbname db-name})
               (spec->url+etc {:dbtype "postgresql" :dbname db-name}))))
  (it "default ports"
    (expect (= (spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name})
               (spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name :port 1433})))
    (expect (= (spec->url+etc {:dbtype "mysql" :dbname db-name})
               (spec->url+etc {:dbtype "mysql" :dbname db-name :port 3306})))
    (expect (= (spec->url+etc {:dbtype "oracle:oci" :dbname db-name})
               (spec->url+etc {:dbtype "oracle:oci" :dbname db-name :port 1521})))
    (expect (= (spec->url+etc {:dbtype "oracle:sid" :dbname db-name})
               (spec->url+etc {:dbtype "oracle:sid" :dbname db-name :port 1521})))
    (expect (= (spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
               (spec->url+etc {:dbtype "oracle:thin" :dbname db-name :port 1521})))
    (expect (= (spec->url+etc {:dbtype "postgresql" :dbname db-name})
               (spec->url+etc {:dbtype "postgresql" :dbname db-name :port 5432})))
    (expect (= (spec->url+etc {:dbtype "sqlserver" :dbname db-name})
               (spec->url+etc {:dbtype "sqlserver" :dbname db-name :port 1433})))))

(defdescribe custom-dbtypes
  "spec->url+etc custom dbtypes"
  (it "acme dbtype"
    (expect (= ["jdbc:acme:my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db" :host :none})))
    (expect (= ["jdbc:acme://127.0.0.1/my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db"})))
    (expect (= ["jdbc:acme://12.34.56.70:1234/my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db" :host "12.34.56.70" :port 1234})))
    (expect (= ["jdbc:acme:dsn=my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db" :host :none
                               :dbname-separator ":dsn="})))
    (expect (= ["jdbc:acme:(*)127.0.0.1/my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db"
                               :host-prefix "(*)"})))
    (expect (= ["jdbc:acme:(*)12.34.56.70:1234/my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db" :host "12.34.56.70" :port 1234
                               :host-prefix "(*)"})))
    (expect (= ["jdbc:acme:(*)12.34.56.70/my-db" {} nil]
               (spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                               :dbname "my-db" :host "12.34.56.70" :port :none
                               :host-prefix "(*)"})))))

(defdescribe jdbc-url-tests
  "jdbc-url function"
  (it "basic URLs work"
    (expect (= "jdbc:acme:my-db"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db" :host :none})))
    (expect (= "jdbc:acme://127.0.0.1/my-db"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db"})))
    (expect (= "jdbc:acme://12.34.56.70:1234/my-db"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db" :host "12.34.56.70" :port 1234})))
    (expect (= "jdbc:acme:dsn=my-db"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db" :host :none
                            :dbname-separator ":dsn="})))
    (expect (= "jdbc:acme:(*)127.0.0.1/my-db"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db"
                            :host-prefix "(*)"})))
    (expect (= "jdbc:acme:(*)12.34.56.70:1234/my-db"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db" :host "12.34.56.70" :port 1234
                            :host-prefix "(*)"}))))
  (it "URLs with properties work"
    (expect (= "jdbc:acme:my-db?useSSL=true"
               (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                            :dbname "my-db" :host :none
                            :useSSL true})))
    (expect (boolean (#{"jdbc:acme:my-db?useSSL=true&user=dba"
                        "jdbc:acme:my-db?user=dba&useSSL=true"}
                      (c/jdbc-url {:dbtype "acme" :classname "java.lang.String"
                                   :dbname "my-db" :host :none
                                   :useSSL true :user "dba"}))))

    (expect (= "jdbc:jtds:sqlserver:my-db;useSSL=true"
               (c/jdbc-url {:dbtype "jtds"
                            :dbname "my-db" :host :none
                            :useSSL true})))
    (expect (boolean (#{"jdbc:jtds:sqlserver:my-db;useSSL=true;user=dba"
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

(defdescribe test-sourceable-via-metadata
  "get-datasource (via protocol) function"
  (doseq [db test-dbs]
    (it (str (:dbtype db) " datasource via metadata")
      (let [ds (p/get-datasource
                ^{`p/get-datasource (fn [v] (p/get-datasource (first v)))} [db])]
        (expect (instance? javax.sql.DataSource ds))))))

(defdescribe test-get-connection
  "get-connection function"
  (doseq [db test-dbs]
    (it (str (:dbtype db) " datasource via Associative")
      (let [ds (p/get-datasource db)]
        (expect (instance? javax.sql.DataSource ds))
        (expect (str/index-of (pr-str ds) (str "jdbc:" (:dbtype db))))
        ;; checks get-datasource on a DataSource is identity
        (expect (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (expect (instance? java.sql.Connection con)))))
    (it (str (:dbtype db) " datasource via String")
      (let [[url _] (spec->url+etc db)
            ds (p/get-datasource url)]
        (expect (instance? javax.sql.DataSource ds))
        (expect (str/index-of (pr-str ds) url))
        (.setLoginTimeout ds 0)
        (expect (= 0 (.getLoginTimeout ds)))
        (with-open [con (p/get-connection ds {})]
          (expect (instance? java.sql.Connection con)))))
    (it (str (:dbtype db) " datasource via jdbcUrl")
      (let [[url etc] (spec->url+etc db)
            ds (p/get-datasource (assoc etc :jdbcUrl url))]
        (if (= "derby" (:dbtype db))
          (expect (= {:create true} etc))
          (expect (= {} etc)))
        (expect (instance? javax.sql.DataSource ds))
        (expect (str/index-of (pr-str ds) (str "jdbc:" (:dbtype db))))
        ;; checks get-datasource on a DataSource is identity
        (expect (identical? ds (p/get-datasource ds)))
        (.setLoginTimeout ds 1)
        (expect (= 1 (.getLoginTimeout ds)))
        (with-open [con (p/get-connection ds {})]
          (expect (instance? java.sql.Connection con)))))
    (it (str (:dbtype db) " datasource via HikariCP")
      ;; the type hint is only needed because we want to call .close
      (with-open [^HikariDataSource ds (c/->pool HikariDataSource db)]
        (expect (instance? javax.sql.DataSource ds))
        ;; checks get-datasource on a DataSource is identity
        (expect (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (expect (instance? java.sql.Connection con)))))
    (it (str (:dbtype db) " datasource via c3p0")
      ;; the type hint is only needed because we want to call .close
      (with-open [^PooledDataSource ds (c/->pool ComboPooledDataSource db)]
        (expect (instance? javax.sql.DataSource ds))
        ;; checks get-datasource on a DataSource is identity
        (expect (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (expect (instance? java.sql.Connection con)))))
    (it (str (:dbtype db) " connection via map (Object)")
      (with-open [con (p/get-connection db {})]
        (expect (instance? java.sql.Connection con))))))

(defdescribe issue-243-uri->db-spec
  "issue #243: uri->db-spec function"
  (it "parses username and password in URIs correctly"
    (expect (= {:dbtype "mysql" :dbname "mydb"
                :host "myserver" :port 1234
                :user "foo" :password "bar"}
               (c/uri->db-spec "mysql://foo:bar@myserver:1234/mydb")))
    (expect (= {:dbtype "mysql" :dbname "mydb"
                :host "myserver" :port 1234
                :user "foo" :password "bar"}
               (c/uri->db-spec "jdbc:mysql://myserver:1234/mydb?user=foo&password=bar")))))
