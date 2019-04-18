;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.sql
  "Some utility functions that make common operations easier by
  providing some syntactic sugar over `execute!`/`execute-one!`.

  This is intended to provide a minimal level of parity with clojure.java.jdbc
  (insert!, update!, delete!, etc). For anything more complex, use a library
  like HoneySQL https://github.com/jkk/honeysql to generate SQL + parameters.

  The following options are supported:
  * :table-fn -- specify a function used to convert table names (strings)
      to SQL entity names -- see the next.jdbc.quoted namespace for the
      most common quoting strategy functions,
  * :column-fn -- specify a function used to convert column names (strings)
      to SQL entity names -- see the next.jdbc.quoted namespace for the
      most common quoting strategy functions."
  (:require [clojure.string :as str]
            [next.jdbc :refer [execute! execute-one!]]))

(defn- by-keys
  "Given a hash map of column names and values and a clause type (:set, :where),
  return a vector of a SQL clause and its parameters.

  Applies any :column-fn supplied in the options."
  [key-map clause opts]
  (let [entity-fn      (:column-fn opts identity)
        [where params] (reduce-kv (fn [[conds params] k v]
                                    (let [e (entity-fn (name k))]
                                      (if (and (= :where clause) (nil? v))
                                        [(conj conds (str e " IS NULL")) params]
                                        [(conj conds (str e " = ?")) (conj params v)])))
                                  [[] []]
                                  key-map)]
    (into [(str (str/upper-case (name clause)) " "
                (str/join (if (= :where clause) " AND " ", ") where))]
          params)))

(defn- as-keys
  "Given a hash map of column names and values, return a string of all the
  column names.

  Applies any :column-fn supplied in the options."
  [key-map opts]
  (str/join ", " (map (comp (:column-fn opts identity) name) (keys key-map))))

(defn- as-?
  "Given a hash map of column names and values, or a vector of column names,
  return a string of ? placeholders for them."
  [key-map opts]
  (str/join ", " (repeat (count key-map) "?")))

