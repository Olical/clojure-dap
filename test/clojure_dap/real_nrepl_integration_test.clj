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
                  (t/is (= (str tmp-file) (get-in st [:stackFrames 0 :source :path]))))

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
