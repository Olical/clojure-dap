(ns clojure-dap.protocol-test
  (:require [clojure.test :as t]
            [malli.core :as m]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [jsonista.core :as json]
            [clojure-dap.stream :as stream]
            [clojure-dap.protocol :as protocol]))

(t/deftest parse-header
  (t/testing "simple header"
    (t/is (= {:Content-Length 119}
             (protocol/parse-header
              (str "Content-Length: 119" protocol/double-header-sep)))))

  (t/testing "a bad header returns an anomaly"
    (t/is (match?
           [:de.otto.nom.core/anomaly
            :cognitect.anomalies/incorrect
            {:cognitect.anomalies/message "Failed to parse DAP header"}]
           (protocol/parse-header (str "Content-Length: ohno" protocol/double-header-sep))))))

(t/deftest render-header
  (t/testing "nil / empty map produces an empty header"
    (t/is (= protocol/header-sep (protocol/render-header nil)))
    (t/is (= protocol/header-sep (protocol/render-header {}))))

  (t/testing "we can render content length headers"
    (t/is (= (str "Content-Length: 123" protocol/double-header-sep) (protocol/render-header {:Content-Length 123})))))

(t/deftest render-message
  (t/testing "a simple valid message"
    (t/is (= (str
              "Content-Length: 72"
              protocol/double-header-sep
              "{\"seq\":153,\"type\":\"request\",\"command\":\"next\",\"arguments\":{\"threadId\":3}}")
             (protocol/render-message
              {:seq 153
               :type "request"
               :command "next"
               :arguments {:threadId 3}}))))

  (t/testing "we can round trip through the render and read functions"
    (with-open [stream (s/stream)]
      (let [message {:seq 153
                     :type "request"
                     :command "next"
                     :arguments {:threadId 3}}]
        (s/put-all! stream (char-array (protocol/render-message message)))
        (t/is (match? message (stream/read-message stream))))))

  (t/testing "a bad message returns an anomaly"
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
      (protocol/render-message
       {:arguments {:threadId 3}
        :command "next"
        :seq 153
        :type "reqest"})))))

(t/deftest parse-message
  (t/testing "parses valid JSON messages"
    (let [message {:seq 153
                   :type "request"
                   :command "next"
                   :arguments {:threadId 3}}]
      (t/is (= message (protocol/parse-message (json/write-value-as-string message))))))

  (t/testing "returns an anomaly if the message doesn't validate"
    (let [message {:seq "153"
                   :type "request"
                   :command "next"
                   :arguments {:threadId 3}}]
      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message
               "Failed to validate against schema :clojure-dap.schema/message",
               :clojure-dap.schema/explanation
               {:value
                {:arguments {:threadId 3},
                 :command "next",
                 :type "request",
                 :seq "153"},
                :errors sequential?}}]
             (protocol/parse-message (json/write-value-as-string message))))))

  (t/testing "returns an anomaly if the JSON is bad"
    (t/is (match?
           [:de.otto.nom.core/anomaly
            :cognitect.anomalies/incorrect
            {:cognitect.anomalies/message "Failed to parse DAP message JSON",
             :clojure-dap.protocol/body "{uhoh this is not ogodsds]!",
             :clojure-dap.protocol/error
             {:via vector?
              :trace vector?
              :cause "Unexpected character ('u' (code 117)): was expecting double-quote to start field name\n at [Source: (String)\"{uhoh this is not ogodsds]!\"; line: 1, column: 3]"}}]
           (protocol/parse-message "{uhoh this is not ogodsds]!")))))
