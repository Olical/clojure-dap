(ns clojure-dap.debuggee.nrepl-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [de.otto.nom.core :as nom]
            [manifold.stream :as s]
            [nrepl.server :as nrepl-server]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.schema :as schema]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.nrepl :as nrepl-debuggee]
            [clojure-dap.test.fake-nrepl-server :as fake-server]))

(t/deftest handle-init-debugger-output
  (t/testing "idle -> stopped: returns stopped event and breakpoint state"
    (let [session-id "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"
          message {:status ["need-debug-input"]
                   :session session-id
                   :debug-value "30"
                   :key "some-key"
                   :locals [["a" "10"]]
                   :file "/tmp/test.clj"
                   :line 13
                   :column 1
                   :code "(+ a b)"}
          [new-state events] (nrepl-debuggee/handle-init-debugger-output
                              (nrepl-debuggee/initial-debug-state) message nil nil)]
      (t/is (= :stopped (:state new-state)))
      (t/is (= "some-key" (get-in new-state [:breakpoint :key])))
      (t/is (= [{:type "event"
                 :event "stopped"
                 :seq protocol/seq-placeholder
                 :body {:reason "breakpoint"
                        :threadId (hash session-id)}}]
               events))))

  (t/testing "no state change for non-debug-input messages"
    (let [state (nrepl-debuggee/initial-debug-state)
          [new-state events] (nrepl-debuggee/handle-init-debugger-output
                              state {:status ["done"] :session "s"} nil nil)]
      (t/is (= state new-state))
      (t/is (nil? events))))

  (t/testing "awaiting-eval-result -> stopped: delivers result to promise"
    (let [result-promise (promise)
          state {:state :awaiting-eval-result
                 :breakpoint {:key "old"}
                 :eval-expression "(inc a)"
                 :eval-result! result-promise}
          [new-state events] (nrepl-debuggee/handle-init-debugger-output
                              state
                              {:status ["need-debug-input"]
                               :session "s"
                               :key "new-key"
                               :debug-value "11"
                               :locals [["a" "10"]]}
                              nil nil)]
      (t/is (= :stopped (:state new-state)))
      (t/is (= "new-key" (get-in new-state [:breakpoint :key])))
      (t/is (nil? events))
      (t/is (= {:result "11"} (deref result-promise 100 :timeout))))))

(t/deftest breakpoint-line-test
  (t/testing "single break - adjusts line from form start to break position"
    (t/is (= 4 (nrepl-debuggee/breakpoint-line
                {:line 3
                 :code "(defn add [a b]\n#break   (+ a b))"
                 :coor [3]}))))

  (t/testing "multiple breaks - first coor depth selects first break"
    (t/is (= 4 (nrepl-debuggee/breakpoint-line
                {:line 3
                 :code "(defn foo [a b]\n#break   (let [x (inc a)]\n#break     (+ x b)))"
                 :coor [3]}))))

  (t/testing "multiple breaks - deeper coor selects later break"
    (t/is (= 5 (nrepl-debuggee/breakpoint-line
                {:line 3
                 :code "(defn foo [a b]\n#break   (let [x (inc a)]\n#break     (+ x b)))"
                 :coor [3 2]}))))

  (t/testing "no breaks in code - returns original line"
    (t/is (= 3 (nrepl-debuggee/breakpoint-line
                {:line 3
                 :code "(defn add [a b] (+ a b))"
                 :coor [3]}))))

  (t/testing "nil code - returns original line"
    (t/is (= 5 (nrepl-debuggee/breakpoint-line
                {:line 5 :code nil :coor [3]})))))

