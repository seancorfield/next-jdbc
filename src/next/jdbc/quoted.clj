;; copyright (c) 2019-2023 Sean Corfield, all rights reserved

(ns next.jdbc.quoted
  "Provides functions for use with the `:table-fn` and `:column-fn` options
  that define how SQL entities should be quoted in strings constructed
  from Clojure data."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn strop
  "Escape any embedded closing strop characters."
  [s x e]
  (str s (str/replace x (str e) (str e e)) e))

(defn ansi "ANSI \"quoting\"" [s] (strop \" s \"))

(defn mysql "MySQL `quoting`" [s] (strop \` s \`))

(defn sql-server "SQL Server [quoting]" [s] (strop \[ s \]))

(def oracle "Oracle \"quoting\" (ANSI)" ansi)

(def postgres "PostgreSQL \"quoting\" (ANSI)" ansi)

(defn schema
  "Given a quoting function, return a new quoting function that will
  process schema-qualified names by quoting each segment:
```clojure
  (mysql (name :foo.bar)) ;=> `foo.bar`
  ((schema mysql) (name :foo.bar)) ;=> `foo`.`bar`
```
"
  [quoting]
  (fn [s]
    (->> (str/split s #"\.")
         (map quoting)
         (str/join "."))))
