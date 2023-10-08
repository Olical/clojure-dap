(ns clojure-dap.client-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.stream :as stream]
            [clojure-dap.client :as client]
            [clojure-dap.stream-test :as stream-test]
            [clojure-dap.test-util :as tutil]))

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
       (= (str "Content-Length: 72"
               stream/double-header-sep
               "{\"seq\":153,\"type\":\"request\",\"command\":\"next\",\"arguments\":{\"threadId\":3}}")
          (tutil/try-take (:output outer-io))))

      @(s/put-all! (:input outer-io) (seq stream-test/example-message))
      (t/is (= {:arguments {:threadId 3}
                :command "next"
                :seq 153
                :type "request"}
               (tutil/try-take (:input client-io))))

      (stream/close-io! outer-io)
      (stream/close-io! client-io)))

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
        (tutil/try-take anomalies)))

      (t/is (not (s/closed? (:input outer-io))))
      (t/is (not (s/closed? (:output outer-io))))
      (t/is (not (s/closed? (:input client-io))))
      (t/is (not (s/closed? (:output client-io))))
      (t/is (not (s/closed? anomalies)))

      (stream/close-io! outer-io)
      (stream/close-io! client-io))

    (let [outer-io (stream/io)
          {:keys [client-io anomalies]} (client/create outer-io)]
      @(s/put-all! (:input outer-io) (seq stream-test/invalid-message))
      (t/is
       (match?
        [:de.otto.nom.core/anomaly
         :cognitect.anomalies/incorrect
         {:cognitect.anomalies/message
          "Failed to validate against schema :clojure-dap.schema/message",
          :clojure-dap.schema/explanation
          {:schema some?
           :value
           {:arguments {:threadId 3},
            :command "next"
            :type "reqest"
            :seq 153}
           :errors seq?}
          :clojure-dap.schema/humanized
          ["3 JSON Validation errors: #/type: reqest is not a valid enum value, #/arguments: required key [adapterID] not found, #/command: next is not a valid enum value"
           "3 JSON Validation errors: #: required key [request_seq] not found, #: required key [success] not found, #/type: reqest is not a valid enum value"
           "JSON Validation error: #/type: reqest is not a valid enum value"
           "3 JSON Validation errors: #: required key [request_seq] not found, #: required key [success] not found, #/type: reqest is not a valid enum value"]}]
        (tutil/try-take anomalies)))

      (tutil/block-until
       "Streams closed"
       #(s/closed? anomalies))

      (t/is (s/closed? (:input outer-io)))
      (t/is (s/closed? (:output outer-io)))
      (t/is (s/closed? (:input client-io)))
      (t/is (s/closed? (:output client-io)))
      (t/is (s/closed? anomalies)))

    (let [outer-io (stream/io)
          {:keys [client-io anomalies]} (client/create outer-io)]
      @(s/put-all! (:input outer-io) (seq (str "Content-Length: ohno" stream/double-header-sep)))
      (t/is
       (match?
        [:de.otto.nom.core/anomaly
         :cognitect.anomalies/incorrect
         {:cognitect.anomalies/message "Failed to parse DAP header"
          :clojure-dap.stream/header (str "Content-Length: ohno" stream/double-header-sep)
          :clojure-dap.stream/error
          {:via
           [{:type 'com.fasterxml.jackson.core.JsonParseException
             :message "Unrecognized token 'ohno': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\n at [Source: (String)\"ohno\"; line: 1, column: 5]"}]}}]
        (tutil/try-take anomalies)))

      (tutil/block-until
       "Streams closed"
       #(s/closed? anomalies))

      (t/is (s/closed? (:input outer-io)))
      (t/is (s/closed? (:output outer-io)))
      (t/is (s/closed? (:input client-io)))
      (t/is (s/closed? (:output client-io)))
      (t/is (s/closed? anomalies)))

    (t/testing "closes the streams if one end disconnects"
      (let [outer-io (stream/io)
            {:keys [client-io anomalies]} (client/create outer-io)]

        (s/close! (:input client-io))
        (s/close! (:output client-io))

        (tutil/block-until
         "Streams closed"
         #(s/closed? anomalies))

        (t/is (s/closed? (:input outer-io)))
        (t/is (s/closed? (:output outer-io)))
        (t/is (s/closed? (:input client-io)))
        (t/is (s/closed? (:output client-io)))
        (t/is (s/closed? anomalies))))))
