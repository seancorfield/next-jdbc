;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  ""
  (:import (java.lang AutoCloseable)
           (java.sql Connection DriverManager
                     PreparedStatement
                     ResultSet ResultSetMetaData
                     SQLException Statement)
           (javax.sql DataSource)
           (java.util Properties)))

(set! *warn-on-reflection* true)

(defprotocol Sourceable
  (get-datasource ^DataSource [this]))
(defprotocol Connectable
  (get-connection ^AutoCloseable [this opts]))
(defprotocol Executable
  (-execute ^clojure.lang.IReduceInit [this sql-params opts]))
(defprotocol Preparable
  (prepare ^PreparedStatement [this sql-params opts]))
(defprotocol Transactable
  (-transact [this body-fn opts]))

(defn set-parameters
  ""
  ^PreparedStatement
  [^PreparedStatement ps params]
  (when (seq params)
    (loop [[p & more] params i 1]
      (.setObject ps i p)
      (when more
        (recur more (inc i)))))
  ps)

(def ^{:private true
       :doc "Map friendly :concurrency values to ResultSet constants."}
  result-set-concurrency
  {:read-only ResultSet/CONCUR_READ_ONLY
   :updatable ResultSet/CONCUR_UPDATABLE})

(def ^{:private true
       :doc "Map friendly :cursors values to ResultSet constants."}
  result-set-holdability
  {:hold ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close ResultSet/CLOSE_CURSORS_AT_COMMIT})

(def ^{:private true
       :doc "Map friendly :type values to ResultSet constants."}
  result-set-type
  {:forward-only ResultSet/TYPE_FORWARD_ONLY
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive ResultSet/TYPE_SCROLL_SENSITIVE})

(defn- ^{:tag (class (into-array String []))} string-array
  [return-keys]
  (into-array String return-keys))

(defn- pre-prepare*
  "Given a some options, return a statement factory -- a function that will
  accept a connection and a SQL string and parameters, and return a
  PreparedStatement representing that."
  [{:keys [return-keys result-type concurrency cursors
           fetch-size max-rows timeout]}]
  (cond->
    (cond
     return-keys
     (do
       (when (or result-type concurrency cursors)
         (throw (IllegalArgumentException.
                 (str ":concurrency, :cursors, and :result-type "
                      "may not be specified with :return-keys."))))
       (if (vector? return-keys)
         (let [key-names (string-array return-keys)]
           (fn [^Connection con ^String sql]
             (try
               (try
                 (.prepareStatement con sql key-names)
                 (catch Exception _
                   ;; assume it is unsupported and try regular generated keys:
                   (.prepareStatement con sql java.sql.Statement/RETURN_GENERATED_KEYS)))
               (catch Exception _
                 ;; assume it is unsupported and try basic PreparedStatement:
                 (.prepareStatement con sql)))))
         (fn [^Connection con ^String sql]
           (try
             (.prepareStatement con sql java.sql.Statement/RETURN_GENERATED_KEYS)
             (catch Exception _
               ;; assume it is unsupported and try basic PreparedStatement:
               (.prepareStatement con sql))))))

     (and result-type concurrency)
     (if cursors
       (fn [^Connection con ^String sql]
         (.prepareStatement con sql
                            (get result-set-type result-type result-type)
                            (get result-set-concurrency concurrency concurrency)
                            (get result-set-holdability cursors cursors)))
       (fn [^Connection con ^String sql]
         (.prepareStatement con sql
                            (get result-set-type result-type result-type)
                            (get result-set-concurrency concurrency concurrency))))

     (or result-type concurrency cursors)
     (throw (IllegalArgumentException.
             (str ":concurrency, :cursors, and :result-type "
                  "may not be specified independently.")))
     :else
     (fn [^Connection con ^String sql]
       (.prepareStatement con sql)))
    fetch-size (as-> f
                     (fn [^Connection con ^String sql]
                       (.setFetchSize ^PreparedStatement (f con sql) fetch-size)))
    max-rows (as-> f
                   (fn [^Connection con ^String sql]
                     (.setMaxRows ^PreparedStatement (f con sql) max-rows)))
    timeout (as-> f
                  (fn [^Connection con ^String sql]
                    (.setQueryTimeout ^PreparedStatement (f con sql) timeout)))))

