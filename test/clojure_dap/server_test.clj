(ns clojure-dap.server-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
            [manifold.stream :as s]
            [clojure-dap.server :as server]
            [clojure-dap.server.handler :as handler]
            [clojure-dap.protocol :as protocol]))

(t/deftest auto-seq
  (t/testing "starts at 1 and auto increments"
    (let [next-seq (server/auto-seq)]
      (t/is (= 1 (next-seq)))
      (t/is (= 2 (next-seq)))
      (t/is (= 3 (next-seq))))))

(t/deftest run
  (t/testing "multiple inputs generate multiple outputs"
    (with-open [input-stream (s/stream 16)
                output-stream (s/stream 16)]
      (s/put-all!
       input-stream
       [{:seq 1
         :type "request"
         :command "initialize"
         :arguments {:adapterID "12345"}}
        {:seq 2
         :type "request"
         :command "launch"
         :arguments {}}])
      (s/close! input-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream})
      (t/is (= [{:body handler/initialised-response-body
                 :command "initialize"
                 :request_seq 1
                 :seq 1
                 :success true
                 :type "response"}
                {:event "initialized", :seq 2, :type "event"}
                {:body {}
                 :command "launch"
                 :request_seq 2
                 :seq 3
                 :success true
                 :type "response"}]
               (vec (s/stream->seq output-stream))))))

  (t/testing "bad inputs or internal errors return errors to the client"
    (with-open [input-stream (s/stream 16)
                output-stream (s/stream 16)]
      (s/put!
       input-stream
       {:seq 1
        :type "request"
        :command "initializor"
        :arguments {:adapterID "12345"}})
      (s/close! input-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream})
      (t/is (match?
             [{:seq 1
               :request_seq 1
               :type "response"
               :command "initializor"
               :success false
               :message #"Error while handling input"}]
             (vec (s/stream->seq output-stream))))))

  (t/testing "a closed output closes the input, output remains empty and input is drained"
    (with-open [input-stream (s/stream 16)
                output-stream (s/stream 16)]
      (s/put-all!
       input-stream
       [{:seq 1
         :type "request"
         :command "initialize"
         :arguments {:adapterID "12345"}}
        {:seq 2
         :type "request"
         :command "launch"
         :arguments {}}])
      (s/close! output-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream})
      (t/is (s/closed? input-stream))
      (t/is (s/drained? input-stream))
      (t/is (= [] (vec (s/stream->seq output-stream)))))))

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
                                  :command "launch"
                                  :arguments {:adapterID "12345"}}))))]
      (let [anomalies! (atom [])
            {:keys [server-complete anomalies-stream]}
            (server/run-io-wrapped
             {:input-reader input-reader
              :output-writer output-writer})]

        (s/consume #(swap! anomalies! conj %) anomalies-stream)

        @server-complete

        (t/is (= (str/join
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
                     :command "launch"
                     :type "response"
                     :success true
                     :seq 3
                     :body {}}]))
                 (str output-writer)))
        (t/is (= [] @anomalies!)))))

  (t/testing "bad messages from the client create an anomaly (we don't notify the client directly yet)"
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

        (t/is (= (str/join
                  (map
                   protocol/render-message
                   [{:request_seq 2
                     :command "initialize"
                     :type "response"
                     :success true
                     :seq 1
                     :body handler/initialised-response-body}
                    {:type "event"
                     :event "initialized"
                     :seq 2}]))
                 (str output-writer)))
        (t/is (match? [[:de.otto.nom.core/anomaly
                        :cognitect.anomalies/incorrect
                        {:clojure-dap.schema/explanation
                         {:errors sequential?,
                          :value {:arguments {:adapterID "12345"},
                                  :command "someunknowncommand",
                                  :seq 1,
                                  :type "request"}},
                         :clojure-dap.schema/humanized
                         (m/prefix
                          ["JSON Validation error: #/command: someunknowncommand is not a valid enum value"
                           "3 JSON Validation errors: #: required key [request_seq] not found, #: required key [success] not found, #/type: request is not a valid enum value"
                           "3 JSON Validation errors: #: required key [event] not found, #: required key [event] not found, #/type: request is not a valid enum value"])

                         :cognitect.anomalies/message "Failed to validate against schema :clojure-dap.protocol/message"}]]
                      @anomalies!))))))
