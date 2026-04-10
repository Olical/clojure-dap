(ns clojure-dap.debuggee.nrepl-test
  (:require [clojure.test :as t]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee.nrepl :as nrepl-debuggee]))

(t/deftest handle-init-debugger-output
  (t/testing "returns a stopped event when status contains need-debug-input"
    (let [session-id "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"
          result (nrepl-debuggee/handle-init-debugger-output
                  {:status ["need-debug-input"]
                   :session session-id
                   :debug-value "30"
                   :key "some-key"})]
      (t/is (= [{:type "event"
                 :event "stopped"
                 :seq protocol/seq-placeholder
                 :body {:reason "breakpoint"
                        :threadId (hash session-id)}}]
               result))))

  (t/testing "returns nil when status does not contain need-debug-input"
    (t/is (nil? (nrepl-debuggee/handle-init-debugger-output
                 {:status ["done"]
                  :session "some-session"}))))

  (t/testing "returns nil for empty status"
    (t/is (nil? (nrepl-debuggee/handle-init-debugger-output
                 {:status []
                  :session "some-session"})))))
