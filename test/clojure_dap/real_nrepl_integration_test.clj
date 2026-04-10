(ns clojure-dap.real-nrepl-integration-test
  "Integration tests against a real CIDER nREPL server.
  These tests exercise the actual debug protocol and log all messages
  for protocol reference. See test output for the raw nREPL conversation."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [matcher-combinators.test]
            [de.otto.nom.core :as nom]
            [manifold.stream :as s]
            [taoensso.telemere :as tel]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.nrepl :as nrepl-debuggee]
            [clojure-dap.test.real-nrepl-server :as real-server]))

(defn- with-real-debuggee
  "Start a real CIDER nREPL server, create a debuggee connected to it,
  call (f debuggee server-port output-stream), then clean up.
  The output-stream receives DAP events (e.g. stopped)."
  [f]
  (let [server (real-server/start!)
        server-port (real-server/port server)
        output-stream (s/stream 64)]
    (tel/log! :info ["Started real CIDER nREPL on port" server-port])
    (try
      (let [debuggee (nrepl-debuggee/create
                      {:host "127.0.0.1"
                       :port server-port}
                      {:output-stream output-stream})]
        (when (nom/anomaly? debuggee)
          (throw (ex-info "Failed to create debuggee" {:anomaly debuggee})))
        (tel/log! :info "Debuggee created successfully")
        (f debuggee server-port output-stream))
      (finally
        (s/close! output-stream)
        (real-server/stop! server)
        (tel/log! :info "Real nREPL server stopped")))))

