(ns clojure-dap.debuggee.fake-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest set-breakpoints
  (let [{:keys [set-breakpoints] :as debuggee} (fake-debuggee/create)
        opts {}]
    (t/is (nil? (debuggee/set-breakpoints debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls set-breakpoints)))))

(t/deftest evaluate
  (let [{:keys [evaluate] :as debuggee} (fake-debuggee/create)
        opts {}]
    (t/is (nil? (debuggee/evaluate debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls evaluate)))))
