(ns clojure-dap.debuggee.fake-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [de.otto.nom.core :as nom]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest create
  (t/testing "returns a debuggee map with spy-wrapped functions"
    (let [debuggee (fake-debuggee/create {})]
      (t/is (fn? (:set-breakpoints debuggee)))
      (t/is (fn? (:evaluate debuggee)))
      (t/is (fn? (:threads debuggee)))
      (t/is (fn? (:stack-trace debuggee)))
      (t/is (fn? (:scopes debuggee)))
      (t/is (fn? (:variables debuggee)))
      (t/is (fn? (:continue debuggee)))
      (t/is (fn? (:next debuggee)))
      (t/is (fn? (:step-in debuggee)))
      (t/is (fn? (:step-out debuggee)))))

  (t/testing "returns an anomaly when create-error? is true"
    (t/is (nom/anomaly? (fake-debuggee/create {:create-error? true})))))

(t/deftest set-breakpoints
  (let [{:keys [set-breakpoints] :as debuggee} (fake-debuggee/create {})
        opts {:breakpoints [{:line 5}]
              :source {:path "foo.clj"}}]
    (t/is (= {:breakpoints [{:line 5, :verified true}]} (debuggee/set-breakpoints debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls set-breakpoints)))

    (let [debuggee (fake-debuggee/create {:fail? true})]
      (t/is (nom/anomaly? (debuggee/set-breakpoints debuggee opts))))

    (let [debuggee (fake-debuggee/create {:socket-exception? true})]
      (t/is (nom/anomaly? (debuggee/set-breakpoints debuggee opts))))))

(t/deftest evaluate
  (let [{:keys [evaluate] :as debuggee} (fake-debuggee/create {})
        opts {:expression "(+ 1 2)"}]
    (t/is (= {:result ":fake-eval-result"} (debuggee/evaluate debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls evaluate))))

  (let [debuggee (fake-debuggee/create {:fail? true})]
    (t/is (nom/anomaly? (debuggee/evaluate
                         debuggee
                         {:expression "(+ 1 2)"}))))

  (let [debuggee (fake-debuggee/create {:socket-exception? true})]
    (t/is (nom/anomaly? (debuggee/evaluate
                         debuggee
                         {:expression "(+ 1 2)"})))))

(t/deftest threads
  (let [{:keys [threads] :as debuggee} (fake-debuggee/create {})]
    (t/is (= {:threads [{:id -1070493020
                         :name "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"}]}
             (debuggee/threads debuggee)))
    (t/is (= (list (list debuggee))
             (spy/calls threads))))

  (let [debuggee (fake-debuggee/create {:fail? true})]
    (t/is (nom/anomaly? (debuggee/threads debuggee))))

  (let [debuggee (fake-debuggee/create {:socket-exception? true})]
    (t/is (nom/anomaly? (debuggee/threads debuggee)))))

(def fake-breakpoint-state
  {:key "debug-key-1"
   :debug-value "42"
   :coor [3]
   :locals [["a" "10"] ["b" "20"]]
   :file "/tmp/test.clj"
   :line 13
   :column 1
   :code "(defn foo [a b] (+ a b))"
   :input-type ["continue" "next" "in" "out" "quit"]
   :session "test-session"
   :original-ns "test-ns"})

(t/deftest stack-trace-test
  (t/testing "returns frame from breakpoint state"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/stack-trace debuggee {:thread-id 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (= {:stackFrames [{:id 1
                                 :name "(defn foo [a b] (+ a b))"
                                 :source {:path "/tmp/test.clj"}
                                 :line 13
                                 :column 1}]
                  :totalFrames 1}
                 result)))))

  (t/testing "returns empty when no breakpoint is active"
    (let [debuggee (fake-debuggee/create {})]
      (let [result (debuggee/stack-trace debuggee {:thread-id 1})]
        (t/is (= {:stackFrames [] :totalFrames 0} result))))))

(t/deftest scopes-test
  (t/testing "returns locals scope from breakpoint state"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/scopes debuggee {:frame-id 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (= {:scopes [{:name "Locals"
                            :variablesReference 1
                            :expensive false}]}
                 result)))))

  (t/testing "returns empty scopes when no breakpoint is active"
    (let [debuggee (fake-debuggee/create {})]
      (let [result (debuggee/scopes debuggee {:frame-id 1})]
        (t/is (= {:scopes []} result))))))

(t/deftest variables-test
  (t/testing "returns locals as variables from breakpoint state"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/variables debuggee {:variables-reference 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (= {:variables [{:name "a" :value "10" :variablesReference 0}
                              {:name "b" :value "20" :variablesReference 0}]}
                 result)))))

  (t/testing "returns empty when no breakpoint is active"
    (let [debuggee (fake-debuggee/create {})]
      (let [result (debuggee/variables debuggee {:variables-reference 1})]
        (t/is (= {:variables []} result))))))

(t/deftest continue-test
  (t/testing "clears breakpoint state and returns"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/continue debuggee {:thread-id 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (nil? @(:breakpoint-state! debuggee)))))))

(t/deftest next-test
  (t/testing "clears breakpoint state and returns"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/next-request debuggee {:thread-id 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (nil? @(:breakpoint-state! debuggee)))))))

(t/deftest step-in-test
  (t/testing "clears breakpoint state and returns"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/step-in debuggee {:thread-id 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (nil? @(:breakpoint-state! debuggee)))))))

(t/deftest step-out-test
  (t/testing "clears breakpoint state and returns"
    (let [debuggee (fake-debuggee/create {})]
      (reset! (:breakpoint-state! debuggee) fake-breakpoint-state)
      (let [result (debuggee/step-out debuggee {:thread-id 1})]
        (t/is (not (nom/anomaly? result)))
        (t/is (nil? @(:breakpoint-state! debuggee)))))))
