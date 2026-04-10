(ns clojure-dap.schema-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [malli.core :as m]
            [de.otto.nom.core :as nom]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]))

(t/deftest assertions
  (t/testing "we must provide the right types"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid function arguments"
                            (schema/define! :foo [:any]))))

  (t/testing "unknown schemas return anomalies"
    (t/is (= [:de.otto.nom.core/anomaly
              :cognitect.anomalies/not-found
              {:cognitect.anomalies/message "Unknown schema: :clojure-dap.schema-test/foo"}]
             (schema/validate ::foo {:a true})))))

(t/deftest define-and-validate
  (t/testing "defining and validating a schema"
    (schema/define! ::red
      [:map
       [:a ::blue]
       [:b pos-int?]])

    (schema/define! ::blue string?)

    (t/is (not (nom/anomaly? (schema/validate ::blue "hi"))))
    (t/is (not (nom/anomaly? (schema/validate ::red {:a "hi", :b 10}))))
    (t/is (nom/anomaly? (schema/validate ::red {:a 26, :b 10})))
    (t/is (match?
           [:de.otto.nom.core/anomaly
            :cognitect.anomalies/incorrect
            {:clojure-dap.schema/explanation {:errors seq?
                                              :schema m/schema?
                                              :value {:a 26, :b 10}}
             :cognitect.anomalies/message "Failed to validate against schema :clojure-dap.schema-test/red"}]
           (schema/validate ::red {:a 26, :b 10})))))

(t/deftest result
  (t/testing "wraps a schema in [:or schema ::anomaly]"
    (let [result-schema (schema/result :string)]
      (t/is (vector? result-schema))
      (t/is (= :or (first result-schema)))
      (t/is (= :string (second result-schema)))
      (t/is (= ::schema/anomaly (nth result-schema 2))))))

(t/deftest dap-schemas
  (t/testing "message includes initialize request and response"
    (t/is (match?
           nil
           (schema/validate
            ::protocol/message
            {:seq 1
             :type "request"
             :command "initialize"
             :arguments {:adapterID "12345"}}))))

  (t/testing "validates initialize response"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "response"
                  :request_seq 1
                  :command "initialize"
                  :success true
                  :body {:supportsCancelRequest false
                         :supportsConfigurationDoneRequest true}}))))

  (t/testing "validates initialized event"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "event"
                  :event "initialized"}))))

  (t/testing "validates output event"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "event"
                  :event "output"
                  :body {:output "hello world"
                         :category "console"}}))))

  (t/testing "validates stopped event"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "event"
                  :event "stopped"
                  :body {:reason "breakpoint"
                         :threadId 1}}))))

  (t/testing "validates terminated event"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "event"
                  :event "terminated"
                  :body {}}))))

  (t/testing "validates attach request"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "request"
                  :command "attach"
                  :arguments {}}))))

  (t/testing "validates disconnect request"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "request"
                  :command "disconnect"}))))

  (t/testing "validates setBreakpoints request"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "request"
                  :command "setBreakpoints"
                  :arguments {:source {:path "/tmp/foo.clj"}
                              :breakpoints [{:line 5}]}}))))

  (t/testing "validates threads request"
    (t/is (nil? (schema/validate
                 ::protocol/message
                 {:seq 1
                  :type "request"
                  :command "threads"}))))

  (t/testing "rejects invalid seq (must be >= 1)"
    (t/is (nom/anomaly? (schema/validate
                         ::protocol/message
                         {:seq 0
                          :type "request"
                          :command "initialize"
                          :arguments {:adapterID "12345"}}))))

  (t/testing "rejects invalid type"
    (t/is (nom/anomaly? (schema/validate
                         ::protocol/message
                         {:seq 1
                          :type "invalid"
                          :command "initialize"
                          :arguments {:adapterID "12345"}}))))

  (t/testing "rejects missing seq"
    (t/is (nom/anomaly? (schema/validate
                         ::protocol/message
                         {:type "request"
                          :command "initialize"
                          :arguments {:adapterID "12345"}})))))
