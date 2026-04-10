(ns clojure-dap.test.fake-nrepl-server
  "A fake nREPL server for integration testing. Responds to ops with
  canned responses without actually evaluating code or triggering debugger."
  (:require [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]
            [nrepl.misc :refer [response-for]]))

(defn make-handler
  "Creates a handler function for the fake nREPL server.
  Takes an opts map that can configure behavior:
    :eval-result    - value to return for eval ops (default \"nil\")
    :eval-ns        - ns to return for eval ops (default \"user\")
    :eval-error     - if set, return this as an error for eval ops
    :sessions       - list of session IDs for ls-sessions (default [\"test-session\"])
    :init-debugger  - seq of response maps for init-debugger op
                      (default [{:status #{:done}}])"
  ([] (make-handler {}))
  ([{:keys [eval-result eval-ns eval-error sessions init-debugger]
     :or {eval-result "nil"
          eval-ns "user"
          sessions ["test-session-1"]
          init-debugger [{:status #{"done"}}]}}]
   (let [session-counter (atom 0)]
     (fn [{:keys [op transport] :as msg}]
       (case op
         "clone"
         (let [new-session (str "fake-session-" (swap! session-counter inc))]
           (transport/send transport
                           (response-for msg
                                         :new-session new-session
                                         :status :done)))

         "close"
         (transport/send transport
                         (response-for msg :status :done))

         "describe"
         (transport/send transport
                         (response-for msg
                                       :ops {"eval" {} "clone" {} "close" {}
                                             "ls-sessions" {} "init-debugger" {}}
                                       :status :done))

         "eval"
         (if eval-error
           (do
             (transport/send transport
                             (response-for msg :err eval-error))
             (transport/send transport
                             (response-for msg :status #{"done" "eval-error"})))
           (do
             (transport/send transport
                             (response-for msg
                                           :value eval-result
                                           :ns eval-ns))
             (transport/send transport
                             (response-for msg :status #{"done"}))))

         "ls-sessions"
         (transport/send transport
                         (response-for msg
                                       :sessions sessions
                                       :status :done))

         "init-debugger"
         (doseq [response init-debugger]
           (transport/send transport
                           (merge
                            (response-for msg :status (or (:status response) #{"done"}))
                            (dissoc response :status))))

         "debug-input"
         (transport/send transport
                         (response-for msg :status :done))

         ;; Default: unknown op
         (transport/send transport
                         (response-for msg
                                       :status #{:error :unknown-op :done}
                                       :op op)))))))

(defn start!
  "Start a fake nREPL server on a random port. Returns the server.
  Use (.getLocalPort (:server-socket server)) to get the port.
  Stop with nrepl.server/stop-server."
  ([] (start! {}))
  ([opts]
   (nrepl-server/start-server
    :port 0
    :handler (make-handler opts))))

(defn port
  "Get the port of a running fake nREPL server."
  [server]
  (.getLocalPort ^java.net.ServerSocket (:server-socket server)))
