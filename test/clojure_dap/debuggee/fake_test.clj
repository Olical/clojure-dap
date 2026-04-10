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
      (t/is (fn? (:variables debuggee)))))

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

(t/deftest stack-trace
  (let [{:keys [stack-trace] :as debuggee} (fake-debuggee/create {})
        opts {:thread-id 1}]
    (t/is (= {:todo true} (debuggee/stack-trace debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls stack-trace))))

  (let [debuggee (fake-debuggee/create {:fail? true})]
    (t/is (nom/anomaly? (debuggee/stack-trace debuggee {:thread-id 1}))))

  (let [debuggee (fake-debuggee/create {:socket-exception? true})]
    (t/is (nom/anomaly? (debuggee/stack-trace debuggee {:thread-id 1})))))

(t/deftest scopes
  (let [{:keys [scopes] :as debuggee} (fake-debuggee/create {})
        opts {:frame-id 1}]
    (t/is (= {:todo true} (debuggee/scopes debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls scopes))))

  (let [debuggee (fake-debuggee/create {:fail? true})]
    (t/is (nom/anomaly? (debuggee/scopes debuggee {:frame-id 1}))))

  (let [debuggee (fake-debuggee/create {:socket-exception? true})]
    (t/is (nom/anomaly? (debuggee/scopes debuggee {:frame-id 1})))))

(t/deftest variables
  (let [{:keys [variables] :as debuggee} (fake-debuggee/create {})
        opts {:variables-reference 1}]
    (t/is (= {:todo true} (debuggee/variables debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls variables))))

  (let [debuggee (fake-debuggee/create {:fail? true})]
    (t/is (nom/anomaly? (debuggee/variables debuggee {:variables-reference 1}))))

  (let [debuggee (fake-debuggee/create {:socket-exception? true})]
    (t/is (nom/anomaly? (debuggee/variables debuggee {:variables-reference 1})))))
