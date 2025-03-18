;; copyright (c) 2020-2025 Sean Corfield, all rights reserved

(ns next.jdbc.types-test
  "Some tests for the type-assist functions."
  (:require [lazytest.core :refer [defdescribe describe it expect]]
            [next.jdbc.types :refer [as-varchar]]))

(set! *warn-on-reflection* true)

(defdescribe as-varchar-tests
  (let [v (as-varchar "Hello")]
    (describe "produces a function"
      (it "yields the original value when invoked"
        (expect (fn? v))
        (expect (= "Hello" (v)))))
    (describe "carries metadata"
      (it "has a `set-parameter` function"
        (expect (contains? (meta v) 'next.jdbc.prepare/set-parameter))
        (expect (fn? (get (meta v) 'next.jdbc.prepare/set-parameter)))))))
