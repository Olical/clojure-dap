(ns clojure-dap.server-test
  (:require [clojure.test :as t]
            [manifold.stream :as s]
            [clojure-dap.server :as server]
            [clojure-dap.stream :as stream]
            [clojure-dap.test-util :as tutil]))

(defn start-test-server []
  (let [opts {:client-io (stream/io)
              :nrepl-io (stream/io)}]
    (merge
     opts
     (server/start opts))))

(defn with-server [f]
  (let [server (start-test-server)]
    (try
      (f server)
      (finally
        (server/stop server)))))

(t/deftest auto-seq
  (t/testing "starts at 1 and auto increments"
    (let [next-seq (server/auto-seq)]
      (t/is (= 1 (next-seq)))
      (t/is (= 2 (next-seq)))
      (t/is (= 3 (next-seq))))))

(t/deftest start-stop
  (t/testing "we can start and stop the system"
    (let [server (start-test-server)]
      (t/is server)
      (t/is (nil? (server/stop server))))))

(t/deftest minimal-flow
  (t/testing "we can start a server, send an initialized event and get capabilities and an initialized response back out"
    (with-server
      (fn [{:keys [client-io] :as _server}]
        (s/put!
         (:input client-io)
         {:seq 1
          :type "request"
          :command "initialize"
          :arguments {:adapterID "12345"}})
        (t/is (match?
               {:seq 1
                :request_seq 1
                :type "response"
                :command "initialize"
                :success true
                :body {:supportsCancelRequest false}}
               (tutil/try-take (:output client-io))))

        (t/testing "unknown / unhandled messages get an error response"
          (s/put!
           (:input client-io)
           {:seq 2
            :type "request"
            :command "unknownthing"
            :arguments {}})
          (t/is (match?
                 {:seq 2
                  :request_seq 2
                  :type "response"
                  :command "unknownthing"
                  :success false
                  :message #"Error while handling input"}
                 (tutil/try-take (:output client-io)))))))))

;; -> initialise request
;; <- capabilities

;; <- initialised event

;; ONE POSSIBILITY
;; <- terminated event
;; <- exited event

;; Other exits:
;; The terminate request is sent from the client to the debug adapter in order to shut down the debuggee gracefully. Clients should only call this request if the capability supportsTerminateRequest is true.
;; The disconnect request asks the debug adapter to disconnect from the debuggee (thus ending the debug session) and then to shut down itself (the debug adapter). (which may also terminate if the opt is true!)
