;; copyright (c) 2019-2025 Sean Corfield, all rights reserved

(ns next.jdbc.transaction-test
  "Stub test namespace for transaction handling."
  (:require [next.jdbc.specs :as specs]
            [next.jdbc.transaction]))

(set! *warn-on-reflection* true)

(specs/instrument)
