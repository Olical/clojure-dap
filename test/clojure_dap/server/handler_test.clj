(ns clojure-dap.server.handler-test
  (:require [clojure.test :as t]
            [spy.core :as spy]
            [de.otto.nom.core :as nom]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.schema :as schema]
            [clojure-dap.stream :as stream]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.server.handler :as handler]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest handle-client-input
  (t/testing "initialize request"
    (t/is (= [{:body handler/initialised-response-body
               :command "initialize"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success true
               :type "response"}
              {:event "initialized", :seq protocol/seq-placeholder, :type "event"}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:seq 1
                :type "request"
                :command "initialize"
                :arguments {:adapterID "12345"}}}))))

  (t/testing "attach request"
    (t/testing "success"
      (let [debuggee! (atom nil)]
        (t/is (= [{:command "attach"
                   :request_seq 1
                   :seq protocol/seq-placeholder
                   :success true
                   :type "response"
                   :body {}}]
                 (handler/handle-client-input
                  {:debuggee! debuggee!
                   :output-stream (s/stream)
                   :input
                   {:seq 1
                    :type "request"
                    :command "attach"
                    :arguments {:clojure_dap {:type "fake"}}}})))
        (t/is (nil? (schema/validate ::debuggee/debuggee @debuggee!)))))

    (t/testing "failure due to bad inputs"
      (let [debuggee! (atom nil)]
        (t/is (match?
               [{:command "attach"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message #"Failed to validate against schema :clojure-dap.server.handler/attach-opts: .*should be either.*fake"
                 :body {:value {:clojure_dap {:type "ohno"}}}}]
               (handler/handle-client-input
                {:debuggee! debuggee!
                 :output-stream (s/stream)
                 :input
                 {:seq 1
                  :type "request"
                  :command "attach"
                  :arguments {:clojure_dap {:type "ohno"}}}})))
        (t/is (nil? @debuggee!))))

    (t/testing "failure due to a connection error (for example)"
      (let [debuggee! (atom nil)]
        (t/is (match?
               [{:command "attach"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message #"^Failed to connect to nREPL.\nDo you have one running?"
                 :body {:value {:clojure_dap
                                {:type "fake"
                                 :fake {:create-error? true}}}}}]
               (handler/handle-client-input
                {:debuggee! debuggee!
                 :output-stream (s/stream)
                 :input
                 {:seq 1
                  :type "request"
                  :command "attach"
                  :arguments {:clojure_dap
                              {:type "fake"
                               :fake {:create-error? true}}}}})))
        (t/is (nil? @debuggee!))))

    (t/testing "success with default debuggee type (nrepl) when no clojure_dap key"
      (let [debuggee! (atom nil)]
        (t/is (match?
               [{:command "attach"
                 :success false
                 :type "response"}]
               (handler/handle-client-input
                {:debuggee! debuggee!
                 :output-stream (s/stream)
                 :input
                 {:seq 1
                  :type "request"
                  :command "attach"
                  :arguments {}}}))))))

  (t/testing "disconnect request"
    (t/is (= [{:command "disconnect"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success true
               :type "response"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom (fake-debuggee/create {}))
               :output-stream (s/stream)
               :input
               {:arguments {:restart false, :terminateDebuggee true}
                :command "disconnect"
                :type "request"
                :seq 1}}))))

  (t/testing "configurationDone request"
    (t/is (= [{:command "configurationDone"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success true
               :type "response"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom (fake-debuggee/create {}))
               :output-stream (s/stream)
               :input
               {:arguments {}
                :command "configurationDone"
                :type "request"
                :seq 1}}))))

  (t/testing "setBreakpoints request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "setBreakpoints"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {:source {:path "foo.clj"}
                            :breakpoints [{:line 5}]}
                :command "setBreakpoints"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "setBreakpoints"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:breakpoints [{:line 5, :verified true}]}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:source {:path "foo.clj"}
                              :breakpoints [{:line 5}]}
                  :command "setBreakpoints"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "setBreakpoints"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "setBreakpoints command failed (:clojure-dap.debuggee.fake/set-breakpoints-failure)"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:source {:path "foo.clj"}
                              :breakpoints [{:line 5}]}
                  :command "setBreakpoints"
                  :type "request"
                  :seq 1}}))))

    (t/testing "socket disconnected"
      (t/is (= [{:command "setBreakpoints"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "setBreakpoints command failed (:clojure-dap.debuggee.fake/socket-exception)"
                 :body {}}
                {:body {}, :event "terminated", :seq protocol/seq-placeholder, :type "event"}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:socket-exception? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:source {:path "foo.clj"}
                              :breakpoints []}
                  :command "setBreakpoints"
                  :type "request"
                  :seq 1}})))))

  (t/testing "evaluate request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "evaluate"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {:expression "(+ 1 2)"}
                :command "evaluate"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "evaluate"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:result ":fake-eval-result"
                        :variablesReference 0}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:expression "(+ 1 2)"}
                  :command "evaluate"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "evaluate"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "evaluate command failed (:clojure-dap.debuggee.fake/evaluate-failure)"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:expression "(+ 1 2)"}
                  :command "evaluate"
                  :type "request"
                  :seq 1}}))))

    (t/testing "socket disconnected"
      (t/is (= [{:command "evaluate"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "evaluate command failed (:clojure-dap.debuggee.fake/socket-exception)"
                 :body {}}
                {:body {}, :event "terminated", :seq protocol/seq-placeholder, :type "event"}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:socket-exception? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:expression "(+ 1 2)"}
                  :command "evaluate"
                  :type "request"
                  :seq 1}})))))

  (t/testing "threads request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "threads"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {}
                :command "threads"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "threads"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:threads [{:id -1070493020, :name "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"}]}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {}
                  :command "threads"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "threads"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "threads command failed (:clojure-dap.debuggee.fake/threads-failure)"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {}
                  :command "threads"
                  :type "request"
                  :seq 1}}))))

    (t/testing "socket disconnected"
      (t/is (= [{:command "threads"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "threads command failed (:clojure-dap.debuggee.fake/socket-exception)"
                 :body {}}
                {:body {}, :event "terminated", :seq protocol/seq-placeholder, :type "event"}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:socket-exception? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {}
                  :command "threads"
                  :type "request"
                  :seq 1}})))))

  (t/testing "stackTrace request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "stackTrace"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {:threadId 1}
                :command "stackTrace"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "stackTrace"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:stackFrames [] :totalFrames 0}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "stackTrace"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "stackTrace"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "stackTrace command failed (:clojure-dap.debuggee.fake/stack-trace-failure)"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "stackTrace"
                  :type "request"
                  :seq 1}}))))

    (t/testing "socket disconnected"
      (t/is (= [{:command "stackTrace"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "stackTrace command failed (:clojure-dap.debuggee.fake/socket-exception)"
                 :body {}}
                {:body {}, :event "terminated", :seq protocol/seq-placeholder, :type "event"}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:socket-exception? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "stackTrace"
                  :type "request"
                  :seq 1}})))))

  (t/testing "scopes request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "scopes"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {:frameId 1}
                :command "scopes"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "scopes"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:scopes []}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:frameId 1}
                  :command "scopes"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "scopes"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "scopes command failed (:clojure-dap.debuggee.fake/scopes-failure)"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:frameId 1}
                  :command "scopes"
                  :type "request"
                  :seq 1}}))))

    (t/testing "socket disconnected"
      (t/is (= [{:command "scopes"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "scopes command failed (:clojure-dap.debuggee.fake/socket-exception)"
                 :body {}}
                {:body {}, :event "terminated", :seq protocol/seq-placeholder, :type "event"}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:socket-exception? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:frameId 1}
                  :command "scopes"
                  :type "request"
                  :seq 1}})))))

  (t/testing "variables request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "variables"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {:variablesReference 1}
                :command "variables"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "variables"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:variables []}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:variablesReference 1}
                  :command "variables"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "variables"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "variables command failed (:clojure-dap.debuggee.fake/variables-failure)"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:variablesReference 1}
                  :command "variables"
                  :type "request"
                  :seq 1}}))))

    (t/testing "socket disconnected"
      (t/is (= [{:command "variables"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success false
                 :type "response"
                 :message "variables command failed (:clojure-dap.debuggee.fake/socket-exception)"
                 :body {}}
                {:body {}, :event "terminated", :seq protocol/seq-placeholder, :type "event"}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {:socket-exception? true}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:variablesReference 1}
                  :command "variables"
                  :type "request"
                  :seq 1}})))))

  (t/testing "unknown commands are caught at protocol validation, not the handler"
    (t/is (nom/anomaly?
           (schema/validate
            ::protocol/message
            {:seq 1
             :type "request"
             :command "launch"
             :arguments {}}))))

  (t/testing "continue request"
    (t/testing "success"
      (t/is (= [{:command "continue"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {:allThreadsContinued true}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "continue"
                  :type "request"
                  :seq 1}})))))

  (t/testing "next request"
    (t/testing "success"
      (t/is (= [{:command "next"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "next"
                  :type "request"
                  :seq 1}})))))

  (t/testing "stepIn request"
    (t/testing "success"
      (t/is (= [{:command "stepIn"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "stepIn"
                  :type "request"
                  :seq 1}})))))

  (t/testing "stepOut request"
    (t/testing "success"
      (t/is (= [{:command "stepOut"
                 :request_seq 1
                 :seq protocol/seq-placeholder
                 :success true
                 :type "response"
                 :body {}}]
               (handler/handle-client-input
                {:debuggee! (atom (fake-debuggee/create {}))
                 :output-stream (s/stream)
                 :input
                 {:arguments {:threadId 1}
                  :command "stepOut"
                  :type "request"
                  :seq 1}})))))

  (t/testing "setExceptionBreakpoints returns empty breakpoints"
    (t/is (= [{:command "setExceptionBreakpoints"
               :request_seq 1
               :seq protocol/seq-placeholder
               :success true
               :type "response"
               :body {:breakpoints []}}]
             (handler/handle-client-input
              {:debuggee! (atom nil)
               :output-stream (s/stream)
               :input
               {:arguments {:filters []}
                :command "setExceptionBreakpoints"
                :type "request"
                :seq 1}})))))

