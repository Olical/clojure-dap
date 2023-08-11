(ns clojure-dap.schema-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [malli.core :as m]
            [de.otto.nom.core :as nom]
            [clojure-dap.schema :as schema]))

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

(t/deftest dap-schemas
  (t/testing "protocol-message"
    (t/is (nil?
           (schema/validate
            ::schema/protocol-message
            {:seq 0
             :type "request"}))))

  (t/testing "request"
    (t/is (nil?
           (schema/validate
            ::schema/request
            {:seq 0
             :type "request"
             :command "foo"
             :arguments {:a 10}}))))

  (t/testing "event"
    (t/is (nil?
           (schema/validate
            ::schema/event
            {:seq 0
             :type "event"
             :event "foo"
             :body {:a 10}}))))

  (t/testing "response"
    (t/is (nil?
           (schema/validate
            ::schema/response
            {:seq 0
             :type "response"
             :request_seq 10
             :success true
             :command "barrel_roll"
             :message "wheeeee"
             :body {:a 10}})))))
