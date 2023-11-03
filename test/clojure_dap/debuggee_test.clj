(ns clojure-dap.debuggee-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [clojure-dap.debuggee :as debuggee]))

(defn stub-debuggee []
  {:calls (atom [])
   :init (spy/spy (constantly []))
   :set-breakpoints (spy/spy (constantly []))
   :evaluate (spy/spy (constantly []))})

(t/deftest init
  (t/testing "calls init"
    (let [{:keys [init] :as debuggee} (stub-debuggee)]
      (debuggee/init debuggee)
      (t/is (= (list (list debuggee))
               (spy/calls init))))))

(t/deftest set-breakpoints
  (t/testing "calls set-breakpoints"
    (let [{:keys [set-breakpoints] :as debuggee} (stub-debuggee)
          arguments {:source {:path "foo.clj"}}]
      (debuggee/set-breakpoints debuggee arguments)
      (t/is (= (list (list debuggee arguments))
               (spy/calls set-breakpoints))))))

(t/deftest evaluate
  (t/testing "calls evaluate"
    (let [{:keys [evaluate] :as debuggee} (stub-debuggee)
          arguments {:expression "(+ 1 2)"}]
      (debuggee/evaluate debuggee arguments)
      (t/is (= (list (list debuggee arguments))
               (spy/calls evaluate))))))
