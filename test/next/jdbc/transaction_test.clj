;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.transaction-test
  "Stub test namespace for transaction handling."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.specs :as specs]
            [next.jdbc.test-fixtures :refer [with-test-db db ds column
                                              default-options
                                              derby? mssql? mysql? postgres?]]
            [next.jdbc.transaction :as tx]))

(set! *warn-on-reflection* true)

(use-fixtures :once with-test-db)

(specs/instrument)
