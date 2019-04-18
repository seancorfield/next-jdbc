;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.quoted
  "Provides functions for use with the :table-fn and :column-fn options
  that define how SQL entities should be quoted in strings constructed
  from Clojure data.")

(defn ansi "ANSI \"quoting\"" [s] (str \" s \"))

(defn mysql "MySQL `quoting`" [s] (str \` s \`))

(defn sql-server "SQL Server [quoting]" [s] (str \[ s \]))

(def oracle "Oracle \"quoting\" (ANSI)" ansi)

(def postgres "PostgreSQL \"quoting\" (ANSI)" ansi)
