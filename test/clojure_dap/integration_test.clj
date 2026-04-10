(ns clojure-dap.integration-test
  "End-to-end integration tests that exercise the full DAP session flow
  through server/run-io-wrapped, using a fake nREPL server."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [nrepl.server :as nrepl-server]
            [clojure-dap.server :as server]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.test.fake-nrepl-server :as fake-server]))

(defn- render-messages
  "Render a seq of DAP messages into their wire format string."
  [messages]
  (str/join (map protocol/render-message messages)))

(defn- parse-output
  "Parse all DAP messages from a wire format output string."
  [output-str]
  (when (seq output-str)
    (with-open [stream (s/stream)]
      (s/put-all! stream (char-array output-str))
      (s/close! stream)
      (loop [messages []]
        (let [msg (try
                    (clojure-dap.stream/read-message stream)
                    (catch Exception _e nil))]
          (if (or (nil? msg) (clojure.core/not (map? msg)))
            messages
            (recur (conj messages msg))))))))

(t/deftest full-debug-session
  (t/testing "initialize, attach with fake debuggee, and basic commands"
    (let [nrepl (fake-server/start!
                 {:init-debugger [{:status #{"need-debug-input"}
                                   :debug-value "42"
                                   :key "bp-key-1"
                                   :locals [["x" "10"] ["y" "20"]]
                                   :file "/tmp/test.clj"
                                   :line 5
                                   :column 1
                                   :code "(+ x y)"}]})
          input (render-messages
                 [{:seq 1
                   :type "request"
                   :command "initialize"
                   :arguments {:adapterID "test-client"}}
                  {:seq 2
                   :type "request"
                   :command "attach"
                   :arguments {:clojure_dap {:type "nrepl"
                                             :nrepl {:host "127.0.0.1"
                                                     :port (fake-server/port nrepl)}}}}
                  {:seq 3
                   :type "request"
                   :command "setExceptionBreakpoints"
                   :arguments {:filters []}}
                  {:seq 4
                   :type "request"
                   :command "configurationDone"
                   :arguments {}}
                  {:seq 5
                   :type "request"
                   :command "threads"}])]
      (try
        (with-open [output-writer (java.io.StringWriter.)
                    input-reader (io/reader (.getBytes input))]
          (let [anomalies! (atom [])
                {:keys [server-complete anomalies-stream]}
                (server/run-io-wrapped
                 {:input-reader input-reader
                  :output-writer output-writer})]

            (s/consume #(swap! anomalies! conj %) anomalies-stream)
            @server-complete

            (let [output (str output-writer)]
              ;; Should contain initialize response
              (t/is (re-find #"\"command\":\"initialize\".*\"success\":true" output))

              ;; Should contain attach response
              (t/is (re-find #"\"command\":\"attach\".*\"success\":true" output))

              ;; Should contain setExceptionBreakpoints response
              (t/is (re-find #"\"command\":\"setExceptionBreakpoints\".*\"success\":true" output))

              ;; Should contain configurationDone response
              (t/is (re-find #"\"command\":\"configurationDone\".*\"success\":true" output))

              ;; Should contain threads response
              (t/is (re-find #"\"command\":\"threads\".*\"success\":true" output))

              ;; Should contain initialized event
              (t/is (re-find #"\"event\":\"initialized\"" output))

              ;; Should contain stopped event (from the fake nREPL's need-debug-input)
              (t/is (re-find #"\"reason\":\"breakpoint\".*\"event\":\"stopped\"" output)))

            (t/is (= [] @anomalies!))))
        (finally
          (nrepl-server/stop-server nrepl))))))
