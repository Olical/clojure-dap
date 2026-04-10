(ns clojure-dap.server-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.server :as server]
            [clojure-dap.server.handler :as handler]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.schema :as schema]
            [matcher-combinators.matchers :as m]))

(t/deftest auto-seq
  (t/testing "starts at 1 and auto increments"
    (let [next-seq (server/auto-seq)]
      (t/is (= 1 (next-seq)))
      (t/is (= 2 (next-seq)))
      (t/is (= 3 (next-seq))))))

(defn- take-with-timeout!
  "Take n messages from a stream, with a per-message timeout."
  [stream n & {:keys [timeout-ms] :or {timeout-ms 2000}}]
  (loop [result []
         remaining n]
    (if (zero? remaining)
      result
      (let [msg (deref (s/take! stream) timeout-ms ::timeout)]
        (if (= ::timeout msg)
          result
          (recur (conj result msg) (dec remaining)))))))

(t/deftest run
  (t/testing "multiple inputs generate multiple outputs"
    (let [input-stream (s/stream 16)
          output-stream (s/stream 16)]
      (s/put-all!
       input-stream
       [{:seq 1
         :type "request"
         :command "initialize"
         :arguments {:adapterID "12345"}}
        {:seq 2
         :type "request"
         :command "attach"
         :arguments {:clojure_dap {:type "fake"}}}])
      (s/close! input-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream})
      (t/is (match?
             [{:body handler/initialised-response-body
               :command "initialize"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success true
               :type "response"}
              {:event "initialized"
               :seq protocol/seq-placeholder
               :type "event"}
              {:body {}
               :command "attach"
               :request_seq 2
               :seq protocol/seq-placeholder
               :success true
               :type "response"}]
             (take-with-timeout! output-stream 3)))))

  (t/testing "bad inputs or internal errors return errors to the client"
    (let [input-stream (s/stream 16)
          output-stream (s/stream 16)]
      (s/put-all!
       input-stream
       [{:seq 1
         :type "request"
         :command "initializor"
         :arguments {:adapterID "12345"}}
        (schema/validate
         ::protocol/message
         {:seq 2
          :type "event"
          :event "unknown event!"})])
      (s/close! input-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream})
      (t/is (match?
             (m/in-any-order
              [{:seq protocol/seq-placeholder
                :request_seq 1
                :type "response"
                :command "initializor"
                :success false
                :message #"Error while handling input"}
               {:type "event"
                :event "output"
                :seq protocol/seq-placeholder
                :body
                {:category "important"
                 :output #"Failed to validate against schema :clojure-dap.protocol/message: "
                 :data {:seq 2
                        :type "event"
                        :event "unknown event!"}}}])
             (take-with-timeout! output-stream 2)))))

  (t/testing "a pre-closed output does not throw"
    (let [input-stream (s/stream 16)
          output-stream (s/stream 16)]
      (s/put-all!
       input-stream
       [{:seq 1
         :type "request"
         :command "initialize"
         :arguments {:adapterID "12345"}}])
      (s/close! input-stream)
      (s/close! output-stream)
      ;; Should not throw - futures will fail to put-all! but that's handled gracefully
      (t/is (some? (server/run
                    {:input-stream input-stream
                     :output-stream output-stream}))))))

(t/deftest run-io-wrapped
  (t/testing "responds to an initialize request appropriately"
    (with-open [output-writer (java.io.StringWriter.)
                input-reader (io/reader
                              (.getBytes
                               (str
                                (protocol/render-message
                                 {:seq 1
                                  :type "request"
                                  :command "initialize"
                                  :arguments {:adapterID "12345"}})
                                (protocol/render-message
                                 {:seq 2
                                  :type "request"
                                  :command "attach"
                                  :arguments {:clojure_dap {:type "fake"}}}))))]
      (let [anomalies! (atom [])
            {:keys [server-complete anomalies-stream]}
            (server/run-io-wrapped
             {:input-reader input-reader
              :output-writer output-writer})]

        (s/consume #(swap! anomalies! conj %) anomalies-stream)

        @server-complete

        (t/is (str/starts-with?
               (str output-writer)
               (str/join
                (map
                 protocol/render-message
                 [{:request_seq 1
                   :command "initialize"
                   :type "response"
                   :success true
                   :seq 1
                   :body handler/initialised-response-body}
                  {:type "event"
                   :event "initialized"
                   :seq 2}
                  {:request_seq 2
                   :command "attach"
                   :type "response"
                   :success true
                   :seq 3
                   :body {}}]))))
        (t/is (= [] @anomalies!)))))

  (t/testing "bad or unknown messages from the client result in error output"
    (with-open [output-writer (java.io.StringWriter.)
                input-reader (io/reader
                              (.getBytes
                               (str
                                "Content-Length: 91\r\n\r\n{\"arguments\":{\"adapterID\":\"12345\"},\"command\":\"someunknowncommand\",\"seq\":1,\"type\":\"request\"}"
                                (protocol/render-message
                                 {:seq 2
                                  :type "request"
                                  :command "initialize"
                                  :arguments {:adapterID "12345"}}))))]
      (let [anomalies! (atom [])
            {:keys [server-complete anomalies-stream]}
            (server/run-io-wrapped
             {:input-reader input-reader
              :output-writer output-writer})]

        (s/consume #(swap! anomalies! conj %) anomalies-stream)

        @server-complete

        ;; Both the error for the unknown command and the valid initialize response should appear
        (let [output (str output-writer)]
          (t/is (re-find #"someunknowncommand" output))
          (t/is (re-find #"\"command\":\"initialize\"" output)))
        (t/is (= [] @anomalies!))))))
