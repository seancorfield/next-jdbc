(ns build
  "next.jdbc's build script.

  clojure -T:build ci
  clojure -T:build deploy

  Run tests via:
  clojure -X:test

  For more information, run:

  clojure -A:deps -T:build help/doc"
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'com.github.seancorfield/next.jdbc)
(def version (format "1.2.%s" (b/git-count-revs nil)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (as-> opts
            (reduce (fn [opts alias]
                      (bb/run-tests (assoc opts :aliases [alias])))
                    opts
                    [:1.10 :master]))
      (bb/clean)
      (bb/jar)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))
