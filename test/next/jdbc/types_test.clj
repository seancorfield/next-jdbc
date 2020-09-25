;; copyright (c) 2020 Sean Corfield, all rights reserved

(ns next.jdbc.types-test
  "Some tests for the type-assist functions."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc.types :refer [as-varchar]]))

(set! *warn-on-reflection* true)

(deftest as-varchar-test
  (let [v (as-varchar "Hello")]
    (is (= "Hello" (v)))
    (is (contains? (meta v) 'next.jdbc.prepare/set-parameter))
    (is (fn? (get (meta v) 'next.jdbc.prepare/set-parameter)))))
