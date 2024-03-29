(ns clojure-dap.debuggee.fake-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [de.otto.nom.core :as nom]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest set-breakpoints
  (let [{:keys [set-breakpoints] :as debuggee} (fake-debuggee/create {})
        opts {:breakpoints [{:line 5}]
              :source {:path "foo.clj"}}]
    (t/is (= {:breakpoints [{:line 5, :verified true}]} (debuggee/set-breakpoints debuggee opts)))
    (t/is (= (list (list debuggee opts))
             (spy/calls set-breakpoints)))

    (let [debuggee (fake-debuggee/create {:fail? true})]
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
                         {:expression "(+ 1 2)"})))))
