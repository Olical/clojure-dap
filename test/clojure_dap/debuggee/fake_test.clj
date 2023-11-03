(ns clojure-dap.debuggee.fake-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest initialize
  (t/testing "returns messages"
    (let [{:keys [initialize] :as this} (fake-debuggee/create)
          arguments {:clientID "test suite"
                     :adapterID "clojure-dap"}]
      (debuggee/initialize this arguments)
      (t/is (= (list (list this arguments))
               (spy/calls initialize))))))

(t/deftest set-breakpoints
  (t/testing "returns messages"
    (let [{:keys [set-breakpoints] :as this} (fake-debuggee/create)
          arguments {:source {:path "foo.clj"}}]
      (debuggee/set-breakpoints this arguments)
      (t/is (= (list (list this arguments))
               (spy/calls set-breakpoints))))))

(t/deftest evaluate
  (t/testing "returns messages"
    (let [{:keys [evaluate] :as debuggee} (fake-debuggee/create)
          arguments {:expression "(+ 1 2)"}]
      (debuggee/evaluate debuggee arguments)
      (t/is (= (list (list debuggee arguments))
               (spy/calls evaluate))))))
