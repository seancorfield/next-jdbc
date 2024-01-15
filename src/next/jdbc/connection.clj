;; copyright (c) 2018-2024 Sean Corfield, all rights reserved

(ns next.jdbc.connection
  "Standard implementations of `get-datasource` and `get-connection`.

  Also provides `dbtypes` as a map of all known database types, and
  the `->pool` and `component` functions for creating pooled datasource
  objects."
  (:require [clojure.java.data :as j]
            [clojure.string :as str]
            [next.jdbc.protocols :as p])
  (:import (java.sql Connection DriverManager)
           (javax.sql DataSource)
           (java.util Properties)))

(set! *warn-on-reflection* true)

(def dbtypes
  "A map of all known database types (including aliases) to the class name(s)
  and port that `next.jdbc` supports out of the box. For databases that have
  non-standard prefixes for the `:dbname` and/or `:host` values in the JDBC
  string, this table includes `:dbname-separator` and/or `:host-prefix`. The
  default prefix for `:dbname` is either `/` or `:` and for `:host` it is `//`.
  For local databases, with no `:host`/`:port` segment in their JDBC URL, a
  value of `:none` is provided for `:host` in this table. In addition,
  `:property-separator` can specify how you build the JDBC URL.

  For known database types, you can use `:dbtype` (and omit `:classname`).

  If you want to use a database that is not in this list, you can specify
  a new `:dbtype` along with the class name of the JDBC driver in `:classname`.
  You will also need to specify `:port`. For example:

     `{:dbtype \"acme\" :classname \"com.acme.JdbcDriver\" ...}`

  The value of `:dbtype` should be the string that the driver is associated
  with in the JDBC URL, i.e., the value that comes between the `jdbc:`
  prefix and the `://<host>...` part. In the above example, the JDBC URL
  that would be generated would be `jdbc:acme://<host>:<port>/<dbname>`.

  If you want `next.jdbc` to omit the host/port part of the URL, specify
  `:host :none`, which would produce a URL like: `jdbc:acme:<dbname>`,
  which allows you to work with local databases (or drivers that do not
  need host/port information).

  The default prefix for the host name (or IP address) is `//`. You
  can override this via the `:host-prefix` option.

  The default separator between the host/port and the database name is `/`.
  The default separator between the subprotocol and the database name,
  for local databases with no host/port, is `:`. You can override this
  via the `:dbname-separator` option.

  JDBC drivers are not provided by `next.jdbc` -- you need to specify the
  driver(s) you need as additional dependencies in your project. For
  example:

     `[com.acme/jdbc \"1.2.3\"] ; lein/boot`

  or:

     `com.acme/jdbc {:mvn/version \"1.2.3\"} ; CLI/deps.edn`

  Note: the `:classname` value can be a string or a vector of strings. If
  a vector of strings is provided, an attempt will be made to load each
  named class in order, until one succeeds. This allows for a given `:dbtype`
  to be used with different versions of a JDBC driver, if the class name
  has changed over time (such as with MySQL)."
  {"derby"           {:classname "org.apache.derby.jdbc.EmbeddedDriver"
                      :host :none}
   "duckdb"          {:classname "org.duckdb.DuckDBDriver"
                      :host :none}
   "h2"              {:classname "org.h2.Driver"
                      :property-separator ";"
                      :host :none}
   "h2:mem"          {:classname "org.h2.Driver"
                      :property-separator ";"}
   "hsql"            {:classname "org.hsqldb.jdbcDriver"
                      :alias-for "hsqldb"
                      :host :none}
   "hsqldb"          {:classname "org.hsqldb.jdbcDriver"
                      :host :none}
   "jtds"            {:classname "net.sourceforge.jtds.jdbc.Driver"
                      :alias-for "jtds:sqlserver"
                      :property-separator ";"
                      :port 1433}
   "jtds:sqlserver"  {:classname "net.sourceforge.jtds.jdbc.Driver"
                      :property-separator ";"
                      :port 1433}
   "mariadb"         {:classname "org.mariadb.jdbc.Driver"
                      :port 3306}
   "mssql"           {:classname "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                      :alias-for "sqlserver"
                      :dbname-separator ";DATABASENAME="
                      :property-separator ";"
                      :port 1433}
   "mysql"           {:classname ["com.mysql.cj.jdbc.Driver"
                                  "com.mysql.jdbc.Driver"]
                      :port 3306}
   "oracle"          {:classname "oracle.jdbc.OracleDriver"
                      :alias-for "oracle:thin"
                      :host-prefix "@"
                      :port 1521}
   "oracle:oci"      {:classname "oracle.jdbc.OracleDriver"
                      :host-prefix "@"
                      :port 1521}
   "oracle:sid"      {:classname "oracle.jdbc.OracleDriver"
                      :alias-for "oracle:thin"
                      :dbname-separator ":"
                      :host-prefix "@"
                      :port 1521}
   "oracle:thin"     {:classname "oracle.jdbc.OracleDriver"
                      :host-prefix "@"
                      :port 1521}
   "postgres"        {:classname "org.postgresql.Driver"
                      :alias-for "postgresql"
                      :port 5432}
   "postgresql"      {:classname "org.postgresql.Driver"
                      :port 5432}
   "pgsql"           {:classname "com.impossibl.postgres.jdbc.PGDriver"}
   "redshift"        {:classname "com.amazon.redshift.jdbc.Driver"}
   "sqlite"          {:classname "org.sqlite.JDBC"
                      :host :none}
   "sqlserver"       {:classname "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                      :dbname-separator ";DATABASENAME="
                      :property-separator ";"
                      :port 1433}
   "timesten:client" {:classname "com.timesten.jdbc.TimesTenClientDriver"
                      :dbname-separator ":dsn="
                      :host :none}
   "timesten:direct" {:classname "com.timesten.jdbc.TimesTenDriver"
                      :dbname-separator ":dsn="
                      :host :none}})

