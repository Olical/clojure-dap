(ns clojure-dap.stream-test
  (:require [clojure.test :as t]
            [malli.core :as m]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.stream :as stream]))

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

(def invalid-message
  "Content-Length: 111\r\n\r\n{
    \"seq\": 153,
    \"type\": \"reqest\",
    \"command\": \"next\",
    \"arguments\": {
        \"threadId\": 3
    }
}")

(t/deftest parse-header
  (t/testing "simple header"
    (t/is (= {:Content-Length 119}
             (stream/parse-header
              "Content-Length: 119\r\n\r\n"))))

  (t/testing "a bad header returns an anomaly"
    (t/is (match?
           [:de.otto.nom.core/anomaly
            :cognitect.anomalies/incorrect
            {:cognitect.anomalies/message "Failed to parse DAP header"
             ::stream/header "Content-Length: ohno\r\n\r\n"}]
           (stream/parse-header "Content-Length: ohno\r\n\r\n")))))

(t/deftest read-message
  (t/testing "reads a DAP message from a input-stream"
    (let [{:keys [input _output]} (stream/io)]
      (s/put-all! input (char-array example-message))

      (t/is (= {:seq 153
                :type "request"
                :command "next"
                :arguments {:threadId 3}}
               (stream/read-message input)))))

  (t/testing "returns an anomaly if we get some bad input"
    (let [{:keys [input _output]} (stream/io)]
      (s/put-all! input (char-array "Content-Length: ohno\r\n\r\n"))

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Failed to parse DAP header"}]
             (stream/read-message input))))

    (let [{:keys [input _output]} (stream/io)]
      (s/put-all!
       input
       (char-array
        "Content-Length: 3\r\n\r\n{\"thisisbad\": true}"))

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Failed to parse DAP message JSON"
               ::stream/headers {:Content-Length 3}
               ::stream/body "{\"t"}]
             (stream/read-message input)))))

  (t/testing "returns an anomaly if we can read a message but it's malformed"
    (let [{:keys [input _output]} (stream/io)]
      (s/put-all! input (char-array invalid-message))

      (t/is
       (match?
        [:de.otto.nom.core/anomaly
         :cognitect.anomalies/incorrect
         {:clojure-dap.schema/explanation
          {:errors some?
           :schema m/schema?
           :value {:arguments {:threadId 3}
                   :command "next"
                   :seq 153
                   :type "reqest"}}

          :cognitect.anomalies/message
          "Failed to validate against schema :clojure-dap.schema/message"}]
        (stream/read-message input)))))

  (t/testing "returns an anomaly if the stream closes"
    (let [{:keys [input _output]} (stream/io)]
      (s/close! input)

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Received a non-character while reading the next DAP message. A nil probably means the stream closed."
               ::stream/value nil}]
             (stream/read-message input))))))

(t/deftest render-header
  (t/testing "nil / empty map produces an empty header"
    (t/is (= stream/header-sep (stream/render-header nil)))
    (t/is (= stream/header-sep (stream/render-header {}))))

  (t/testing "we can render content length headers"
    (t/is (= "Content-Length: 123\r\n\r\n" (stream/render-header {:Content-Length 123})))))
