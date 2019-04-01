;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc
  "The public API of the next generation java.jdbc library.

  The basic building blocks are the java.sql/javax.sql classes:
  * DataSource -- something to get connections from,
  * Connection -- an active connection to the database,
  * PreparedStatement -- SQL and parameters combined, from a connection,
  * reducible! -- given a connectable and SQL + parameters or a statement,
      return a reducible that, when reduced will execute the SQL and consume
      the ResultSet produced,
  * execute! -- given a connectable and SQL + parameters or a statement,
      execute the SQL, consume the ResultSet produced, and return a vector
      of hash maps representing the rows; this can be datafied to allow
      navigation of foreign keys into other tables (either by convention or
      via a schema definition).
  * with-transaction -- execute a series of SQL operations within a transaction.

  In addition, there are some utility functions that make common operations
  easier by providing some syntactic sugar over 'execute!'.

  The following options are supported generally:
  * :entities -- specify a function used to convert strings to SQL entity names
      (to turn table and column names into appropriate SQL names),
  * :identifiers -- specify a function used to convert SQL entity (column)
      names to Clojure names (that are then turned into keywords),
  * :row-fn -- when consuming a ResultSet, apply this function to each row of
      data; defaults to a function that produces a datafiable hash map.

  The following options are supported where a PreparedStatement is created:
  * :concurrency -- :read-only, :updatable,
  * :cursors -- :close, :hold
  * :fetch-size -- the fetch size value,
  * :max-rows -- the maximum number of rows to return,
  * :result-type -- :forward-only, :scroll-insensitive, :scroll-sensitive,
  * :return-keys -- either true or a vector of key names to return,
  * :timeout -- the query timeout."
  (:require [next.jdbc.connection] ; used to extend protocols
            [next.jdbc.prepare :as prepare] ; used to extend protocols
            [next.jdbc.protocols :as p]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [next.jdbc.transaction])) ; used to extend protocols

(set! *warn-on-reflection* true)

(defn get-datasource
  "Given some sort of specification of a database, return a DataSource."
  [spec]
  (p/get-datasource spec))

(defn get-connection
  "Given some sort of specification of a database, return a new Connection.

  In general, this should be used via with-open:

  (with-open [con (get-connection spec opts)]
    (run-some-ops con))"
  [spec opts]
  (p/get-connection spec opts))

(defn prepare
  "Given some sort of specification of a database, and a vector containing
  SQL and any parameters it needs, return a new PreparedStatement.

  In general, this should be used via with-open:

  (with-open [stmt (prepare spec sql-params opts)]
    (run-some-ops stmt))"
  [spec sql-params opts]
  (p/prepare spec sql-params opts))

(defn reducible!
  "General SQL execution function.

  Returns a reducible that, when reduced, runs the SQL and yields the result."
  ([stmt] (p/-execute stmt [] {}))
  ([connectable sql-params & [opts]]
   (p/-execute connectable sql-params opts)))

(defn execute!
  "General SQL execution function.

  Invokes 'reducible!' and then reduces that into a vector of hash maps."
  ([stmt]
   (rs/execute! stmt [] {}))
  ([connectable sql-params]
   (rs/execute! connectable sql-params {}))
  ([connectable sql-params opts]
   (rs/execute! connectable sql-params opts)))

(defn execute-one!
  "General SQL execution function that returns just the first row of a result.

  Invokes 'reducible!' but immediately returns the first row."
  ([stmt]
   (rs/execute-one! stmt [] {}))
  ([connectable sql-params]
   (rs/execute-one! connectable sql-params {}))
  ([connectable sql-params opts]
   (rs/execute-one! connectable sql-params opts)))

(defmacro with-transaction
  "Given a connectable object, gets a new connection and binds it to 'sym',
  then executes the 'body' in that context, committing any changes if the body
  completes successfully, otherwise rolling back any changes made.

  The options map supports:
  * isolation -- :none, :read-committed, :read-uncommitted, :repeatable-read,
      :serializable,
  * :read-only -- true / false,
  * :rollback-only -- true / false."
  [[sym connectable opts] & body]
  `(p/-transact ~connectable (fn [~sym] ~@body) ~opts))

(defn insert!
  "Given a connectable object, a table name, and a data hash map, inserts the
  data as a single row in the database and attempts to return a map of generated
  keys."
  ([connectable table key-map]
   (rs/execute! connectable
                (sql/for-insert table key-map {})
                {:return-keys true}))
  ([connectable table key-map opts]
   (rs/execute! connectable
                (sql/for-insert table key-map opts)
                (merge {:return-keys true} opts))))

(defn insert-multi!
  "Given a connectable object, a table name, a sequence of column names, and
  a vector of rows of data (vectors of column values), inserts the data as
  multiple rows in the database and attempts to return a vector of maps of
  generated keys."
  ([connectable table cols rows]
   (rs/execute! connectable
                (sql/for-insert-multi table cols rows {})
                {:return-keys true}))
  ([connectable table cols rows opts]
   (rs/execute! connectable
                (sql/for-insert-multi table cols rows opts)
                (merge {:return-keys true} opts))))

(defn find-by-keys
  "Given a connectable object, a table name, and a hash map of columns and
  their values, returns a vector of hash maps of rows that match."
  ([connectable table key-map]
   (rs/execute! connectable (sql/for-query table key-map {}) {}))
  ([connectable table key-map opts]
   (rs/execute! connectable (sql/for-query table key-map opts) opts)))

(defn get-by-id
  "Given a connectable object, a table name, and a primary key value, returns
  a hash map of the first row that matches.

  By default, the primary key is assumed to be 'id' but that can be overridden
  in the five-argument call."
  ([connectable table pk]
   (rs/execute-one! connectable (sql/for-query table {:id pk} {}) {}))
  ([connectable table pk opts]
   (rs/execute-one! connectable (sql/for-query table {:id pk} opts) opts))
  ([connectable table pk pk-name opts]
   (rs/execute-one! connectable (sql/for-query table {pk-name pk} opts) opts)))

(defn update!
  "Given a connectable object, a table name, a hash map of columns and values
  to set, and either a hash map of columns and values to search on or a vector
  of a SQL where clause and parameters, perform an update on the table."
  ([connectable table key-map where-params]
   (rs/execute! connectable (sql/for-update table key-map where-params {}) {}))
  ([connectable table key-map where-params opts]
   (rs/execute! connectable (sql/for-update table key-map where-params opts) opts)))

(defn delete!
  "Given a connectable object, a table name, and either a hash map of columns
  and values to search on or a vector of a SQL where clause and parameters,
  perform a delete on the table."
  ([connectable table where-params]
   (rs/execute! connectable (sql/for-delete table where-params {}) {}))
  ([connectable table where-params opts]
   (rs/execute! connectable (sql/for-delete table where-params opts) opts)))
