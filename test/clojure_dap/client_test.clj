(ns clojure-dap.client-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.stream :as stream]
            [clojure-dap.client :as client]
            [clojure-dap.stream-test :as stream-test]))

(t/deftest create
  (t/testing "connects a client IO pair to a new pair, can read and write DAP messages through it"
    (let [outer-io (stream/io)
          {:keys [client-io _anomalies]} (client/create outer-io)]

      @(s/put! (:output client-io)
               {:seq 153
                :type "request"
                :command "next"
                :arguments {:threadId 3}})
      (t/is
       (= "Content-Length: 72\r\n\r\n{\"seq\":153,\"type\":\"request\",\"command\":\"next\",\"arguments\":{\"threadId\":3}}"
          @(s/try-take! (:output outer-io) 100)))

      @(s/put-all! (:input outer-io) (seq stream-test/example-message))
      (t/is (= {:arguments {:threadId 3}
                :command "next"
                :seq 153
                :type "request"}
               @(s/try-take! (:input client-io) 100)))))

  (t/testing "handles invalid messages in either direction"
    (let [outer-io (stream/io)
          {:keys [client-io anomalies]} (client/create outer-io)]

      @(s/put! (:output client-io)
               {:seq 153
                :type "quest"
                :command "next"
                :arguments {:threadId 3}})
      (t/is
       (match?
        [:de.otto.nom.core/anomaly
         :cognitect.anomalies/incorrect
         {:cognitect.anomalies/message
          "Failed to validate against schema :clojure-dap.schema/message"
          :clojure-dap.schema/explanation
          {:schema some?
           :value {:seq 153
                   :type "quest"
                   :command "next"
                   :arguments {:threadId 3}}
           :errors seq?}
          :clojure-dap.schema/humanized
          ["3 JSON Validation errors: #/type: quest is not a valid enum value, #/arguments: required key [adapterID] not found, #/command: next is not a valid enum value"
           "3 JSON Validation errors: #: required key [request_seq] not found, #: required key [success] not found, #/type: quest is not a valid enum value"
           "JSON Validation error: #/type: quest is not a valid enum value"
           "3 JSON Validation errors: #: required key [request_seq] not found, #: required key [success] not found, #/type: quest is not a valid enum value"]}]
        @(s/take! anomalies)))))

  (t/testing "closes the streams if one end disconnects"))
