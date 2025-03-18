;; copyright (c) 2019-2025 Sean Corfield, all rights reserved

(ns next.jdbc.quoted-test
  "Basic tests for quoting strategies. These are also tested indirectly
  via the next.jdbc.sql tests."
  (:require [lazytest.core :refer [defdescribe describe it expect]]
            [next.jdbc.quoted :refer [ansi mysql sql-server oracle postgres
                                      schema]]))

(set! *warn-on-reflection* true)

(def ^:private quote-fns [ansi mysql sql-server oracle postgres])

(defdescribe quoted-functionality
  (describe "base quoting"
    (it "should correctly quote simple names"
      (doseq [[f e] (map vector quote-fns
                         ["\"x\"" "`x`" "[x]" "\"x\"" "\"x\""])]
        (expect (= (f "x") e)))))
  (describe "dotted name quoting"
    (describe "basic quoting"
      (it "should quote dotted names 'as-is'"
        (doseq [[f e] (map vector quote-fns
                           ["\"x.y\"" "`x.y`" "[x.y]" "\"x.y\"" "\"x.y\""])]
          (expect (= (f "x.y") e)))))
    (describe "schema quoting"
      (it "should split and quote dotted names with schema"
        (doseq [[f e] (map vector quote-fns
                           ["\"x\".\"y\"" "`x`.`y`" "[x].[y]" "\"x\".\"y\"" "\"x\".\"y\""])]
          (expect (= ((schema f) "x.y") e)))))))
