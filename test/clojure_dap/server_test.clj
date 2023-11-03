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
            [clojure-dap.debuggee.fake :as fake-debuggee]))

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
         :command "attach"
         :arguments {}}])
      (s/close! input-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream
        :debuggee (fake-debuggee/create)})
      (t/is (= [{:body handler/initialised-response-body
                 :command "initialize"
                 :request_seq 1
                 :seq 1
                 :success true
                 :type "response"}
                {:event "initialized", :seq 2, :type "event"}
                {:body {}
                 :command "attach"
                 :request_seq 2
                 :seq 3
                 :success true
                 :type "response"}]
               (vec (s/stream->seq output-stream))))))

  (t/testing "bad inputs or internal errors return errors to the client"
    (with-open [input-stream (s/stream 16)
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
        :output-stream output-stream
        :debuggee (fake-debuggee/create)})
      (t/is (match?
             [{:seq 1
               :request_seq 1
               :type "response"
               :command "initializor"
               :success false
               :message #"Error while handling input"}
              {:type "event"
               :event "output"
               :seq 2
               :body
               {:category "important"
                :output #"Failed to validate against schema :clojure-dap.protocol/message: "
                :data {:seq 2
                       :type "event"
                       :event "unknown event!"}}}]
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
         :command "attach"
         :arguments {}}])
      (s/close! output-stream)
      (server/run
       {:input-stream input-stream
        :output-stream output-stream
        :debuggee (fake-debuggee/create)})
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
                                  :command "attach"
                                  :arguments {:adapterID "12345"}}))))]
      (let [anomalies! (atom [])
            {:keys [server-complete anomalies-stream]}
            (server/run-io-wrapped
             {:input-reader input-reader
              :output-writer output-writer
              :debuggee (fake-debuggee/create)})]

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
                     :command "attach"
                     :type "response"
                     :success true
                     :seq 3
                     :body {}}]))
                 (str output-writer)))
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
              :output-writer output-writer
              :debuggee (fake-debuggee/create)})]

        (s/consume #(swap! anomalies! conj %) anomalies-stream)

        @server-complete

        (t/is (re-find
               #"(?s)Failed to validate against schema :clojure-dap.protocol/message.*\"command\":\"initialize\".*\"event\":\"initialized\""
               (str output-writer)))
        (t/is (= [] @anomalies!))))))