(t/deftest create
  (t/testing "connects to an nREPL server and returns a valid debuggee"
    (let [server (fake-server/start!)
          output-stream (s/stream 16)]
      (try
        (let [debuggee (nrepl-debuggee/create
                        {:host "127.0.0.1"
                         :port (fake-server/port server)}
                        {:output-stream output-stream})]
          (t/is (not (nom/anomaly? debuggee)))
          (t/is (nil? (schema/validate ::debuggee/debuggee debuggee)))
          (t/is (fn? (:set-breakpoints debuggee)))
          (t/is (fn? (:evaluate debuggee)))
          (t/is (fn? (:threads debuggee)))
          (t/is (fn? (:stack-trace debuggee)))
          (t/is (fn? (:scopes debuggee)))
          (t/is (fn? (:variables debuggee)))
          (t/is (fn? (:continue debuggee)))
          (t/is (fn? (:next debuggee)))
          (t/is (fn? (:step-in debuggee)))
          (t/is (fn? (:step-out debuggee)))
          (t/is (some? (:breakpoint-state! debuggee))))
        (finally
          (s/close! output-stream)
          (nrepl-server/stop-server server)))))

  (t/testing "returns an anomaly when connection is refused"
    (let [output-stream (s/stream 16)]
      (try
        (let [result (nrepl-debuggee/create
                      {:host "127.0.0.1"
                       :port 1}
                      {:output-stream output-stream})]
          (t/is (nom/anomaly? result)))
        (finally
          (s/close! output-stream)))))

  (t/testing "reads port from .nrepl-port file when no port specified"
    (let [server (fake-server/start!)
          output-stream (s/stream 16)
          tmp-dir (System/getProperty "java.io.tmpdir")
          port-file (java.io.File. tmp-dir ".nrepl-port")]
      (try
        (spit port-file (str (fake-server/port server)))
        (let [debuggee (nrepl-debuggee/create
                        {:root-dir tmp-dir}
                        {:output-stream output-stream})]
          (t/is (not (nom/anomaly? debuggee))))
        (finally
          (.delete port-file)
          (s/close! output-stream)
          (nrepl-server/stop-server server)))))

  (t/testing "init-debugger with need-debug-input stores breakpoint state and emits stopped"
    (let [server (fake-server/start!
                  {:init-debugger [{:status #{"need-debug-input"}
                                    :debug-value "42"
                                    :key "debug-key"
                                    :locals [["x" "10"]]
                                    :file "/tmp/test.clj"
                                    :line 5
                                    :column 1
                                    :code "(+ x 1)"}]})
          output-stream (s/stream 16)]
      (try
        (let [debuggee (nrepl-debuggee/create
                        {:host "127.0.0.1"
                         :port (fake-server/port server)}
                        {:output-stream output-stream})]
          (t/is (not (nom/anomaly? debuggee)))
          (let [event (deref (s/take! output-stream) 2000 :timeout)]
            (t/is (match?
                   {:type "event"
                    :event "stopped"
                    :seq protocol/seq-placeholder
                    :body {:reason "breakpoint"
                           :threadId int?}}
                   event)))
          ;; Breakpoint state should be stored
          (t/is (some? @(:breakpoint-state! debuggee)))
          (t/is (= "debug-key" (:key @(:breakpoint-state! debuggee)))))
        (finally
          (s/close! output-stream)
          (nrepl-server/stop-server server))))))

(defn- with-fake-debuggee
  "Helper: starts a fake nREPL server, creates a debuggee, calls f with it,
  then cleans up."
  ([f] (with-fake-debuggee {} f))
  ([server-opts f]
   (let [server (fake-server/start! server-opts)
         output-stream (s/stream 16)]
     (try
       (let [debuggee (nrepl-debuggee/create
                       {:host "127.0.0.1"
                        :port (fake-server/port server)}
                       {:output-stream output-stream})]
         (f debuggee))
       (finally
         (s/close! output-stream)
         (nrepl-server/stop-server server))))))

(t/deftest evaluate-test
  (t/testing "returns the eval result"
    (with-fake-debuggee
      {:eval-result "42"}
      (fn [debuggee]
        (let [result (debuggee/evaluate debuggee {:expression "(+ 1 2)"})]
          (t/is (not (nom/anomaly? result)))
          (t/is (= "42" (:result result)))))))

  (t/testing "returns error output when eval fails"
    (with-fake-debuggee
      {:eval-error "CompilerException: unable to resolve symbol"}
      (fn [debuggee]
        (let [result (debuggee/evaluate debuggee {:expression "(bad-fn)"})]
          (t/is (not (nom/anomaly? result)))
          (t/is (= "CompilerException: unable to resolve symbol"
                   (:result result))))))))

(t/deftest threads-test
  (t/testing "returns threads from nREPL sessions"
    (with-fake-debuggee
      {:sessions ["session-a" "session-b"]}
      (fn [debuggee]
        (let [result (debuggee/threads debuggee)]
          (t/is (not (nom/anomaly? result)))
          (t/is (= [{:id (hash "session-a") :name "session-a"}
                    {:id (hash "session-b") :name "session-b"}]
                   (:threads result)))))))

  (t/testing "returns threads with single session"
    (with-fake-debuggee
      {:sessions ["only-session"]}
      (fn [debuggee]
        (let [result (debuggee/threads debuggee)]
          (t/is (= [{:id (hash "only-session") :name "only-session"}]
                   (:threads result))))))))

