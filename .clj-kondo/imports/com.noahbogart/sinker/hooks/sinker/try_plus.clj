(ns hooks.sinker.try-plus
  (:require
    [clj-kondo.hooks-api :as api]))

(defn parse-exprs
  [clause]
  (if (and (api/list-node? clause) (= 'catch (api/sexpr (first (:children clause)))))
    (let [[catch-token pred id & body] (:children clause)]
      (with-meta
        #_{:clj-kondo/ignore [:discouraged-var]}
        (cond
          (nil? pred)
          (api/list-node
            (list* catch-token pred body))
          (or (api/keyword-node? pred)
            (symbol? (api/sexpr pred))
            (= :var (:tag pred)))
          (api/list-node
            (list* catch-token (api/token-node 'clojure.lang.ExceptionInfo) id
              (cons pred body)))
          :else
          clause)
        (meta clause)))
    clause))

(defn try-plus
  [{:keys [node]}]
  (let [[_try+ & children] (:children node)
        body (mapv parse-exprs children)
        new-node (with-meta
                   (api/list-node (list* (api/token-node 'try) body))
                   (meta node))]
    {:node new-node}))
