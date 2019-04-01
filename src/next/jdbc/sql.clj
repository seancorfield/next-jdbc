;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.sql
  "Utilities to construct SQL strings (and lists of parameters) for
  various types of SQL statements.

  This is intended to provide a minimal level of parity with clojure.java.jdbc
  (insert!, update!, delete!, etc). For anything more complex, use a library
  like HoneySQL https://github.com/jkk/honeysql to generate SQL + parameters."
  (:require [clojure.string :as str]))

(defn by-keys
  ""
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
  ""
  [key-map opts]
  (str/join ", " (map (comp (:entities opts identity) name) (keys key-map))))

(defn as-?
  ""
  [key-map opts]
  (str/join ", " (repeat (count key-map) "?")))

(defn for-query
  ""
  [table key-map opts]
  (let [entity-fn    (:entities opts identity)
        where-params (by-keys key-map :where opts)]
    (into [(str "SELECT * FROM " (entity-fn (name table))
                " " (first where-params))]
          (rest where-params))))

(defn for-update
  ""
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
  ""
  [table key-map opts]
  (let [entity-fn (:entities opts identity)
        params    (as-keys key-map opts)
        places    (as-? key-map opts)]
    (into [(str "INSERT INTO " (entity-fn (name table))
                " (" params ")"
                " VALUES (" places ")")]
          (vals key-map))))

(comment
  (require '[next.jdbc.quoted :refer [mysql]])
  (by-keys {:a nil :b 42 :c "s"} {})
  (as-keys {:a nil :b 42 :c "s"} {})
  (as-? {:a nil :b 42 :c "s"} {})
  (for-query :user {:id 9} {:entities mysql})
  (for-query :user {:id nil} {:entities mysql})
  (for-update :user {:status 42} {:id 9} {:entities mysql})
  (for-update :user {:status 42} ["id = ?" 9] {:entities mysql})
  (for-insert :user {:id 9 :status 42 :opt nil} {:entities mysql}))
