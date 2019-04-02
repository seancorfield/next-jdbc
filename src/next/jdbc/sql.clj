;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.sql
  "Utilities to construct SQL strings (and lists of parameters) for
  various types of SQL statements.

  This is intended to provide a minimal level of parity with clojure.java.jdbc
  (insert!, update!, delete!, etc). For anything more complex, use a library
  like HoneySQL https://github.com/jkk/honeysql to generate SQL + parameters.

  This is primarily intended to be an implementation detail."
  (:require [clojure.string :as str]))

(defn by-keys
  "Given a hash map of column names and values and a clause type (:set, :where),
  return a vector of a SQL clause and its parameters.

  Applies any :entities function supplied in the options."
  [key-map clause opts]
  (let [entity-fn      (:entities opts identity)
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

(defn as-keys
  "Given a hash map of column names and values, return a string of all the
  column names.

  Applies any :entities function supplied in the options."
  [key-map opts]
  (str/join ", " (map (comp (:entities opts identity) name) (keys key-map))))

(defn as-?
  "Given a hash map of column names and values, or a vector of column names,
  return a string of ? placeholders for them."
  [key-map opts]
  (str/join ", " (repeat (count key-map) "?")))

(defn for-query
  "Given a table name and either a hash map of column names and values or a
  vector of SQL (where clause) and its parameters, return a vector of the
  full SELECT SQL string and its parameters.

  Applies any :entities function supplied in the options."
  [table where-params opts]
  (let [entity-fn    (:entities opts identity)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (into [(str "SELECT * FROM " (entity-fn (name table))
                " " (first where-params))]
          (rest where-params))))

(defn for-delete
  "Given a table name and either a hash map of column names and values or a
  vector of SQL (where clause) and its parameters, return a vector of the
  full DELETE SQL string and its parameters.

  Applies any :entities function supplied in the options."
  [table where-params opts]
  (let [entity-fn    (:entities opts identity)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (into [(str "DELETE FROM " (entity-fn (name table))
                " " (first where-params))]
          (rest where-params))))

(defn for-update
  "Given a table name, a vector of column names to set and their values, and
  either a hash map of column names and values or a vector of SQL (where clause)
  and its parameters, return a vector of the full UPDATE SQL string and its
  parameters.

  Applies any :entities function supplied in the options."
  [table key-map where-params opts]
  (let [entity-fn    (:entities opts identity)
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

(defn for-insert
  "Given a table name and a hash map of column names and their values,
  return a vector of the full INSERT SQL string and its parameters.

  Applies any :entities function supplied in the options."
  [table key-map opts]
  (let [entity-fn (:entities opts identity)
        params    (as-keys key-map opts)
        places    (as-? key-map opts)]
    (into [(str "INSERT INTO " (entity-fn (name table))
                " (" params ")"
                " VALUES (" places ")")]
          (vals key-map))))

(defn for-insert-multi
  "Given a table name, a vector of column names, and a vector of row values
  (each row is a vector of its values), return a vector of the full INSERT
  SQL string and its parameters.

  Applies any :entities function supplied in the options."
  [table cols rows opts]
  (assert (apply = (count cols) (map count rows)))
  (let [entity-fn (:entities opts identity)
        params    (str/join ", " (map (comp entity-fn name) cols))
        places    (as-? (first rows) opts)]
    (into [(str "INSERT INTO " (entity-fn (name table))
                " (" params ")"
                " VALUES "
                (str/join ", " (repeat (count rows) (str "(" places ")"))))]
          cat
          rows)))

(comment
  (require '[next.jdbc.quoted :refer [mysql]])
  (by-keys {:a nil :b 42 :c "s"} :where {})
  ;=> ["WHERE a IS NULL AND b = ? AND c = ?" 42 "s"]
  (as-keys {:a nil :b 42 :c "s"} {})
  ;=> a, b, c
  (as-? {:a nil :b 42 :c "s"} {})
  ;=> ?, ?, ?
  (for-query :user {:id 9} {:entities mysql})
  ;=> ["SELECT * FROM `user` WHERE `id` = ?" 9]
  (for-query :user {:id nil} {:entities mysql})
  ;=> ["SELECT * FROM `user` WHERE `id` IS NULL"]
  (for-query :user ["id = ? and opt is null" 9] {:entities mysql})
  ;=> ["SELECT * FROM `user` WHERE id = ? and opt is null" 9]
  (for-delete :user {:opt nil :id 9} {:entities mysql})
  ;=> ["DELETE FROM `user` WHERE `opt` IS NULL AND `id` = ?" 9]
  (for-delete :user ["id = ? and opt is null" 9] {:entities mysql})
  ;=> ["DELETE FROM `user` WHERE id = ? and opt is null" 9]
  (for-update :user {:status 42} {} {:entities mysql})
  ;=> ["UPDATE `user` SET `status` = ? WHERE " 42]
  (for-update :user {:status 42} {:id 9} {:entities mysql})
  ;=> ["UPDATE `user` SET `status` = ? WHERE `id` = ?" 42 9]
  (for-update :user {:status 42, :opt nil} ["id = ?" 9] {:entities mysql})
  ;=> ["UPDATE `user` SET `status` = ?, `opt` = ? WHERE id = ?" 42 nil 9]
  (for-insert :user {:id 9 :status 42 :opt nil} {:entities mysql})
  ;=> ["INSERT INTO `user` (`id`, `status`, `opt`) VALUES (?, ?, ?)" 9 42 nil]
  (for-insert-multi :user [:id :status]
                    [[42 "hello"]
                     [35 "world"]
                     [64 "dollars"]]
                    {:entities mysql}))
  ;=> ["INSERT INTO `user` (`id`, `status`) VALUES (?, ?), (?, ?), (?, ?)" 42 "hello" 35 "world" 64 "dollars"])
