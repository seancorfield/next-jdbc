;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.quoted-test
  "Basic tests for quoting strategies. These are also tested indirectly
  via the next.jdbc.sql tests."
  (:require [clojure.test :refer [deftest are testing]]
            [next.jdbc.quoted :refer [ansi mysql sql-server oracle postgres
                                      schema]]))

(set! *warn-on-reflection* true)

(deftest basic-quoting
  (are [quote-fn quoted] (= (quote-fn "x") quoted)
    ansi       "\"x\""
    mysql      "`x`"
    sql-server "[x]"
    oracle     "\"x\""
    postgres   "\"x\""))

(deftest schema-quoting
  (testing "verify non-schema behavior"
    (are [quote-fn quoted] (= (quote-fn "x.y") quoted)
      ansi       "\"x.y\""
      mysql      "`x.y`"
      sql-server "[x.y]"
      oracle     "\"x.y\""
      postgres   "\"x.y\""))
  (testing "verify schema behavior"
    (are [quote-fn quoted] (= ((schema quote-fn) "x.y") quoted)
      ansi       "\"x\".\"y\""
      mysql      "`x`.`y`"
      sql-server "[x].[y]"
      oracle     "\"x\".\"y\""
      postgres   "\"x\".\"y\"")))