(defn- take-event!
  "Take a DAP event from the output stream with a timeout."
  [output-stream & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (deref (s/take! output-stream) timeout-ms :timeout))

(t/deftest connect-and-evaluate
  (t/testing "can connect to a real CIDER nREPL and evaluate expressions"
    (with-real-debuggee
      (fn [debuggee server-port _output-stream]
        ;; Define something via direct nREPL eval (in one expression)
        (real-server/eval! server-port "(ns test.integration) (def test-value 42)")

        ;; Now evaluate through the debuggee
        (let [result (debuggee/evaluate debuggee {:expression "(+ 1 2)"})]
          (tel/log! :info ["evaluate result:" result])
          (t/is (not (nom/anomaly? result)))
          (t/is (= "3" (:result result))))))))

(t/deftest set-breakpoints-and-hit
  (t/testing "can set breakpoints, trigger them, inspect state, and continue"
    (let [;; Write a temp source file we can set breakpoints on
          tmp-file (java.io.File/createTempFile "integration-test" ".clj")]
      (try
        (spit tmp-file "(ns test.bp-integration)\n\n(defn add [a b]\n  (+ a b))\n")
        (with-real-debuggee
          (fn [debuggee server-port output-stream]
            ;; Set breakpoints on line 4 (the (+ a b) expression)
            (let [bp-result (debuggee/set-breakpoints
                             debuggee
                             {:source {:path (str tmp-file)}
                              :breakpoints [{:line 4}]})]
              (tel/log! :info ["set-breakpoints result:" bp-result])
              (t/is (not (nom/anomaly? bp-result)))
              (t/is (= [{:line 4 :verified true}] (:breakpoints bp-result))))

            ;; Trigger the breakpoint by calling the function from a separate eval
            ;; This will block the eval thread until we continue
            (tel/log! :info "Triggering breakpoint via async eval...")
            (let [eval-future (real-server/eval-async!
                               server-port
                               "(test.bp-integration/add 10 20)")]

              ;; Wait for the stopped event
              (tel/log! :info "Waiting for stopped event...")
              (let [event (take-event! output-stream :timeout-ms 10000)]
                (tel/log! :info ["Received event:" event])
                (t/is (not= :timeout event) "Should receive a stopped event")
                (when (map? event)
                  (t/is (= "stopped" (:event event)))
                  (t/is (= "breakpoint" (get-in event [:body :reason]))))

                ;; Now query stack trace
                (let [st (debuggee/stack-trace debuggee {:thread-id 1})]
                  (tel/log! :info ["stack-trace result:" st])
                  (t/is (not (nom/anomaly? st)))
                  (t/is (= 1 (:totalFrames st)))
                  (t/is (= (str tmp-file) (get-in st [:stackFrames 0 :source :path])))
                  ;; Should report line 4 (where the #break is), not line 3 (form start)
                  (t/is (= 4 (get-in st [:stackFrames 0 :line])))
                  ;; Frame name should be namespace/function-name
                  (t/is (= "test.bp-integration/add" (get-in st [:stackFrames 0 :name]))))

                ;; Query scopes
                (let [sc (debuggee/scopes debuggee {:frame-id 1})]
                  (tel/log! :info ["scopes result:" sc])
                  (t/is (not (nom/anomaly? sc)))
                  (t/is (= 1 (count (:scopes sc))))
                  (t/is (= "Locals" (get-in sc [:scopes 0 :name]))))

                ;; Query variables
                (let [vars (debuggee/variables debuggee {:variables-reference 1})]
                  (tel/log! :info ["variables result:" vars])
                  (t/is (not (nom/anomaly? vars)))
                  ;; Should have locals a=10 and b=20
                  (t/is (pos? (count (:variables vars))))
                  (let [var-map (into {} (map (juxt :name :value) (:variables vars)))]
                    (tel/log! :info ["variable map:" var-map])
                    (t/is (= "10" (get var-map "a")))
                    (t/is (= "20" (get var-map "b")))))

                ;; Continue execution
                (tel/log! :info "Sending continue...")
                (let [cont (debuggee/continue debuggee {:thread-id 1})]
                  (tel/log! :info ["continue result:" cont])
                  (t/is (not (nom/anomaly? cont))))

                ;; The eval should now complete
                (tel/log! :info "Waiting for eval to complete...")
                (let [eval-result (deref eval-future 5000 :eval-timeout)]
                  (tel/log! :info ["eval result after continue:" eval-result])
                  (t/is (not= :eval-timeout eval-result)))))))
        (finally
          (.delete tmp-file))))))

(t/deftest evaluate-at-breakpoint
  (t/testing "can evaluate expressions while stopped at a breakpoint"
    (let [tmp-file (java.io.File/createTempFile "eval-bp-test" ".clj")]
      (try
        (spit tmp-file "(ns test.eval-bp)\n\n(defn add [a b]\n  (+ a b))\n")
        (with-real-debuggee
          (fn [debuggee server-port output-stream]
            (debuggee/set-breakpoints
             debuggee
             {:source {:path (str tmp-file)}
              :breakpoints [{:line 4}]})

            (let [eval-future (real-server/eval-async!
                               server-port
                               "(test.eval-bp/add 10 20)")]

              ;; Wait for stopped
              (let [event (take-event! output-stream)]
                (t/is (= "stopped" (:event event))))

              ;; Evaluate an expression while stopped
              ;; This uses the debuggee's nREPL session, not the breakpoint context
              (let [result (debuggee/evaluate debuggee {:expression "(+ 100 200)"})]
                (tel/log! :info ["evaluate at breakpoint:" result])
                (t/is (not (nom/anomaly? result)))
                (t/is (= "300" (:result result))))

              ;; Continue
              (debuggee/continue debuggee {:thread-id 1})
              (deref eval-future 5000 :timeout))))
        (finally
          (.delete tmp-file))))))

(t/deftest evaluate-locals-at-breakpoint
  (t/testing "can evaluate expressions that reference local variables"
    (let [tmp-file (java.io.File/createTempFile "eval-locals-test" ".clj")]
      (try
        (spit tmp-file "(ns test.eval-locals)\n\n(defn add [a b]\n  (+ a b))\n")
        (with-real-debuggee
          (fn [debuggee server-port output-stream]
            (debuggee/set-breakpoints
             debuggee
             {:source {:path (str tmp-file)}
              :breakpoints [{:line 4}]})

            (let [eval-future (real-server/eval-async!
                               server-port
                               "(test.eval-locals/add 10 20)")]

              ;; Wait for stopped
              (let [event (take-event! output-stream)]
                (t/is (= "stopped" (:event event))))

              ;; Evaluate an expression that references local variable 'a'
              (let [result (debuggee/evaluate debuggee {:expression "(inc a)"})]
                (tel/log! :info ["evaluate locals result:" result])
                (t/is (not (nom/anomaly? result)))
                (t/is (= "11" (:result result))))

              ;; Continue
              (debuggee/continue debuggee {:thread-id 1})
              (deref eval-future 5000 :timeout))))
        (finally
          (.delete tmp-file))))))

(t/deftest multiple-breakpoints
  (t/testing "can hit multiple breakpoints in sequence"
    (let [tmp-file (java.io.File/createTempFile "multi-bp-test" ".clj")]
      (try
        (spit tmp-file "(ns test.multi-bp)\n\n(defn step-a [x]\n  (inc x))\n\n(defn step-b [x]\n  (* x 2))\n\n(defn run-both [x]\n  (step-b (step-a x)))\n")
        (with-real-debuggee
          (fn [debuggee server-port output-stream]
            ;; Set breakpoints on both functions
            (let [bp-result (debuggee/set-breakpoints
                             debuggee
                             {:source {:path (str tmp-file)}
                              :breakpoints [{:line 4} {:line 7}]})]
              (t/is (= [{:line 4 :verified true}
                        {:line 7 :verified true}]
                       (:breakpoints bp-result))))

            (let [eval-future (real-server/eval-async!
                               server-port
                               "(test.multi-bp/run-both 5)")]

              ;; First breakpoint: step-a
              (let [event1 (take-event! output-stream)]
                (t/is (= "stopped" (:event event1)))
                (tel/log! :info ["First breakpoint - locals:" (:locals @(:breakpoint-state! debuggee))])
                (let [vars (debuggee/variables debuggee {:variables-reference 1})
                      var-map (into {} (map (juxt :name :value) (:variables vars)))]
                  (t/is (= "5" (get var-map "x")))))

              ;; Continue past first breakpoint
              (debuggee/continue debuggee {:thread-id 1})

              ;; Second breakpoint: step-b
              (let [event2 (take-event! output-stream)]
                (t/is (= "stopped" (:event event2)))
                (tel/log! :info ["Second breakpoint - locals:" (:locals @(:breakpoint-state! debuggee))])
                (let [vars (debuggee/variables debuggee {:variables-reference 1})
                      var-map (into {} (map (juxt :name :value) (:variables vars)))]
                  ;; step-b receives (inc 5) = 6
                  (t/is (= "6" (get var-map "x")))))

              ;; Continue to finish
              (debuggee/continue debuggee {:thread-id 1})

              ;; Eval should complete with (step-b (step-a 5)) = (* (inc 5) 2) = 12
              (let [eval-result (deref eval-future 5000 :eval-timeout)]
                (tel/log! :info ["final result:" eval-result])
                (t/is (not= :eval-timeout eval-result))
                (t/is (= ["12"] (:value eval-result)))))))
        (finally
          (.delete tmp-file))))))

(t/deftest step-next-and-continue
  (t/testing "can step through code with next, then continue"
    (let [tmp-file (java.io.File/createTempFile "step-test" ".clj")]
      (try
        ;; Code with multiple breakable expressions
        (spit tmp-file "(ns test.step-integration)\n\n(defn process [x]\n  (let [a (inc x)\n        b (* a 2)]\n    b))\n")
        (with-real-debuggee
          (fn [debuggee server-port output-stream]
            ;; Set breakpoints on the let body lines
            (let [bp-result (debuggee/set-breakpoints
                             debuggee
                             {:source {:path (str tmp-file)}
                              :breakpoints [{:line 4}]})]
              (t/is (not (nom/anomaly? bp-result))))

            ;; Trigger the breakpoint
            (let [eval-future (real-server/eval-async!
                               server-port
                               "(test.step-integration/process 5)")]

              ;; Wait for first stopped event
              (let [event (take-event! output-stream :timeout-ms 10000)]
                (t/is (not= :timeout event))
                (t/is (= "stopped" (:event event)))
                (tel/log! :info ["First stop - breakpoint state:"
                                 @(:breakpoint-state! debuggee)]))

              ;; Step next
              (tel/log! :info "Stepping next...")
              (let [step-result (debuggee/next-request debuggee {:thread-id 1})]
                (t/is (not (nom/anomaly? step-result))))

              ;; Should get another stopped event after stepping
              ;; (short timeout - stepping may complete the function)
              (let [event2 (take-event! output-stream :timeout-ms 2000)]
                (tel/log! :info ["After step - event:" event2])
                (if (= :timeout event2)
                  ;; Stepping may have completed the function - that's ok
                  (tel/log! :info "No second stop (function completed after step)")
                  (do
                    (t/is (= "stopped" (:event event2)))
                    ;; Continue to finish
                    (debuggee/continue debuggee {:thread-id 1}))))

              ;; Eval should complete
              (let [eval-result (deref eval-future 5000 :eval-timeout)]
                (tel/log! :info ["eval result:" eval-result])
                (t/is (not= :eval-timeout eval-result))))))
        (finally
          (.delete tmp-file))))))

(t/deftest multiple-evals-then-continue
  (t/testing "can evaluate multiple expressions then continue"
    (let [tmp-file (java.io.File/createTempFile "multi-eval-test" ".clj")]
      (try
        (spit tmp-file "(ns test.multi-eval)\n\n(defn calc [x y]\n  (+ x y))\n")
        (with-real-debuggee
          (fn [debuggee server-port output-stream]
            (debuggee/set-breakpoints
             debuggee
             {:source {:path (str tmp-file)}
              :breakpoints [{:line 4}]})

            (let [eval-future (real-server/eval-async!
                               server-port
                               "(test.multi-eval/calc 3 7)")]

              (let [event (take-event! output-stream)]
                (t/is (= "stopped" (:event event))))

              ;; First eval: check x
              (let [result (debuggee/evaluate debuggee {:expression "x"})]
                (t/is (= "3" (:result result))))

              ;; Second eval: check y
              (let [result (debuggee/evaluate debuggee {:expression "y"})]
                (t/is (= "7" (:result result))))

              ;; Third eval: compute something
              (let [result (debuggee/evaluate debuggee {:expression "(* x y)"})]
                (t/is (= "21" (:result result))))

              ;; Continue should still work after multiple evals
              (debuggee/continue debuggee {:thread-id 1})
              (let [eval-result (deref eval-future 5000 :timeout)]
                (t/is (not= :timeout eval-result))
                (t/is (= ["10"] (:value eval-result)))))))
        (finally
          (.delete tmp-file))))))

(t/deftest evaluate-not-at-breakpoint
  (t/testing "evaluate falls back to regular eval when not at a breakpoint"
    (with-real-debuggee
      (fn [debuggee _server-port _output-stream]
        ;; No breakpoint set, just evaluate
        (let [result (debuggee/evaluate debuggee {:expression "(+ 100 200)"})]
          (t/is (not (nom/anomaly? result)))
          (t/is (= "300" (:result result))))))))

(t/deftest evaluate-triggers-breakpoint
  (t/testing "evaluate that hits a breakpoint completes after continue"
    (let [tmp-file (java.io.File/createTempFile "eval-bp-trigger-test" ".clj")]
      (try
        (spit tmp-file "(ns test.eval-bp-trigger)\n\n(defn greet [name]\n  (str \"Hello, \" name))\n")
        (with-real-debuggee
          (fn [debuggee _server-port output-stream]
            (debuggee/set-breakpoints
             debuggee
             {:source {:path (str tmp-file)}
              :breakpoints [{:line 4}]})

            ;; Evaluate the breakpointed function in a future (it will block at the breakpoint)
            (let [eval-future (future (debuggee/evaluate debuggee {:expression "(test.eval-bp-trigger/greet \"world\")"}))]

              ;; The breakpoint should be hit - wait for stopped event
              (let [event (take-event! output-stream)]
                (t/is (= "stopped" (:event event))))

              ;; Continue to let the eval complete
              (debuggee/continue debuggee {:thread-id 1})

              ;; The eval should now complete with the result
              (let [result (deref eval-future 5000 :eval-timeout)]
                (tel/log! :info ["evaluate-triggers-breakpoint result:" result])
                (t/is (not= :eval-timeout result))
                (t/is (not (nom/anomaly? result)))
                (t/is (= "\"Hello, world\"" (:result result)))))))
        (finally
          (.delete tmp-file))))))
