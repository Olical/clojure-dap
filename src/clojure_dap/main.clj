(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as rfs]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [malli.core :as m]
            [malli.instrument :as mi]
            [malli.dev.pretty :as malli-pretty]
            [manifold.stream :as s]
            [clojure-dap.client :as client]
            [clojure-dap.server :as server]
            [clojure-dap.stream :as stream]))

(defn run
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts]
  (log/merge-config!
   {:appenders {;; Never log to stdout through timbre.
                ;; This is because the DAP server may be communicating with a client over stdout.
                ;; We also install extra deps that hook up essentially every other Java
                ;; logging system to timbre, so it all goes out under stderr with the same formatting.
                ;; Disabled for now, just using spit appender.
                :println nil #_(appenders/println-appender {:stream :*err*})

                ;; TODO Ensure this is cross platform.
                ;; And will we end up with multiple processes sharing the same file?
                ;; Also nvim users sometimes move their cache dir, so we should write to our own maybe, or tmp.
                :spit (appenders/spit-appender {:fname (str (rfs/expand-home "~/.cache/nvim/clojure-dap.log"))})}
    :middleware [#(assoc % :hostname_ "-")]})

  (log/set-min-level! :trace)
  (log/info "Starting clojure-dap with configuration:" opts)

  (log/info "Initialising Malli instrumentation")
  (mi/instrument! {:report (malli-pretty/thrower)})

  (let [{:keys [client-io anomalies]}
        (client/create
         (stream/java-io->io
          {:reader (io/reader System/in)
           :writer (io/writer System/out)}))
        server (server/start
                {:client-io client-io
                 :nrepl-io (stream/io)})]
    (s/map #(log/error "Unhandled anomaly!" %) anomalies)

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable
      (fn []
        (log/info "Shutdown hook triggered, shutting down...")
        (server/stop server)
        (stream/close-io! client-io)
        (shutdown-agents)
        (log/info "All done, goodbye!"))))

    (log/info "Server started in single session mode (multi session mode will come later)")

    @(:stop-promise! server)))
(m/=>
 run
 [:=>
  [:cat
   [:map]]
  any?])
