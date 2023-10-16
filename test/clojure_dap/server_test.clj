(ns clojure-dap.server-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.server :as server]))

(t/deftest auto-seq
  (t/testing "starts at 1 and auto increments"
    (let [next-seq (server/auto-seq)]
      (t/is (= 1 (next-seq)))
      (t/is (= 2 (next-seq)))
      (t/is (= 3 (next-seq))))))

(t/deftest handle-client-input
  (t/testing "given an initialize request, responds and emits the initialized event"
    (t/is (= [{:body {:supportsCancelRequest false
                      :supportsConfigurationDoneRequest false}
               :command "initialize"
               :request_seq 1
               :seq 1
               :success true
               :type "response"}
              {:event "initialized", :seq 2, :type "event"}]
             (server/handle-client-input
              {:next-seq (server/auto-seq)
               :input
               {:seq 1
                :type "request"
                :command "initialize"
                :arguments {:adapterID "12345"}}}))))

  (t/testing "given a launch request, it responds with success (a noop)"
    (t/is (= [{:command "launch"
               :request_seq 1
               :seq 1
               :success true
               :type "response"
               :body {}}]
             (server/handle-client-input
              {:next-seq (server/auto-seq)
               :input
               {:seq 1
                :type "request"
                :command "launch"
                :arguments {}}})))))

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
      (t/is (= [{:body {:supportsCancelRequest false
                        :supportsConfigurationDoneRequest false}
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
