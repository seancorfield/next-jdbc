;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.prepare-test
  "Stub test namespace for PreparedStatement creation etc.

  This functionality is core to all of the higher-level stuff so it mostly
  gets tested that way, but there should be some dedicated tests in here
  eventually that ensure all of the options specific to PreparedStatement
  actually work they way they're supposed to!"
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc.prepare :refer :all]))

(set! *warn-on-reflection* true)
