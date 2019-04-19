;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.quoted-test
  "Basic tests for quoting strategies. These are also tested indirectly
  via the next.jdbc.sql tests."
  (:require [clojure.test :refer [deftest are]]
            [next.jdbc.quoted :refer :all]))

(deftest basic-quoting
  (are [quote-fn quoted] (= (quote-fn "x") quoted)
    ansi       "\"x\""
    mysql      "`x`"
    sql-server "[x]"
    oracle     "\"x\""
    postgres   "\"x\""))
