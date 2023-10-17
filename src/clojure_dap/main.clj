(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as rfs]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [malli.experimental :as mx]
            [malli.instrument :as mi]
            [de.otto.nom.core :as nom]
            [malli.dev.pretty :as malli-pretty]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure-dap.util :as util]
            [clojure-dap.server :as server]
            [clojure-dap.stream :as stream]
            [clojure-dap.protocol :as protocol]))

;; TODO Handle anomalies at all points. Needs more tests.
(mx/defn start-server-with-io
  :- [:map
      [:server [:fn d/deferred?]]
      [:anomalies-stream ::stream/stream]]
  "Runs the server and plugs everything together with the provided reader and writer. Will handle encoding and decoding of messages into the JSON wire format.

  Any anomalies from the client or the server are put into the anomalies-stream which is returned by this function alongside the server deferred."
  [{:keys [input-reader output-writer]}
   :- [:map
       [:input-reader ::stream/reader]
       [:output-writer ::stream/writer]]]

  (let [input-byte-stream (s/stream)
        input-message-stream (s/stream)
        output-stream (s/stream)
        anomalies-stream (s/stream)]

    (util/with-thread ::reader
      (stream/reader-into-stream!
       {:reader input-reader
        :stream input-byte-stream}))

    (util/with-thread ::writer
      (stream/stream-into-writer!
       ;; TODO Refactor this mapcat pattern into something reusable if it works.
       {:stream (->> output-stream
                     (s/mapcat
                      (fn [message]
                        (let [res (protocol/render-message message)]
                          (if (nom/anomaly? res)
                            (do
                              (s/put! anomalies-stream res)
                              nil)
                            [res])))))

        :writer output-writer}))

    (util/with-thread ::message-reader
      (let [input-char-stream (s/transform
                               (comp
                                (filter #(>= % 1))
                                (map char))
                               input-byte-stream)]
        (loop []
          (if (s/closed? input-byte-stream)
            (s/close! input-message-stream)
            (do
              (s/put! input-message-stream (stream/read-message input-char-stream))
              (recur))))))

    {:server (server/run
              {:input-stream (s/mapcat
                              (fn [res]
                                (if (nom/anomaly? res)
                                  (do
                                    (s/put! anomalies-stream res)
                                    nil)
                                  [res]))
                              input-message-stream)
               :output-stream output-stream})
     :anomalies-stream anomalies-stream}))

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
  (let [{:keys [server _anomalies-stream]}
        (start-server-with-io
         {:input-reader (io/reader System/in)
          :output-writer (io/writer System/out)})]
    (log/info "Server started in single session mode (multi session mode will come later)")
    @server)

  nil)
