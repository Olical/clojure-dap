(ns clojure-dap.schema-test
  (:require [clojure.test :as t]
            [clojure-dap.schema :as schema]))

(t/deftest assertions
  (t/testing "we must provide the right types"
    (t/is (thrown-with-msg? AssertionError #"Schema ID must be a qualified keyword"
                            (schema/define! :foo [:any]))))

  (t/testing "unknown schemas throw errors"
    (t/is (thrown-with-msg? AssertionError #"Unknown schema: :clojure-dap.schema-test/foo"
                            (schema/explain ::foo {:a true})))))

(t/deftest define-and-validate
  (t/testing "defining and validating a schema"
    (schema/define! ::red
      [:map
       [:a ::blue]
       [:b pos-int?]])

    (schema/define! ::blue string?)

    (t/is (nil? (schema/explain ::blue "hi")))
    (t/is (nil? (schema/explain ::red {:a "hi", :b 10})))
    (t/is (= "{:schema [:map [:a :clojure-dap.schema-test/blue] [:b pos-int?]], :value {:a 26, :b 10}, :errors ({:path [:a 0], :in [:a], :schema string?, :value 26})}"
             (pr-str (schema/explain ::red {:a 26, :b 10}))))))
