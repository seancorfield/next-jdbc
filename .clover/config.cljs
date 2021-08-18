;; ~/.config/clover/config.cljs
;; for running in GitPod, we don't tap> values:

(defn- wrap-in-tap [code] code)

(defn tap-top-block []
  (p/let [block (editor/get-top-block)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-block []
  (p/let [block (editor/get-block)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-selection []
  (p/let [block (editor/get-selection)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-def-var []
  (p/let [block (editor/get-selection)]
    (when (seq (:text block))
      (-> block
          (update :text
                  #(str "(def " % ")"))
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-var []
  (p/let [block (editor/get-var)]
    (when (seq (:text block))
      (-> block
          (update :text #(str "(or (find-ns '" % ") (resolve '" % "))"))
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-ns []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (-> block
          (update :text #(str "(find-ns '" % ")"))
          (update :text wrap-in-tap)
          (assoc :range (:range here))
          (editor/eval-and-render)))))

(defn- wrap-in-clean-ns
  "Given a string, find the namespace, and clean it up:
  remove its aliases, its refers, and any interns."
  [s]
  (str "(when-let [ns (find-ns '" s ")]"
       " (run! #(try (ns-unalias ns %) (catch Throwable _)) (keys (ns-aliases ns)))"
       " (run! #(try (ns-unmap ns %)   (catch Throwable _)) (keys (ns-interns ns)))"
       " (->> (ns-refers ns)"
       "      (remove (fn [[_ v]] (.startsWith (str v) \"#'clojure.core/\")))"
       "      (map key)"
       "      (run! #(try (ns-unmap ns %) (catch Throwable _)))))"))

(defn tap-remove-ns []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (editor/run-callback
       :notify
       {:type :info :title "Removing..." :message (:text block)})
      (-> block
          (update :text wrap-in-clean-ns)
          (update :text wrap-in-tap)
          (assoc :range (:range here))
          (editor/eval-and-render)))))

(defn tap-reload-all-ns []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (editor/run-callback
       :notify
       {:type :info :title "Reloading all..." :message (:text block)})
      (p/let [res (editor/eval-and-render
                    (-> block
                        (update :text #(str "(require '" % " :reload-all)"))
                        (update :text wrap-in-tap)
                        (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         {:type (if (:error res) :warning :info)
          :title (if (:error res)
                   "Reload failed for..."
                   "Reload succeeded!")
          :message (:text block)})))))

(defn- format-test-result [{:keys [test pass fail error]}]
  (str "Ran " test " test"
       (when-not (= 1 test) "s")
       (when-not (zero? pass)
         (str ", " pass " assertion"
              (when-not (= 1 pass) "s")
              " passed"))
       (when-not (zero? fail)
         (str ", " fail " failed"))
       (when-not (zero? error)
         (str ", " error " errored"))
       "."))

(defn tap-run-current-test []
  (p/let [block (editor/get-top-block)
          test-name (when (seq (:text block))
                      (clojure.string/replace (:text block)
                                              #"\(def[a-z]* ([^\s]*)[^]*"
                                              "$1"))
          here  (editor/get-selection)]
    (when (seq test-name)
      (p/let [res (editor/eval-and-render
                   (-> block
                       (update :text
                               (fn [_]
                                 (str "
                          (with-out-str
                            (binding [clojure.test/*test-out* *out*]
                              (clojure.test/test-vars [#'" test-name "])))")))
                       (update :text wrap-in-tap)
                       (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         (if (:error res)
           {:type :info
            :title "Failed to run tests for"
            :message test-name}
           (try
             (let [s (str (:result res))]
               (if (re-find #"FAIL in" s)
                 {:type :warning
                  :title test-name
                  :message s}
                 {:type :info
                  :title (str test-name " passed")
                  :message (when (seq s) s)}))
             (catch js/Error e
               {:type :warning
                :title "EXCEPTION!"
                :message (ex-message e)}))))))))

(defn tap-run-tests []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (p/let [res (editor/eval-and-render
                   (-> block
                       (update :text (fn [s] (str "
                          (try
                            (let [nt (symbol \"" s "\")]
                              (clojure.test/run-tests nt))
                            (catch Throwable _))")))
                       (update :text wrap-in-tap)
                       (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         {:type (if (:error res) :warning :info)
          :title (if (:error res)
                   "Failed to run tests for..."
                   "Tests completed!")
          :message (if (:error res) (:text block) (format-test-result (:result res)))})))))

(defn tap-run-side-tests []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (p/let [res (editor/eval-and-render
                    (-> block
                        (update :text (fn [s] (str "
                          (some #(try
                                   (let [nt (symbol (str \"" s "\" \"-\" %))]
                                     (require nt)
                                     (clojure.test/run-tests nt))
                                  (catch Throwable _))
                                [\"test\" \"expectations\"])")))
                        (update :text wrap-in-tap)
                        (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         {:type (if (:error res) :warning :info)
          :title (if (:error res)
                   "Failed to run tests for..."
                   "Tests completed!")
          :message (if (:error res) (:text block) (format-test-result (:result res)))})))))

(defn tap-doc-var []
  (p/let [block (editor/get-var)]
    (when (seq (:text block))
      (-> block
          (update :text
                  #(str
                    "(java.net.URL."
                    " (str \"http://clojuredocs.org/\""
                    " (-> (str (symbol #'" % "))"
                    ;; clean up ? ! &
                    "  (clojure.string/replace \"?\" \"%3f\")"
                    "  (clojure.string/replace \"!\" \"%21\")"
                    "  (clojure.string/replace \"&\" \"%26\")"
                    ")))"))
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-javadoc []
  (p/let [block (editor/get-selection)
          block (if (< 1 (count (:text block))) block (editor/get-var))]
      (when (seq (:text block))
        (-> block
            (update :text
                    #(str
                      "(let [c-o-o " %
                      " ^Class c (if (instance? Class c-o-o) c-o-o (class c-o-o))] "
                      " (java.net.URL. "
                      "  (clojure.string/replace"
                      "   ((requiring-resolve 'clojure.java.javadoc/javadoc-url)"
                      "    (.getName c))"
                      ;; strip inner class
                      "   #\"\\$[a-zA-Z0-9_]+\" \"\""
                      ")))"))
            (update :text wrap-in-tap)
            (editor/eval-and-render)))))

(defn- add-libs [deps]
  (str "((requiring-resolve 'clojure.tools.deps.alpha.repl/add-libs) '" deps ")"))

(defn tap-add-libs []
  (p/let [block (editor/get-block)]
    (when (seq (:text block))
      (-> block
          (update :text add-libs)
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))
