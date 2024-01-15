;; copyright (c) 2018-2024 Sean Corfield, all rights reserved

(ns next.jdbc.types
  "Provides convenience functions for wrapping values you pass into SQL
  operations that have per-instance implementations of `SettableParameter`
  so that `.setObject()` is called with the appropriate `java.sql.Types` value."
  (:require [clojure.string :as str]
            [next.jdbc.prepare])
  (:import (java.lang.reflect Field Modifier)
           (java.sql PreparedStatement)))

(set! *warn-on-reflection* true)

(defmacro ^:private all-types
  []
  (let [names
        (into []
              (comp (filter #(Modifier/isStatic (.getModifiers ^Field %)))
                    (map #(.getName ^Field %)))
              (.getDeclaredFields java.sql.Types))]
    `(do
       ~@(for [n names]
           (let [as-n (symbol (str "as-"
                                   (-> n
                                       (str/lower-case)
                                       (str/replace "_" "-"))))]
             `(defn ~as-n
                ~(str "Wrap a Clojure value in a thunk with metadata to implement `set-parameter`
  so that `.setObject()` is called with the `java.sql.Types/" n "` SQL type.")
                [~'obj]
                (with-meta (constantly ~'obj)
                  {'next.jdbc.prepare/set-parameter
                   (fn [vf# ^PreparedStatement s# ^long i#]
                     (.setObject s# i# (vf#) ~(symbol "java.sql.Types" n)))})))))))

(all-types)

(comment
  (macroexpand '(all-types)))
