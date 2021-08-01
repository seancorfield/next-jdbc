(ns build
  (:require [clojure.tools.build.api :as b]))

(defn run-tests
  [_]
  (let [basis (b/create-basis {:aliases [:test]})
        cmds  (b/java-command {:basis     basis
                               :main      'clojure.main
                               :main-args ["-m" "cognitect.test-runner"]})]
    (b/process cmds)))
