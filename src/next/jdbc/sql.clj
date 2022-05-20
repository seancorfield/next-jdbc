;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.sql
  "Some utility functions that make common operations easier by
  providing some syntactic sugar over `execute!`/`execute-one!`.

  This is intended to provide a minimal level of parity with
  `clojure.java.jdbc` (`insert!`, `insert-multi!`, `query`, `find-by-keys`,
  `get-by-id`, `update!`, and `delete!`).

  For anything more complex, use a library like HoneySQL
  https://github.com/seancorfield/honeysql to generate SQL + parameters.

  The following options are supported:
  * `:table-fn` -- specify a function used to convert table names (strings)
      to SQL entity names -- see the `next.jdbc.quoted` namespace for the
      most common quoting strategy functions,
  * `:column-fn` -- specify a function used to convert column names (strings)
      to SQL entity names -- see the `next.jdbc.quoted` namespace for the
      most common quoting strategy functions.

  In addition, `find-by-keys` supports `:order-by` to add an `ORDER BY`
  clause to the generated SQL."
  (:require [next.jdbc :refer [execute! execute-one! execute-batch!]]
            [next.jdbc.sql.builder
             :refer [for-delete for-insert for-insert-multi
                     for-query for-update]]))

(set! *warn-on-reflection* true)

(defn insert!
  "Syntactic sugar over `execute-one!` to make inserting hash maps easier.

  Given a connectable object, a table name, and a data hash map, inserts the
  data as a single row in the database and attempts to return a map of generated
  keys."
  ([connectable table key-map]
   (insert! connectable table key-map {}))
  ([connectable table key-map opts]
   (let [opts (merge (:options connectable) opts)]
     (execute-one! connectable
                   (for-insert table key-map opts)
                   (merge {:return-keys true} opts)))))

(defn insert-multi!
  "Syntactic sugar over `execute!` to make inserting columns/rows easier.

  Given a connectable object, a table name, a sequence of column names, and
  a vector of rows of data (vectors of column values), inserts the data as
  multiple rows in the database and attempts to return a vector of maps of
  generated keys.

  Also supports a sequence of hash maps with keys corresponding to column
  names.

  If called with `:batch` true will call `execute-batch!` - see its documentation
  for situations in which the generated keys may or may not be returned as well as
  additional options that can be passed.

  Note: without `:batch` this expands to a single SQL statement with placeholders for
  every value being inserted -- for large sets of rows, this may exceed the limits
  on SQL string size and/or number of parameters for your JDBC driver or your
  database!"
  {:arglists '([connectable table hash-maps]
               [connectable table hash-maps opts]
               [connectable table cols rows]
               [connectable table cols rows opts])}
  ([connectable table hash-maps]
   (insert-multi! connectable table hash-maps {}))
  ([connectable table hash-maps-or-cols opts-or-rows]
   (if-not (-> hash-maps-or-cols first map?)
     (insert-multi! connectable table hash-maps-or-cols opts-or-rows {})
     (let [cols  (keys (first hash-maps-or-cols))
           ->row (fn ->row [m]
                   (map (partial get m) cols))]
       (insert-multi! connectable table cols (map ->row hash-maps-or-cols) opts-or-rows))))
  ([connectable table cols rows opts]
   (if (seq rows)
     (let [opts   (merge (:options connectable) opts)
           batch? (:batch opts)]
       (if batch?
         (let [[sql & param-groups] (for-insert-multi table cols rows opts)]
              (execute-batch! connectable sql param-groups
                     (merge {:return-keys true :return-generated-keys true} opts)))
         (execute! connectable
                   (for-insert-multi table cols rows opts)
                   (merge {:return-keys true} opts))))
     [])))

(defn query
  "Syntactic sugar over `execute!` to provide a query alias.

  Given a connectable object, and a vector of SQL and its parameters,
  returns a vector of hash maps of rows that match."
  ([connectable sql-params]
   (query connectable sql-params {}))
  ([connectable sql-params opts]
   (let [opts (merge (:options connectable) opts)]
     (execute! connectable sql-params opts))))

(defn find-by-keys
  "Syntactic sugar over `execute!` to make certain common queries easier.

  Given a connectable object, a table name, and either a hash map of
  columns and values to search on or a vector of a SQL where clause and
  parameters, returns a vector of hash maps of rows that match.

  If `:all` is passed instead of a hash map or vector -- the query will
  select all rows in the table, subject to any pagination options below.

  If `:columns` is passed, only that specified subset of columns will be
  returned in each row (otherwise all columns are selected).

  If the `:order-by` option is present, add an `ORDER BY` clause. `:order-by`
  should be a vector of column names or pairs of column name / direction,
  which can be `:asc` or `:desc`.

  If the `:top` option is present, the SQL Server `SELECT TOP ?` syntax
  is used and the value of the option is inserted as an additional parameter.

  If the `:limit` option is present, the MySQL `LIMIT ? OFFSET ?` syntax
  is used (using the `:offset` option if present, else `OFFSET ?` is omitted).
  PostgreSQL also supports this syntax.

  If the `:offset` option is present (without `:limit`), the standard
  `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` syntax is used (using the `:fetch`
  option if present, else `FETCH...` is omitted)."
  ([connectable table key-map]
   (find-by-keys connectable table key-map {}))
  ([connectable table key-map opts]
   (let [opts (merge (:options connectable) opts)]
     (execute! connectable (for-query table key-map opts) opts))))

(defn get-by-id
  "Syntactic sugar over `execute-one!` to make certain common queries easier.

  Given a connectable object, a table name, and a primary key value, returns
  a hash map of the first row that matches.

  By default, the primary key is assumed to be `id` but that can be overridden
  in the five-argument call.

  As with `find-by-keys`, you can specify `:columns` to return just a
  subset of the columns in the returned row.

  Technically, this also supports `:order-by`, `:top`, `:limit`, `:offset`,
  and `:fetch` -- like `find-by-keys` -- but they don't make as much sense
  here since only one row is ever returned."
  ([connectable table pk]
   (get-by-id connectable table pk :id {}))
  ([connectable table pk opts]
   (get-by-id connectable table pk :id opts))
  ([connectable table pk pk-name opts]
   (let [opts (merge (:options connectable) opts)]
     (execute-one! connectable (for-query table {pk-name pk} opts) opts))))

(defn update!
  "Syntactic sugar over `execute-one!` to make certain common updates easier.

  Given a connectable object, a table name, a hash map of columns and values
  to set, and either a hash map of columns and values to search on or a vector
  of a SQL where clause and parameters, perform an update on the table."
  ([connectable table key-map where-params]
   (update! connectable table key-map where-params {}))
  ([connectable table key-map where-params opts]
   (let [opts (merge (:options connectable) opts)]
     (execute-one! connectable
                   (for-update table key-map where-params opts)
                   opts))))

(defn delete!
  "Syntactic sugar over `execute-one!` to make certain common deletes easier.

  Given a connectable object, a table name, and either a hash map of columns
  and values to search on or a vector of a SQL where clause and parameters,
  perform a delete on the table."
  ([connectable table where-params]
   (delete! connectable table where-params {}))
  ([connectable table where-params opts]
   (let [opts (merge (:options connectable) opts)]
     (execute-one! connectable (for-delete table where-params opts) opts))))
