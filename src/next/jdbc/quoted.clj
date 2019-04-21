;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.quoted
  "Provides functions for use with the `:table-fn` and `:column-fn` options
  that define how SQL entities should be quoted in strings constructed
  from Clojure data."
  (:require [clojure.string :as str]))

(defn ansi "ANSI \"quoting\"" [s] (str \" s \"))

(defn mysql "MySQL `quoting`" [s] (str \` s \`))

(defn sql-server "SQL Server [quoting]" [s] (str \[ s \]))

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
