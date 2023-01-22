(ns clojure-dap.schema-test
  (:require [clojure.test :as t]
            [clojure-dap.schema :as schema]
            [clojure-dap.util :as util]))

(t/deftest assertions
  (t/testing "we must provide the right types"
    (t/is (thrown-with-msg? AssertionError #"Schema ID must be a qualified keyword"
                            (schema/define! :foo [:any]))))

  (t/testing "unknown schemas throw errors"
    (t/is (thrown-with-msg? AssertionError #"Unknown schema: :clojure-dap.schema-test/foo"
                            (schema/validate ::foo {:a true})))))

(t/deftest define-and-validate
  (t/testing "defining and validating a schema"
    (schema/define! ::red
      [:map
       [:a ::blue]
       [:b pos-int?]])

    (schema/define! ::blue string?)

    (t/is (not (util/anomaly? (schema/validate ::blue "hi"))))
    (t/is (not (util/anomaly? (schema/validate ::red {:a "hi", :b 10}))))
    (t/is (util/anomaly? (schema/validate ::red {:a 26, :b 10})))
    (t/is (= "{:cognitect.anomalies/category :cognitect.anomalies/incorrect, :cognitect.anomalies/message \"Failed to validate against schema :clojure-dap.schema-test/red\", :explanation {:schema [:map [:a :clojure-dap.schema-test/blue] [:b pos-int?]], :value {:a 26, :b 10}, :errors ({:path [:a 0], :in [:a], :schema string?, :value 26})}}"
             (pr-str (schema/validate ::red {:a 26, :b 10}))))))
