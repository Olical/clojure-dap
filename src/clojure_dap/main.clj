(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as rfs]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [malli.experimental :as mx]
            [malli.instrument :as mi]
            [malli.dev.pretty :as malli-pretty]
            [manifold.stream :as s]
            [clojure-dap.server :as server]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(mx/defn run :- :nil
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts :- :map]

  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
    (fn []
      (log/info "Shutdown hook triggered, shutting down...")
      (shutdown-agents)
      (log/info "All done, goodbye!"))))

  (log/merge-config!
   {:appenders {;; Never log to stdout through timbre.
                ;; This is because the DAP server may be communicating with a client over stdout.
                ;; We also install extra deps that hook up essentially every other Java
                ;; logging system to timbre, so it all goes out under stderr with the same formatting.
                :println (appenders/println-appender {:stream :*err*})

                ;; TODO Make this cross platform and not conflict with other processes.
                ;; And will we end up with multiple processes sharing the same file?
                ;; Also nvim users sometimes move their cache dir, so we should write to our own maybe, or tmp.
                :spit (appenders/spit-appender {:fname (str (rfs/expand-home "~/.cache/nvim/clojure-dap.log"))})}
    :middleware [#(assoc % :hostname_ "-")]})

  (log/set-min-level! :trace)
  (log/info "Starting clojure-dap with configuration:" opts)

  (log/info "Initialising Malli instrumentation")
  (mi/instrument! {:report (malli-pretty/thrower)})

  (log/info "Starting server...")
  (let [{:keys [server-complete anomalies-stream]}
        (server/run-io-wrapped
         {:input-reader (io/reader System/in)
          :output-writer (io/writer System/out)
          :debuggee (fake-debuggee/create)})]
    (s/consume #(log/error "Anomaly" %) anomalies-stream)
    (log/info "Server started in single session mode (multi session mode will come later)")
    @server-complete)
  (log/info "Server completed, will exit"))
