;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.result-set-test
  "Stub test namespace for the result set functions.

  There's so much that should be tested here:
  * column name generation functions
  * ReadableColumn protocol extension point
  * RowBuilder and ResultSetBuilder machinery
  * datafy/nav support
  * ResultSet-as-map for reducible! / -execute protocol
  * -execute-one and -execute-all implementations"
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc.result-set :refer :all]))