(t/deftest set-breakpoints-test
  (t/testing "instruments source and evals forms via nREPL"
    (let [tmp-file (java.io.File/createTempFile "test" ".clj")]
      (try
        (spit tmp-file "(ns test-ns)\n\n(defn add [a b]\n  (+ a b))\n")
        (with-fake-debuggee
          {:eval-result "nil" :eval-ns "test-ns"}
          (fn [debuggee]
            (let [result (debuggee/set-breakpoints
                          debuggee
                          {:source {:path (str tmp-file)}
                           :breakpoints [{:line 4}]})]
              (t/is (not (nom/anomaly? result)))
              (t/is (= {:breakpoints [{:line 4 :verified true}]}
                       result)))))
        (finally
          (.delete tmp-file)))))

  (t/testing "handles multiple breakpoints"
    (let [tmp-file (java.io.File/createTempFile "test" ".clj")]
      (try
        (spit tmp-file "(ns test-ns)\n\n(defn add [a b]\n  (+ a b))\n\n(defn sub [a b]\n  (- a b))\n")
        (with-fake-debuggee
          {}
          (fn [debuggee]
            (let [result (debuggee/set-breakpoints
                          debuggee
                          {:source {:path (str tmp-file)}
                           :breakpoints [{:line 4} {:line 7}]})]
              (t/is (not (nom/anomaly? result)))
              (t/is (= {:breakpoints [{:line 4 :verified true}
                                      {:line 7 :verified true}]}
                       result)))))
        (finally
          (.delete tmp-file)))))

  (t/testing "returns anomaly when source file doesn't exist"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (let [result (debuggee/set-breakpoints
                      debuggee
                      {:source {:path "/nonexistent/file.clj"}
                       :breakpoints [{:line 1}]})]
          (t/is (nom/anomaly? result)))))))

(t/deftest stack-trace-test
  (t/testing "returns breakpoint location as stack frame"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (reset! (:breakpoint-state! debuggee)
                {:key "k" :file "/tmp/test.clj" :line 13 :column 1
                 :code "(defn foo [a b] (+ a b))" :original-ns "my.ns" :locals []})
        (let [result (debuggee/stack-trace debuggee {:thread-id 1})]
          (t/is (= {:stackFrames [{:id 1
                                   :name "my.ns/foo"
                                   :source {:path "/tmp/test.clj"}
                                   :line 13
                                   :column 1}]
                    :totalFrames 1}
                   result))))))

  (t/testing "returns empty when no breakpoint active"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (let [result (debuggee/stack-trace debuggee {:thread-id 1})]
          (t/is (= {:stackFrames [] :totalFrames 0} result)))))))

(t/deftest scopes-test
  (t/testing "returns locals scope when breakpoint active"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (reset! (:breakpoint-state! debuggee)
                {:key "k" :locals [["a" "10"]]})
        (let [result (debuggee/scopes debuggee {:frame-id 1})]
          (t/is (= {:scopes [{:name "Locals"
                              :variablesReference 1
                              :expensive false}]}
                   result))))))

  (t/testing "returns empty when no breakpoint active"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (let [result (debuggee/scopes debuggee {:frame-id 1})]
          (t/is (= {:scopes []} result)))))))

(t/deftest variables-test
  (t/testing "returns locals as variables"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (reset! (:breakpoint-state! debuggee)
                {:key "k" :locals [["a" "10"] ["b" "20"]]})
        (let [result (debuggee/variables debuggee {:variables-reference 1})]
          (t/is (= {:variables [{:name "a" :value "10" :variablesReference 0}
                                {:name "b" :value "20" :variablesReference 0}]}
                   result))))))

  (t/testing "returns empty when no breakpoint active"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (let [result (debuggee/variables debuggee {:variables-reference 1})]
          (t/is (= {:variables []} result)))))))

(t/deftest continue-test
  (t/testing "clears breakpoint state"
    (with-fake-debuggee
      {}
      (fn [debuggee]
        (reset! (:breakpoint-state! debuggee)
                {:key "k" :locals []})
        (let [result (debuggee/continue debuggee {:thread-id 1})]
          (t/is (not (nom/anomaly? result)))
          (t/is (nil? @(:breakpoint-state! debuggee))))))))