(t/deftest handle-anomalous-client-input
  (t/testing "given an anomaly it returns an output event containing an explanation"
    (t/is (match?
           [{:event "output",
             :seq protocol/seq-placeholder,
             :type "event",
             :body {:category "important"
                    :data {:event "some unknown event"
                           :foo true
                           :type "event"}
                    :output #"^\[:cognitect.anomalies/incorrect\] Failed to validate against schema :clojure-dap.protocol/message: \d+ JSON Validation errors: #:.*required key \[seq\] not found, "}}]
           (handler/handle-anomalous-client-input
            {:anomaly (schema/validate
                       ::protocol/message
                       {:type "event"
                        :event "some unknown event"
                        :foo true})}))))

  (t/testing "when the anomaly is a ::stream/closed then we just return nothing"
    (t/is (= [{:type "event"
               :event "output"
               :seq protocol/seq-placeholder
               :body {:category "important"
                      :output "Input stream closed, clojure-dap will shut down."}}]
             (handler/handle-anomalous-client-input
              {:anomaly (nom/fail ::stream/closed)})))))

(t/deftest socket-exception-anomaly?
  (t/testing "returns true when given an anomaly containing a SocketException"
    (t/is (handler/socket-exception-anomaly?
           (nom/fail :uhoh {:exception (java.net.SocketException.)})))
    (t/is (not
           (handler/socket-exception-anomaly?
            (nom/fail :uhoh {:exception (Exception.)}))))))

(t/deftest handle-anomaly
  (t/testing "does nothing if you don't give it an anomaly"
    (t/is (nil? (handler/handle-anomaly
                 10
                 {:resp (spy/spy)

                  :input {:arguments {:expression "(+ 1 2)"}
                          :command "evaluate"
                          :type "request"
                          :seq 1}}))))

  (t/testing "returns a failure if it's an anomaly"
    (t/is (= [{:message "evaluate command failed (:ohno)"
               :success false}]
             (handler/handle-anomaly
              (nom/fail :ohno {:message "aaaa"})
              {:resp (spy/spy identity)

               :input {:arguments {:expression "(+ 1 2)"}
                       :command "evaluate"
                       :type "request"
                       :seq protocol/seq-placeholder}}))))

  (t/testing "also returns a terminated event if the anomaly is a SocketException"
    (t/is (= [{:message "evaluate command failed (:ohno)"
               :success false}
              {:body {}
               :event "terminated"
               :seq protocol/seq-placeholder
               :type "event"}]
             (handler/handle-anomaly
              (nom/fail :ohno {:exception (java.net.SocketException.)})
              {:resp (spy/spy identity)
               :input {:arguments {:expression "(+ 1 2)"}
                       :command "evaluate"
                       :type "request"
                       :seq protocol/seq-placeholder}})))))

(t/deftest render-anomaly
  (t/testing "renders anomaly into explanation and value"
    (let [anomaly (schema/validate
                   ::protocol/message
                   {:seq "not-a-number"
                    :type "request"
                    :command "initialize"
                    :arguments {:adapterID "12345"}})
          result (handler/render-anomaly anomaly)]
      (t/is (string? (:explanation result)))
      (t/is (= {:seq "not-a-number"
                :type "request"
                :command "initialize"
                :arguments {:adapterID "12345"}}
               (:value result))))))
