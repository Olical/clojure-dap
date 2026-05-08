(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.java.io :as io]
            [taoensso.telemere :as tel]
            [me.raynes.fs :as rfs]
            [malli.experimental :as mx]
            [malli.instrument :as mi]
            [manifold.stream :as s]
            [clojure-dap.util :as util]
            [clojure-dap.server :as server])
  (:import [java.io PrintStream]))

(defonce ^PrintStream dap-stdout System/out)

(defn setup-stdio!
  "Redirects System.out and *out* to stderr so anything that prints to stdout
  (telemere defaults, library banners, errant println in user code) lands on
  stderr instead of corrupting the DAP JSON-RPC transport. The original stdout
  is held in dap-stdout for the DAP server to write frames to. Idempotent."
  []
  (System/setOut System/err)
  (alter-var-root #'*out* (constantly *err*))
  (tel/remove-handler! :default/console)
  (tel/add-handler! :stderr
                    (tel/handler:console
                     {:stream :*err*
                      :output-fn (tel/format-signal-fn {})})))

(mx/defn run :- :nil
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts :- :map]

  (setup-stdio!)

  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
    (fn []
      (tel/log! :info "Shutdown hook triggered, shutting down...")
      (shutdown-agents)
      (tel/log! :info "All done, goodbye!"))))

  ;; TODO Make this cross platform and not conflict with other processes.
  ;; And will we end up with multiple processes sharing the same file?
  ;; Also nvim users sometimes move their cache dir, so we should write to our own maybe, or tmp.
  (tel/add-handler! :file
                    (tel/handler:file
                     {:path (str (rfs/expand-home "~/.cache/nvim/clojure-dap.log"))
                      :output-fn (tel/format-signal-fn {})}))

  (tel/set-min-level! :trace)
  (tel/log! :info ["Starting clojure-dap with configuration:" opts])

  (tel/log! :info "Initialising Malli instrumentation")
  (mi/instrument! {:report (util/malli-reporter)})

  (tel/log! :info "Starting server...")
  (let [{:keys [server-complete anomalies-stream]}
        (server/run-io-wrapped
         {:input-reader (io/reader System/in)
          :output-writer (io/writer dap-stdout)})]
    (s/consume #(tel/log! :error %) anomalies-stream)
    (tel/log! :info "Server started in single session mode (multi session mode will come later)")
    @server-complete)
  (tel/log! :info "Server completed, will exit"))
