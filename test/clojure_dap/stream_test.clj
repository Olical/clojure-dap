(ns clojure-dap.stream-test
  (:require [clojure.test :as t]
            [clojure-dap.stream :as stream]
            [manifold.stream :as s]))

(t/deftest io
  (t/testing "it's a pair of streams, do what you want with them!"
    (let [{:keys [input output]} (stream/io)]
      (t/is (s/stream? input))
      (t/is (s/stream? output)))))

(def example-message
  "Content-Length: 112\r\n\r\n{
    \"seq\": 153,
    \"type\": \"request\",
    \"command\": \"next\",
    \"arguments\": {
        \"threadId\": 3
    }
}")

(t/deftest parse-header
  (t/testing "simple header"
    (t/is (= {:Content-Length 119}
             (stream/parse-header
              "Content-Length: 119\r\n\r\n")))))

(t/deftest read-message
  (t/testing "reads a DAP message from a input-stream"
    (let [{:keys [input _output]} (stream/io)]

      ;; Assumption! Messages are fed in character by character.
      (s/put-all! input (char-array example-message))

      (t/is (= {:seq 153
                :type "request"
                :command "next"
                :arguments {:threadId 3}}
               (stream/read-message input)))))

  (t/testing "returns an anomaly if we get some bad input")
  (t/testing "returns an anomaly if we can read a message but it's malformed")
  (t/testing "returns an anomaly if the stream closes"))
