(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through it'll handle the rest. Understands DAP messages and eventually nREPL too, acting as the trade hub of all the various processes, servers and clients."
  (:require [taoensso.timbre :as log]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [malli.experimental :as mx]
            [de.otto.nom.core :as nom]
            [clojure-dap.util :as util]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.stream :as stream]
            [clojure-dap.server.handler :as handler]))

(mx/defn auto-seq :- ::protocol/next-seq-fn
  "Returns a function that when called returns a sequence number one greater than the last time it was called. Starts at 1."
  []
  (let [state! (atom 0)]
    (fn []
      (swap! state! inc))))

(mx/defn run :- [:fn d/deferred?]
  "Consumes messages from the input stream and writes the respones to the output stream. We work with Clojure data structures at this level of abstraction, another system should handle the encoding and decoding of DAP messages.

  Errors that occur in handle-client-input are fed into the output-stream as errors."
  [{:keys [input-stream output-stream]}
   :- [:map
       [:input-stream [:fn s/stream?]]
       [:output-stream [:fn s/stream?]]]]
  (let [debuggee! (atom ::no-debuggee)]
    (s/connect-via
     input-stream
     (fn [input]
       (->> (try
              (if (nom/anomaly? input)
                (handler/handle-anomalous-client-input
                 {:anomaly input})
                (handler/handle-client-input
                 {:input input
                  :output-stream output-stream
                  :debuggee! debuggee!}))
              (catch Throwable e
                (log/error e "Failed to handle client input")
                [{:request_seq (:seq input)
                  :type "response"
                  :seq protocol/seq-placeholder
                  :command (:command input)
                  :success false
                  :message (str "Error while handling input\n" (ex-message e))}]))
            (s/put-all! output-stream)))

     output-stream)))

(mx/defn run-io-wrapped
  :- [:map
      [:server-complete [:fn d/deferred?]]
      [:anomalies-stream ::stream/stream]]
  "Runs the server and plugs everything together with the provided reader and writer. Will handle encoding and decoding of messages into the JSON wire format.

  Any anomalies from the client or the server are put into the anomalies-stream which is returned by this function.

  A deferred that waits for all threads and streams to complete is also returned. You can wait on that with deref until everything has drained and completed."
  [{:keys [input-reader output-writer]}
   :- [:map
       [:input-reader ::stream/reader]
       [:output-writer ::stream/writer]]]

  (let [input-byte-stream (s/stream)
        input-message-stream (s/stream)
        output-stream (s/stream)
        anomalies-stream (s/stream)
        next-seq (auto-seq)]

    (s/on-closed
     output-stream
     (fn []
       (log/info "output-stream closed, closing anomalies-stream")
       (s/close! anomalies-stream)))

    {:anomalies-stream anomalies-stream
     :server-complete
     (d/zip
      (util/with-thread ::reader
        (stream/reader-into-stream!
         {:reader input-reader
          :stream input-byte-stream}))

      (util/with-thread ::writer
        @(stream/stream-into-writer!
          {:stream (stream/partition-anomalies
                    output-stream
                    (fn [message]
                      (let [message (assoc message :seq (next-seq))]
                        (log/trace "SEND" message)
                        (protocol/render-message message)))
                    anomalies-stream)
           :writer output-writer}))

      (util/with-thread ::message-reader
        (let [input-char-stream
              (s/mapcat
               (fn [x]
                 (if (and (int? x) (>= x 0))
                   [(char x)]
                   (log/warn "Weird input byte, can't turn it into a character:" x)))
               input-byte-stream)]
          (loop []
            (if (and (s/closed? input-byte-stream) (s/drained? input-byte-stream))
              (s/close! input-message-stream)
              (let [message (stream/read-message input-char-stream)]
                (log/trace "RECV" message)
                @(s/put! input-message-stream message)
                (recur))))))

      (run
       {:input-stream input-message-stream
        :output-stream output-stream}))}))
