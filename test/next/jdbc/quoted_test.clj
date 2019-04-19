;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.quoted-test
  (:require [clojure.test :refer [deftest are]]
            [next.jdbc.quoted :refer :all]))

(deftest basic-quoting
  (are [quote-fn quoted] (= (quote-fn "x") quoted)
    ansi       "\"x\""
    mysql      "`x`"
    sql-server "[x]"
    oracle     "\"x\""
    postgres   "\"x\""))