(defn- prepare-fn*
  "Given a connection, a SQL statement, its parameters, and a statement factory,
  return a PreparedStatement representing that."
  ^PreparedStatement
  [con sql params factory]
  (set-parameters (factory con sql) params))

(def ^:private isolation-levels
  "Transaction isolation levels."
  {:none             Connection/TRANSACTION_NONE
   :read-committed   Connection/TRANSACTION_READ_COMMITTED
   :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED
   :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
   :serializable     Connection/TRANSACTION_SERIALIZABLE})

(defn transact*
  ""
  [^Connection con f opts]
  (let [{:keys [isolation read-only? rollback-only?]} opts
        old-autocommit (.getAutoCommit con)
        old-isolation  (.getTransactionIsolation con)
        old-readonly   (.isReadOnly con)]
    (io!
     (when isolation
       (.setTransactionIsolation con (isolation isolation-levels)))
     (when read-only?
       (.setReadOnly con true))
     (.setAutoCommit con false)
     (try
       (let [result (f con)]
         (if rollback-only?
           (.rollback con)
           (.commit con))
         result)
       (catch Throwable t
         (try
           (.rollback con)
           (catch Throwable rb
             ;; combine both exceptions
             (throw (ex-info (str "Rollback failed handling \""
                                  (.getMessage t)
                                  "\"")
                             {:rollback rb
                              :handling t}))))
         (throw t))
       (finally ; tear down
         ;; the following can throw SQLExceptions but we do not
         ;; want those to replace any exception currently being
         ;; handled -- and if the connection got closed, we just
         ;; want to ignore exceptions here anyway
         (try
           (.setAutoCommit con old-autocommit)
           (catch Exception _))
         (when isolation
           (try
             (.setTransactionIsolation con old-isolation)
             (catch Exception _)))
         (when read-only?
           (try
             (.setReadOnly con old-readonly)
             (catch Exception _))))))))

(extend-protocol Transactable
  Connection
  (-transact [this body-fn opts]
             (transact* this body-fn opts))
  DataSource
  (-transact [this body-fn opts]
             (with-open [con (get-connection this opts)]
               (transact* con body-fn opts)))
  Object
  (-transact [this body-fn opts]
             (-transact (get-datasource this) body-fn opts)))