(defn- for-query
  "Given a table name and either a hash map of column names and values or a
  vector of SQL (where clause) and its parameters, return a vector of the
  full SELECT SQL string and its parameters.

  Applies any :table-fn / :column-fn supplied in the options."
  [table where-params opts]
  (let [entity-fn    (:table-fn opts identity)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (into [(str "SELECT * FROM " (entity-fn (name table))
                " " (first where-params))]
          (rest where-params))))

(defn- for-delete
  "Given a table name and either a hash map of column names and values or a
  vector of SQL (where clause) and its parameters, return a vector of the
  full DELETE SQL string and its parameters.

  Applies any :table-fn / :column-fn supplied in the options."
  [table where-params opts]
  (let [entity-fn    (:table-fn opts identity)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (into [(str "DELETE FROM " (entity-fn (name table))
                " " (first where-params))]
          (rest where-params))))

(defn- for-update
  "Given a table name, a vector of column names to set and their values, and
  either a hash map of column names and values or a vector of SQL (where clause)
  and its parameters, return a vector of the full UPDATE SQL string and its
  parameters.

  Applies any :table-fn / :column-fn supplied in the options."
  [table key-map where-params opts]
  (let [entity-fn    (:table-fn opts identity)
        set-params   (by-keys key-map :set opts)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (-> [(str "UPDATE " (entity-fn (name table))
              " " (first set-params)
              " " (first where-params))]
        (into (rest set-params))
        (into (rest where-params)))))

(defn- for-insert
  "Given a table name and a hash map of column names and their values,
  return a vector of the full INSERT SQL string and its parameters.

  Applies any :table-fn / :column-fn supplied in the options."
  [table key-map opts]
  (let [entity-fn (:table-fn opts identity)
        params    (as-keys key-map opts)
        places    (as-? key-map opts)]
    (into [(str "INSERT INTO " (entity-fn (name table))
                " (" params ")"
                " VALUES (" places ")")]
          (vals key-map))))

(defn- for-insert-multi
  "Given a table name, a vector of column names, and a vector of row values
  (each row is a vector of its values), return a vector of the full INSERT
  SQL string and its parameters.

  Applies any :table-fn / :column-fn supplied in the options."
  [table cols rows opts]
  (assert (apply = (count cols) (map count rows)))
  (let [entity-fn (:table-fn opts identity)
        params    (str/join ", " (map (comp entity-fn name) cols))
        places    (as-? (first rows) opts)]
    (into [(str "INSERT INTO " (entity-fn (name table))
                " (" params ")"
                " VALUES "
                (str/join ", " (repeat (count rows) (str "(" places ")"))))]
          cat
          rows)))

(defn insert!
  "Syntactic sugar over execute-one! to make inserting hash maps easier.

  Given a connectable object, a table name, and a data hash map, inserts the
  data as a single row in the database and attempts to return a map of generated
  keys."
  ([connectable table key-map]
   (insert! connectable table key-map {}))
  ([connectable table key-map opts]
   (execute-one! connectable
                 (for-insert table key-map opts)
                 (merge {:return-keys true} opts))))

(defn insert-multi!
  "Syntactic sugar over execute! to make inserting columns/rows easier.

  Given a connectable object, a table name, a sequence of column names, and
  a vector of rows of data (vectors of column values), inserts the data as
  multiple rows in the database and attempts to return a vector of maps of
  generated keys."
  ([connectable table cols rows]
   (insert-multi! connectable table cols rows {}))
  ([connectable table cols rows opts]
   (execute! connectable
             (for-insert-multi table cols rows opts)
             (merge {:return-keys true} opts))))

(defn query
  "Syntactic sugar over execute! to provide a query alias.

  Given a connectable object, and a vector of SQL and its parameters,
  returns a vector of hash maps of rows that match."
  ([connectable sql-params]
   (query connectable sql-params {}))
  ([connectable sql-params opts]
   (execute! connectable sql-params opts)))

(defn find-by-keys
  "Syntactic sugar over execute! to make certain common queries easier.

  Given a connectable object, a table name, and a hash map of columns and
  their values, returns a vector of hash maps of rows that match."
  ([connectable table key-map]
   (find-by-keys connectable table key-map {}))
  ([connectable table key-map opts]
   (execute! connectable (for-query table key-map opts) opts)))

(defn get-by-id
  "Syntactic sugar over execute-one! to make certain common queries easier.

  Given a connectable object, a table name, and a primary key value, returns
  a hash map of the first row that matches.

  By default, the primary key is assumed to be 'id' but that can be overridden
  in the five-argument call."
  ([connectable table pk]
   (get-by-id connectable table pk :id {}))
  ([connectable table pk opts]
   (get-by-id connectable table pk :id opts))
  ([connectable table pk pk-name opts]
   (execute-one! connectable (for-query table {pk-name pk} opts) opts)))

(defn update!
  "Syntactic sugar over execute-one! to make certain common updates easier.

  Given a connectable object, a table name, a hash map of columns and values
  to set, and either a hash map of columns and values to search on or a vector
  of a SQL where clause and parameters, perform an update on the table."
  ([connectable table key-map where-params]
   (update! connectable table key-map where-params {}))
  ([connectable table key-map where-params opts]
   (execute-one! connectable
                 (for-update table key-map where-params opts)
                 opts)))

(defn delete!
  "Syntactic sugar over execute-one! to make certain common deletes easier.

  Given a connectable object, a table name, and either a hash map of columns
  and values to search on or a vector of a SQL where clause and parameters,
  perform a delete on the table."
  ([connectable table where-params]
   (delete! connectable table where-params {}))
  ([connectable table where-params opts]
   (execute-one! connectable (for-delete table where-params opts) opts)))

(comment
  (require '[next.jdbc.quoted :refer [mysql sql-server]])
  (by-keys {:a nil :b 42 :c "s"} :where {})
  ;=> ["WHERE a IS NULL AND b = ? AND c = ?" 42 "s"]
  (as-keys {:a nil :b 42 :c "s"} {})
  ;=> a, b, c
  (as-? {:a nil :b 42 :c "s"} {})
  ;=> ?, ?, ?
  (for-query :user {:id 9} {:table-fn sql-server :column-fn mysql})
  ;=> ["SELECT * FROM [user] WHERE `id` = ?" 9]
  (for-query :user {:id nil} {:table-fn sql-server :column-fn mysql})
  ;=> ["SELECT * FROM [user] WHERE `id` IS NULL"]
  (for-query :user ["id = ? and opt is null" 9] {:table-fn sql-server :column-fn mysql})
  ;=> ["SELECT * FROM [user] WHERE id = ? and opt is null" 9]
  (for-delete :user {:opt nil :id 9} {:table-fn sql-server :column-fn mysql})
  ;=> ["DELETE FROM [user] WHERE `opt` IS NULL AND `id` = ?" 9]
  (for-delete :user ["id = ? and opt is null" 9] {:table-fn sql-server :column-fn mysql})
  ;=> ["DELETE FROM [user] WHERE id = ? and opt is null" 9]
  (for-update :user {:status 42} {} {:table-fn sql-server :column-fn mysql})
  ;=> ["UPDATE [user] SET `status` = ? WHERE " 42]
  (for-update :user {:status 42} {:id 9} {:table-fn sql-server :column-fn mysql})
  ;=> ["UPDATE [user] SET `status` = ? WHERE `id` = ?" 42 9]
  (for-update :user {:status 42, :opt nil} ["id = ?" 9] {:table-fn sql-server :column-fn mysql})
  ;=> ["UPDATE [user] SET `status` = ?, `opt` = ? WHERE id = ?" 42 nil 9]
  (for-insert :user {:id 9 :status 42 :opt nil} {:table-fn sql-server :column-fn mysql})
  ;=> ["INSERT INTO [user] (`id`, `status`, `opt`) VALUES (?, ?, ?)" 9 42 nil]
  (for-insert-multi :user [:id :status]
                    [[42 "hello"]
                     [35 "world"]
                     [64 "dollars"]]
                    {:table-fn sql-server :column-fn mysql}))
  ;=> ["INSERT INTO [user] (`id`, `status`) VALUES (?, ?), (?, ?), (?, ?)" 42 "hello" 35 "world" 64 "dollars"])
