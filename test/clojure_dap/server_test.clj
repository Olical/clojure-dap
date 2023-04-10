(ns clojure-dap.server-test
  (:require [clojure.test :as t]
            [manifold.stream :as s]
            [clojure-dap.server :as server]
            [clojure-dap.stream :as stream]))

(defn start-test-server []
  (let [opts {:client-io (stream/io)
              :nrepl-io (stream/io)}]
    (merge
     opts
     (server/start opts))))

(t/deftest start-stop
  (t/testing "we can start and stop the system"
    (let [server (start-test-server)]
      (t/is server)
      (t/is (nil? (server/stop server))))))

;; -> initialise request
;; <- capabilities

;; <- initialised event

;; ONE POSSIBILITY
;; <- terminated event
;; <- exited event

;; Other exits:
;; The terminate request is sent from the client to the debug adapter in order to shut down the debuggee gracefully. Clients should only call this request if the capability supportsTerminateRequest is true.
;; The disconnect request asks the debug adapter to disconnect from the debuggee (thus ending the debug session) and then to shut down itself (the debug adapter). (which may also terminate if the opt is true!)

; (t/deftest minimal-session)