(defmacro with-transaction
  [[sym connectable opts] & body]
  `(-transact ~connectable (fn [~sym] ~@body) ~opts))

(def ^:private classnames
  "Map of subprotocols to classnames. dbtype specifies one of these keys.

  The subprotocols map below provides aliases for dbtype.

  Most databases have just a single class name for their driver but we
  support a sequence of class names to try in order to allow for drivers
  that change their names over time (e.g., MySQL)."
  {"derby"          "org.apache.derby.jdbc.EmbeddedDriver"
   "h2"             "org.h2.Driver"
   "h2:mem"         "org.h2.Driver"
   "hsqldb"         "org.hsqldb.jdbcDriver"
   "jtds:sqlserver" "net.sourceforge.jtds.jdbc.Driver"
   "mysql"          ["com.mysql.cj.jdbc.Driver"
                     "com.mysql.jdbc.Driver"]
   "oracle:oci"     "oracle.jdbc.OracleDriver"
   "oracle:thin"    "oracle.jdbc.OracleDriver"
   "postgresql"     "org.postgresql.Driver"
   "pgsql"          "com.impossibl.postgres.jdbc.PGDriver"
   "redshift"       "com.amazon.redshift.jdbc.Driver"
   "sqlite"         "org.sqlite.JDBC"
   "sqlserver"      "com.microsoft.sqlserver.jdbc.SQLServerDriver"})

(def ^:private aliases
  "Map of schemes to subprotocols. Used to provide aliases for dbtype."
  {"hsql"       "hsqldb"
   "jtds"       "jtds:sqlserver"
   "mssql"      "sqlserver"
   "oracle"     "oracle:thin"
   "oracle:sid" "oracle:thin"
   "postgres"   "postgresql"})

(def ^:private host-prefixes
  "Map of subprotocols to non-standard host-prefixes.
  Anything not listed is assumed to use //."
  {"oracle:oci"  "@"
   "oracle:thin" "@"})

(def ^:private ports
  "Map of subprotocols to ports."
  {"jtds:sqlserver" 1433
   "mysql"          3306
   "oracle:oci"     1521
   "oracle:sid"     1521
   "oracle:thin"    1521
   "postgresql"     5432
   "sqlserver"      1433})

(def ^:private dbname-separators
  "Map of schemes to separators. The default is / but a couple are different."
  {"mssql"      ";DATABASENAME="
   "sqlserver"  ";DATABASENAME="
   "oracle:sid" ":"})

(defn- ^Properties as-properties
  "Convert any seq of pairs to a java.utils.Properties instance.
   Uses as-sql-name to convert both keys and values into strings."
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (name k) (str v)))
    p))

(defn- get-driver-connection
  "Common logic for loading the DriverManager and the designed JDBC driver
  class and obtaining the appropriate Connection object."
  [url etc]
  ;; force DriverManager to be loaded
  (DriverManager/getLoginTimeout)
  (DriverManager/getConnection url (as-properties etc)))

(defn- spec->url+etc
  ""
  [{:keys [dbtype dbname host port classname] :as db-spec}]
  (let [;; allow aliases for dbtype
        subprotocol (aliases dbtype dbtype)
        host (or host "127.0.0.1")
        port (or port (ports subprotocol))
        db-sep (dbname-separators dbtype "/")
        url (cond (= "h2:mem" dbtype)
                  (str "jdbc:" subprotocol ":" dbname ";DB_CLOSE_DELAY=-1")
                  (#{"derby" "h2" "hsqldb" "sqlite"} subprotocol)
                  (str "jdbc:" subprotocol ":" dbname)
                  :else
                  (str "jdbc:" subprotocol ":"
                       (host-prefixes subprotocol "//")
                       host
                       (when port (str ":" port))
                       db-sep dbname))
        etc (dissoc db-spec :dbtype :dbname)]
    ;; verify the datasource is loadable
    (if-let [class-name (or classname (classnames subprotocol))]
      (do
        (if (string? class-name)
          (clojure.lang.RT/loadClassForName class-name)
          (loop [[clazz & more] class-name]
            (when-let [load-failure
                       (try
                         (clojure.lang.RT/loadClassForName clazz)
                         nil
                         (catch Exception e
                           e))]
              (if (seq more)
                (recur more)
                (throw load-failure))))))
      (throw (ex-info (str "Unknown dbtype: " dbtype) db-spec)))
    [url etc]))

(defn- string->url+etc
  ""
  [s]
  [s {}])

(defn- url+etc->datasource
  ""
  [[url etc]]
  (reify DataSource
    (getConnection [_]
                   (get-driver-connection url etc))
    (getConnection [_ username password]
                   (get-driver-connection url
                                          (assoc etc
                                                 :username username
                                                 :password password)))))

(defn- make-connection
  "Given a DataSource and a map of options, get a connection and update it
  as specified by the options."
  ^Connection
  [^DataSource datasource opts]
  (let [^Connection connection (.getConnection datasource)]
    (when (contains? opts :auto-commit?)
      (.setAutoCommit connection (boolean (:auto-commit? opts))))
    (when (contains? opts :read-only?)
      (.setReadOnly connection (boolean (:read-only? opts))))
    connection))

(extend-protocol Sourceable
  clojure.lang.Associative
  (get-datasource [this]
                  (url+etc->datasource (spec->url+etc this)))
  DataSource
  (get-datasource [this] this)
  String
  (get-datasource [this]
                  (url+etc->datasource (string->url+etc this))))

(extend-protocol Connectable
  DataSource
  (get-connection [this opts] (make-connection this opts))
  Object
  (get-connection [this opts] (get-connection (get-datasource this) opts)))

(extend-protocol Preparable
  Connection
  (prepare [this sql-params opts]
           (let [[sql & params] sql-params
                 factory        (pre-prepare* opts)]
             (set-parameters (factory this sql) params))))

(defn- get-column-names
  ""
  [^ResultSet rs]
  (let [^ResultSetMetaData rsmeta (.getMetaData rs)
        idxs (range 1 (inc (.getColumnCount rsmeta)))]
    (mapv (fn [^Integer i]
            (keyword (not-empty (.getTableName rsmeta i))
                     (.getColumnLabel rsmeta i)))
          idxs)))

(defn- mapify-result-set
  "Given a result set, return an object that wraps the current row as a hash
  map. Note that a result set is mutable and the current row will change behind
  this wrapper so operations need to be eager (and fairly limited).

  Supports ILookup (keywords are treated as strings).

  Supports Associative (again, keywords are treated as strings). If you assoc,
  a full row will be realized (via seq/into).

  Supports Seqable which realizes a full row of the data."
  [^ResultSet rs]
  (let [cols (delay (get-column-names rs))]
    (reify

      clojure.lang.ILookup
      (valAt [this k]
             (try
               (.getObject rs (name k))
               (catch SQLException _)))
      (valAt [this k not-found]
             (try
               (.getObject rs (name k))
               (catch SQLException _
                 not-found)))

      clojure.lang.Associative
      (containsKey [this k]
                   (try
                     (.getObject rs (name k))
                     true
                     (catch SQLException _
                       false)))
      (entryAt [this k]
               (try
                 (clojure.lang.MapEntry. k (.getObject rs (name k)))
                 (catch SQLException _)))
      (assoc [this k v]
             (assoc (into {} (seq this)) k v))

      clojure.lang.Seqable
      (seq [this]
           (seq (mapv (fn [^Integer i]
                        (clojure.lang.MapEntry. (nth @cols (dec i))
                                                (.getObject rs i)))
                      (range 1 (inc (count @cols)))))))))

(defn- reduce-stmt
  ""
  [^PreparedStatement stmt f init try-generated-keys?]
  (if-let [^ResultSet rs (if (.execute stmt)
                           (.getResultSet stmt)
                           (when try-generated-keys?
                             (try
                               (.getGeneratedKeys stmt)
                               (catch Exception _))))]
    (let [rs-map (mapify-result-set rs)]
      (loop [init' init]
        (if (.next rs)
          (let [result (f init' rs-map)]
            (if (reduced? result)
              @result
              (recur result)))
          init')))
    (f init {::update-count (.getUpdateCount stmt)})))

(extend-protocol Executable
  Connection
  (-execute [this [sql & params] opts]
            (let [factory (pre-prepare* opts)]
              (reify clojure.lang.IReduceInit
                (reduce [_ f init]
                        (with-open [stmt (prepare-fn* this sql params factory)]
                          (reduce-stmt stmt f init (:return-keys opts)))))))
  DataSource
  (-execute [this [sql & params] opts]
            (let [factory (pre-prepare* opts)]
              (reify clojure.lang.IReduceInit
                (reduce [_ f init]
                        (with-open [con (get-connection this opts)]
                          (with-open [stmt (prepare-fn* con sql params factory)]
                            (reduce-stmt stmt f init (:return-keys opts))))))))
  PreparedStatement
  (-execute [this _ _]
            (reify clojure.lang.IReduceInit
              ;; we can't tell if this PreparedStatement will return generated
              ;; keys so we pass a truthy value to at least attempt it if we
              ;; do not get a ResultSet back from the execute call
              (reduce [_ f init] (reduce-stmt this f init :maybe-keys))))
  Object
  (-execute [this sql-params opts]
            (-execute (get-datasource this) sql-params opts)))

(defn reducible!
  "General SQL execution function.

  Returns a reducible that, when reduced, runs the SQL and yields the result."
  ([stmt] (-execute stmt [] {}))
  ([connectable sql-params & [opts]]
   (-execute connectable sql-params opts)))

(defn query
  ""
  [connectable sql-params & [opts]]
  (into []
        (map (or (:row-fn opts) (partial into {})))
        (execute! connectable sql-params opts)))

(defn query-one
  ""
  [connectable sql-params & [opts]]
  (reduce (fn [_ row] (reduced ((or (:row-fn opts) (partial into {})) row)))
          nil
          (execute! connectable sql-params opts)))

(defn command!
  ""
  [connectable sql-params & [opts]]
  (reduce + 0 (execute! connectable sql-params opts)))

(comment
  (def db-spec {:dbtype "h2:mem" :dbname "perf"})
  (def con db-spec)
  (def con (get-datasource db-spec))
  (get-connection con {})
  (def con (get-connection (get-datasource db-spec) {}))
  (def con (get-connection db-spec {}))
  (command! con ["DROP TABLE fruit"])
  (command! con ["CREATE TABLE fruit (id int default 0, name varchar(32) primary key, appearance varchar(32), cost int, grade real)"])
  (command! con ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (1,'Apple','red',59,87), (2,'Banana','yellow',29,92.2), (3,'Peach','fuzzy',139,90.0), (4,'Orange','juicy',89,88.6)"])

  (println con)
  (.close con)

  (require '[criterium.core :refer [bench quick-bench]])

  ;; calibrate
  (quick-bench (reduce + (take 10e6 (range))))

  ;; raw java
  (defn select* [^Connection con]
    (let [ps (doto (.prepareStatement con "SELECT * FROM fruit WHERE appearance = ?")
               (.setObject 1 "red"))
          rs (.executeQuery ps)
          _ (.next rs)
          value (.getObject rs "name")]
      (.close ps)
      value))
  (quick-bench (select* con))

  ;; almost same as the Java example above
  (quick-bench
   (reduce (fn [rs m] (reduced (:name m)))
           nil
           (execute! con ["select * from fruit where appearance = ?" "red"])))
  (quick-bench
   (query-one con
              ["select * from fruit where appearance = ?" "red"]
              {:row-fn :name}))

  ;; simple query
  (quick-bench
   (query con ["select * from fruit where appearance = ?" "red"]))

  ;; with a prepopulated prepared statement
  (with-open [ps (prepare con ["select * from fruit where appearance = ?" "red"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (execute! ps))]))

  ;; same as above but setting parameters inside the benchmark
  (with-open [ps (prepare con ["select * from fruit where appearance = ?"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (execute! (set-parameters ps ["red"])))]))

  ;; this takes more than twice the time of the one above which seems strange
  (with-open [ps (prepare con ["select * from fruit where appearance = ?"] {})]
    (quick-bench
     [(reduce (fn [_ row] (reduced (:name row)))
              nil
              (execute! (set-parameters ps ["red"])))
      (reduce (fn [_ row] (reduced (:name row)))
              nil
              (execute! (set-parameters ps ["fuzzy"])))]))

  ;; full first row
  (quick-bench
   (query-one con ["select * from fruit where appearance = ?" "red"]))

  ;; test assoc works
  (query-one con
             ["select * from fruit where appearance = ?" "red"]
             {:row-fn #(assoc % :test :value)})
  ;; test assoc works
  (query con
         ["select * from fruit where appearance = ?" "red"]
         {:row-fn #(assoc % :test :value)})

  (in-transaction [t con {:rollback-only? true}]
    (command! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])
    (query t ["select * from fruit where name = ?" "Pear"]))
  (query con ["select * from fruit where name = ?" "Pear"])

  (in-transaction [t con {:rollback-only? false}]
    (command! t ["INSERT INTO fruit (id,name,appearance,cost,grade) VALUES (5,'Pear','green',49,47)"])
    (query t ["select * from fruit where name = ?" "Pear"]))
  (query con ["select * from fruit where name = ?" "Pear"]))
