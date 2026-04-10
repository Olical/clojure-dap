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
  (t/testing "returns a stopped event when status contains need-debug-input"
    (let [session-id "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"
          result (nrepl-debuggee/handle-init-debugger-output
                  {:status ["need-debug-input"]
                   :session session-id
                   :debug-value "30"
                   :key "some-key"})]
      (t/is (= [{:type "event"
                 :event "stopped"
                 :seq protocol/seq-placeholder
                 :body {:reason "breakpoint"
                        :threadId (hash session-id)}}]
               result))))

  (t/testing "returns nil when status does not contain need-debug-input"
    (t/is (nil? (nrepl-debuggee/handle-init-debugger-output
                 {:status ["done"]
                  :session "some-session"}))))

  (t/testing "returns nil for empty status"
    (t/is (nil? (nrepl-debuggee/handle-init-debugger-output
                 {:status []
                  :session "some-session"})))))

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
          (t/is (fn? (:variables debuggee))))
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

  (t/testing "init-debugger with need-debug-input produces stopped event on output stream"
    (let [server (fake-server/start!
                  {:init-debugger [{:status #{"need-debug-input"}
                                    :debug-value "42"
                                    :key "debug-key"}]})
          output-stream (s/stream 16)]
      (try
        (let [debuggee (nrepl-debuggee/create
                        {:host "127.0.0.1"
                         :port (fake-server/port server)}
                        {:output-stream output-stream})]
          (t/is (not (nom/anomaly? debuggee)))
          ;; The init-debugger thread should produce a stopped event
          ;; The session will be the fake server's session ID
          (let [event (deref (s/take! output-stream) 2000 :timeout)]
            (t/is (match?
                   {:type "event"
                    :event "stopped"
                    :seq protocol/seq-placeholder
                    :body {:reason "breakpoint"
                           :threadId int?}}
                   event))))
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
