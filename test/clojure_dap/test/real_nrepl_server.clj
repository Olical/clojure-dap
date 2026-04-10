(ns clojure-dap.test.real-nrepl-server
  "Starts a real nREPL server with CIDER middleware for integration testing.
  Provides helpers to evaluate code and trigger breakpoints."
  (:require [nrepl.core :as nrepl]
            [nrepl.server :as nrepl-server]
            [cider.nrepl :as cider]
            [taoensso.telemere :as tel]))

(defn start!
  "Start a real nREPL server with CIDER middleware on a random port.
  Returns the server."
  []
  (nrepl-server/start-server
   :port 0
   :handler cider/cider-nrepl-handler))

(defn port [server]
  (.getLocalPort ^java.net.ServerSocket (:server-socket server)))

(defn stop! [server]
  (nrepl-server/stop-server server))

(defn eval!
  "Evaluate code on a separate nREPL client connection.
  Returns the combined response. Uses its own connection so it doesn't
  interfere with the debuggee's session."
  [server-port code & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (with-open [conn (nrepl/connect :port server-port)]
    (let [client (nrepl/client conn timeout-ms)]
      (tel/log! :info ["real-nrepl eval:" code])
      (let [responses (doall (nrepl/message client {:op "eval" :code code}))]
        (tel/log! :info ["real-nrepl eval responses:" responses])
        (nrepl/combine-responses responses)))))

(defn eval-async!
  "Evaluate code on a separate nREPL connection in a future.
  Returns the future. Use this for code that will hit a breakpoint
  (the eval will block until debug-input is sent)."
  [server-port code & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (future
    (try
      (eval! server-port code :timeout-ms timeout-ms)
      (catch Exception e
        (tel/log! {:level :error :error e} "eval-async! failed")
        {:error (ex-message e)}))))