(def ^:private driver-cache
  "An optimization for repeated calls to get-datasource, or for get-connection
  called on a db-spec hash map, so that we only try to load the classes once."
  (atom {}))

(defn- spec->url+etc
  "Given a database spec, return a JDBC URL and a map of any additional options.

  As a special case, the database spec can contain jdbcUrl (just like ->pool),
  in which case it will return that URL as-is and a map of any other options."
  [{:keys [dbtype dbname host port classname
           dbname-separator host-prefix property-separator
           jdbcUrl]
    :as db-spec}]
  (let [etc (dissoc db-spec
                    :dbtype :dbname :host :port :classname
                    :dbname-separator :host-prefix :property-separator
                    :jdbcUrl)]
    (if jdbcUrl
      [jdbcUrl etc]
      (let [;; allow aliases for dbtype
            subprotocol (-> dbtype dbtypes :alias-for (or dbtype))
            host        (or host (-> dbtype dbtypes :host) "127.0.0.1")
            port        (or port (-> dbtype dbtypes :port))
            db-sep      (or dbname-separator (-> dbtype dbtypes :dbname-separator (or "/")))
            local-sep   (or dbname-separator (-> dbtype dbtypes :dbname-separator (or ":")))
            url (cond (#{"h2"} subprotocol)
                      (str "jdbc:" subprotocol local-sep
                           (if (re-find #"^([A-Za-z]:)?[\./\\]" dbname)
                             ;; DB name starts with relative or absolute path
                             dbname
                             ;; otherwise make it local
                             (str "./" dbname)))

                      (#{"h2:mem"} subprotocol)
                      (str "jdbc:" subprotocol local-sep dbname ";DB_CLOSE_DELAY=-1")

                      (= :none host)
                      (str "jdbc:" subprotocol local-sep dbname)

                      :else
                      (str "jdbc:" subprotocol ":"
                           (or host-prefix (-> dbtype dbtypes :host-prefix (or "//")))
                           host
                           (when (and port (not= :none port)) (str ":" port))
                           db-sep dbname))]
        ;; verify the datasource is loadable
        (if-let [class-name (or classname (-> dbtype dbtypes :classname))]
          (swap! driver-cache update class-name
                 #(if % %
                    (let [;; force DriverManager to be loaded
                          _ (DriverManager/getLoginTimeout)]
                      (if (string? class-name)
                        (clojure.lang.RT/loadClassForName class-name)
                        (loop [[clazz & more] class-name]
                          (let [loaded
                                (try
                                  (clojure.lang.RT/loadClassForName clazz)
                                  (catch Exception e
                                    e))]
                            (if (instance? Throwable loaded)
                              (if (seq more)
                                (recur more)
                                (throw loaded))
                              loaded)))))))
          (throw (ex-info (str "Unknown dbtype: " dbtype
                               ", and :classname not provided.")
                          db-spec)))
        [url etc (or property-separator
                     (-> dbtype dbtypes :property-separator))]))))

(defn jdbc-url
  "Given a database spec (as a hash map), return a JDBC URL with all the
  attributes added to the query string. The result is suitable for use in
  calls to `->pool` and `component` as the `:jdbcUrl` key in the parameter
  map for the connection pooling library.

  This allows you to build a connection-pooled datasource that needs
  additional settings that the pooling library does not support, such as
  `:serverTimezone`:

```clojure
  (def db-spec {:dbtype .. :dbname .. :user .. :password ..
                :serverTimezone \"UTC\"})
  (def ds (next.jdbc.connection/->pool
           HikariCP {:jdbcUrl (next.jdbc.connection/jdbc-url db-spec)
                     :maximumPoolSize 15}))
```

  This also clearly separates the attributes that should be part of the
  JDBC URL from the attributes that should be configured on the pool.

  Since JDBC drivers can handle URL encoding differently, if you are
  trying to pass attributes that might need encoding, you should make
  sure they are properly URL-encoded as values in the database spec hash map.
  This function does **not** attempt to URL-encode values for you!"
  [db-spec]
  (let [[url etc ps] (spec->url+etc db-spec)
        url-and      (or ps (if (str/index-of url "?") "&" "?"))]
    (if (seq etc)
      (str url url-and (str/join (or ps "&")
                                 (reduce-kv (fn [pairs k v]
                                              (conj pairs (str (name k) "=" v)))
                                            []
                                            etc)))
      url)))

(defn ->pool
  "Given a (connection pooled datasource) class and a database spec, return a
  connection pool object built from that class and the database spec.

  Assumes the `clazz` has a `.setJdbcUrl` method (which HikariCP and c3p0 do).

  If you already have a JDBC URL and want to use this method, pass `:jdbcUrl`
  in the database spec (instead of `:dbtype`, `:dbname`, etc).

  Properties for the connection pool object can be passed as mixed case
  keywords that correspond to setter methods (just as `:jdbcUrl` maps to
  `.setJdbcUrl`). `clojure.java.data/to-java` is used to construct the
  object and call the setters.

  If you need to pass in connection URL parameters, it can be easier to use
  `next.jdbc.connection/jdbc-url` to construct URL, e.g.,

  (->pool HikariDataSource
          {:jdbcUrl (jdbc-url {:dbtype .. :dbname .. :useSSL false})
           :username .. :password ..})

  Here we pass `:useSSL false` to `jdbc-url` so that it ends up in the
  connection string, but pass `:username` and `:password` for the pool itself.

  Note that the result is not type-hinted (because there's no common base
  class or interface that can be assumed). In particular, connection pooled
  datasource objects may need to be closed but they don't necessarily implement
  `java.io.Closeable` (HikariCP does, c3p0 does not)."
  [clazz db-spec]
  (if (:jdbcUrl db-spec)
    (j/to-java clazz db-spec)
    (let [[url etc] (spec->url+etc db-spec)]
      (j/to-java clazz (assoc etc :jdbcUrl url)))))

(defn- attempt-close
  "Given an arbitrary object that almost certainly supports a `.close`
  method that takes no arguments and returns `void`, try to find it
  and call it."
  [obj]
  (let [^Class clazz (class obj)
        ^java.lang.reflect.Method close
        (->> (.getMethods clazz)
             (filter (fn [^java.lang.reflect.Method m]
                       (and (= "close" (.getName m))
                            (empty? (.getParameterTypes m))
                            (= "void" (.getName (.getReturnType m))))))
             (first))]
    (when close
      (.invoke close obj (object-array [])))))

(defn component
  "Takes the same arguments as `->pool` but returns an entity compatible
  with Stuart Sierra's Component: when `com.stuartsierra.component/start`
  is called on it, it builds a connection pooled datasource, and returns
  an entity that can either be invoked as a function with no arguments
  to return that datasource, or can have `com.stuartsierra.component/stop`
  called on it to shutdown the datasource (and return a new startable
  entity).

  If `db-spec` contains `:init-fn`, that is assumed to be a function
  that should be called on the newly-created datasource. This allows for
  modification of (mutable) connection pooled datasource and/or some sort
  of database initialization/setup to be called automatically.

  By default, the datasource is shutdown by calling `.close` on it.
  If the datasource class implements `java.io.Closeable` then a direct,
  type-hinted call to `.close` will be used, with no reflection,
  otherwise Java reflection will be used to find the first `.close`
  method in the datasource class that takes no arguments and returns `void`.

  If neither of those behaviors is appropriate, you may supply a third
  argument to this function -- `close-fn` -- which performs whatever
  action is appropriate to your chosen datasource class."
  ([clazz db-spec]
   (component clazz db-spec #(if (isa? clazz java.io.Closeable)
                               (.close ^java.io.Closeable %)
                               (attempt-close %))))
  ([clazz db-spec close-fn]
   (with-meta {}
     {'com.stuartsierra.component/start
      (fn [_]
        (let [init-fn (:init-fn db-spec)
              pool    (->pool clazz (dissoc db-spec :init-fn))]
          (when init-fn (init-fn pool))
          (with-meta (fn ^DataSource [] pool)
            {'com.stuartsierra.component/stop
             (fn [_]
               (close-fn pool)
               (component clazz db-spec close-fn))})))})))

(comment
  (require '[com.stuartsierra.component :as component]
           '[next.jdbc.sql :as sql])
  (import '(com.mchange.v2.c3p0 ComboPooledDataSource PooledDataSource)
          '(com.zaxxer.hikari HikariDataSource))
  (isa? PooledDataSource java.io.Closeable) ;=> false
  (isa? HikariDataSource java.io.Closeable) ;=> true
  ;; create a pool with a combination of JDBC URL and username/password:
  (->pool HikariDataSource
          {:jdbcUrl
           (jdbc-url {:dbtype "mysql" :dbname "clojure_test"
                      :useSSL false})
           :username "root" :password (System/getenv "MYSQL_ROOT_PASSWORD")})
  ;; use c3p0 with default reflection-based closing function:
  (def dbc (component ComboPooledDataSource
                      {:dbtype "mysql" :dbname "clojure_test"
                       :user "clojure_test" :password "clojure_test"}))
  ;; use c3p0 with a type-hinted closing function:
  (def dbc (component ComboPooledDataSource
                      {:dbtype "mysql" :dbname "clojure_test"
                       :user "clojure_test" :password "clojure_test"}
                      #(.close ^PooledDataSource %)))
  ;; use HikariCP with default Closeable .close function:
  (def dbc (component HikariDataSource
                      {:dbtype "mysql" :dbname "clojure_test"
                       ;; HikariCP requires :username, not :user
                       :username "clojure_test" :password "clojure_test"}))
  ;; start the chosen datasource component:
  (def ds  (component/start dbc))
  ;; invoke datasource component to get the underlying javax.sql.DataSource:
  (sql/get-by-id (ds) :fruit 1)
  ;; stop the component and close the pooled datasource:
  (component/stop ds)
  )

(defn- string->url+etc
  "Given a JDBC URL, return it with an empty set of options with no parsing."
  [s]
  [s {}])

(defn- as-properties
  "Convert any seq of pairs to a `java.util.Properties` instance."
  ^Properties [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (name k) (str v)))
    p))

(defn uri->db-spec
  "clojure.java.jdbc (and some users out there) considered the URI format
  to be an acceptable JDBC URL, i.e., with credentials embdedded in the string,
  rather than as query parameters.

  This function accepts a URI string, optionally prefixed with `jdbc:` and
  returns a db-spec hash map."
  [uri]
  (let [{:keys [scheme userInfo host port path query]}
        (j/from-java (java.net.URI. (str/replace uri #"^jdbc:" "")))
        [user password] (when (seq userInfo) (str/split userInfo #":"))
        properties (when (seq query)
                     (into {}
                           (map #(let [[k v] (str/split % #"=")]
                                   [(keyword k) v]))
                           (str/split query #"\&")))]
    (cond-> (assoc properties
                   :dbtype scheme
                   :host   host
                   :port   port)
      (seq path) (assoc :dbname (subs path 1))
      user (assoc :user user)
      password (assoc :password password))))

(defn- get-driver-connection
  "Common logic for loading the designated JDBC driver class and
  obtaining the appropriate `Connection` object."
  [url timeout etc]
  (when timeout (DriverManager/setLoginTimeout timeout))
  (try
    (DriverManager/getConnection url (as-properties etc))
    (catch Exception e
      (try
        (let [db-spec (uri->db-spec url)
              [url' etc'] (spec->url+etc db-spec)]
          (DriverManager/getConnection url' (as-properties (merge etc' etc))))
        (catch Exception _
          ;; if the fallback fails too, throw the original exception
          (throw e))))))

(defn- url+etc->datasource
  "Given a JDBC URL and a map of options, return a `DataSource` that can be
  used to obtain a new database connection."
  [[url etc]]
  (let [login-timeout (atom nil)]
    (reify DataSource
      (getConnection [_]
                     (get-driver-connection url @login-timeout etc))
      (getConnection [_ username password]
                     (get-driver-connection url @login-timeout
                                            (assoc etc
                                                   :user username
                                                   :password password)))
      (getLoginTimeout [_] (or @login-timeout 0))
      (setLoginTimeout [_ secs] (reset! login-timeout secs))
      (toString [_] url))))

(defn- make-connection
  "Given a `DataSource` and a map of options, get a connection and update it
  as specified by the options.

  These options are supported:
  * `:auto-commit` -- whether the connection should be set to auto-commit or not;
      without this option, the default is `true` -- connections will auto-commit,
  * `:read-only` -- whether the connection should be set to read-only mode,
  * `:connection` -- a hash map of camelCase properties to set on the connection,
      via reflection, e.g., :autoCommit, :readOnly, :schema..."
  ^Connection
  [^DataSource datasource opts]
  (let [^Connection connection (if (and (:user opts) (:password opts))
                                 (.getConnection datasource
                                                 (:user opts)
                                                 (:password opts))
                                 (.getConnection datasource))]
    ;; fast, specific option handling:
    (when (contains? opts :auto-commit)
      (.setAutoCommit connection (boolean (:auto-commit opts))))
    (when (contains? opts :read-only)
      (.setReadOnly connection (boolean (:read-only opts))))
    ;; slow, general-purpose option handling:
    (when-let [props (:connection opts)]
      (j/set-properties connection props))
    connection))

(extend-protocol p/Sourceable
  clojure.lang.Associative
  (get-datasource [this]
                  ;; #207 c.j.j compatibility:
                  (if-let [datasource (:datasource this)]
                    datasource
                    (url+etc->datasource
                     (if-let [uri (:connection-uri this)]
                       (string->url+etc uri)
                       (spec->url+etc this)))))
  javax.sql.DataSource
  (get-datasource [this] this)
  String
  (get-datasource [this]
                  (url+etc->datasource (string->url+etc this))))

(extend-protocol p/Connectable
  javax.sql.DataSource
  (get-connection [this opts] (make-connection this opts))
  java.sql.PreparedStatement
  ;; note: options are ignored and this should not be closed independently
  ;; of the PreparedStatement to which it belongs: this done to allow
  ;; datafy/nav across a PreparedStatement only...
  (get-connection [this _] (.getConnection this))
  Object
  (get-connection [this opts] (p/get-connection (p/get-datasource this) opts)))
