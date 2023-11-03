(ns clojure-dap.debuggee.fake-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest init
  (t/testing "returns messages"
    (let [{:keys [init] :as debuggee} (fake-debuggee/create)]
      (debuggee/init debuggee)
      (t/is (= (list (list debuggee))
               (spy/calls init))))))

(t/deftest set-breakpoints
  (t/testing "returns messages"
    (let [{:keys [set-breakpoints] :as debuggee} (fake-debuggee/create)
          arguments {:source {:path "foo.clj"}}]
      (debuggee/set-breakpoints debuggee arguments)
      (t/is (= (list (list debuggee arguments))
               (spy/calls set-breakpoints))))))

(t/deftest evaluate
  (t/testing "returns messages"
    (let [{:keys [evaluate] :as debuggee} (fake-debuggee/create)
          arguments {:expression "(+ 1 2)"}]
      (debuggee/evaluate debuggee arguments)
      (t/is (= (list (list debuggee arguments))
               (spy/calls evaluate))))))
