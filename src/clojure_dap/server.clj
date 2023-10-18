(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through it'll handle the rest. Understands DAP messages and eventually nREPL too, acting as the trade hub of all the various processes, servers and clients."
  (:require [taoensso.timbre :as log]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [malli.experimental :as mx]
            [de.otto.nom.core :as nom]
            [clojure-dap.util :as util]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.stream :as stream]))

(mx/defn auto-seq :- [:function [:=> [:cat] :int]]
  "Returns a function that when called returns a sequence number one greater than the last time it was called. Starts at 1."
  []
  (let [state (atom 0)]
    (fn []
      (swap! state inc))))

(mx/defn handle-client-input :- [:sequential ::protocol/message]
  "Takes a message from a DAP client and a next-seq function (always returns the next sequence number, maintains it's own state) and returns any required responses in a seq of some kind."
  [{:keys [input next-seq]}
   :- [:map
       [:input ::protocol/message]
       [:next-seq [:function [:=> [:cat] number?]]]]]
  (let [req-seq (:seq input)]
    (case (:command input)
      "initialize"
      [{:type "response"
        :command "initialize"
        :seq (next-seq)
        :request_seq req-seq
        :success true
        :body {:supportsCancelRequest false

               ;; TODO Enable this one when we support it.
               ;; It's called when the initialization is complete.
               :supportsConfigurationDoneRequest false}}
       {:type "event"
        :event "initialized"
        :seq (next-seq)}]

      "launch"
      [{:type "response"
        :command "launch"
        :seq (next-seq)
        :request_seq req-seq
        :success true
        :body {}}]

      "disconnect"
      [{:type "response"
        :command "disconnect"
        :seq (next-seq)
        :request_seq req-seq
        :success true
        :body {}}])))

(mx/defn run :- [:fn d/deferred?]
  "Consumes messages from the input stream and writes the respones to the output stream. We work with Clojure data structures at this level of abstraction, another system should handle the encoding and decoding of DAP messages.

  Errors that occur in handle-client-input are fed into the output-stream as errors."
  [{:keys [input-stream output-stream]}
   :- [:map
       [:input-stream [:fn s/stream?]]
       [:output-stream [:fn s/stream?]]]]
  (let [next-seq (auto-seq)]
    (s/connect-via
     input-stream
     (fn [input]
       (->> (try
              (handle-client-input
               {:input input
                :next-seq next-seq})
              (catch Throwable e
                (log/error e "Failed to handle client input")
                [{:seq (next-seq)
                  :request_seq (:seq input)
                  :type "response"
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
        anomalies-stream (s/stream)]

    (s/on-closed output-stream #(s/close! anomalies-stream))

    {:anomalies-stream anomalies-stream
     :server-complete
     (d/zip
      (util/with-thread ::reader
        (stream/reader-into-stream!
         {:reader input-reader
          :stream input-byte-stream}))

      (util/with-thread ::writer
        @(stream/stream-into-writer!
          {:stream (stream/partition-anomalies output-stream protocol/render-message anomalies-stream)
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
                (if (nom/anomaly? message)
                  @(s/put! anomalies-stream message)
                  @(s/put! input-message-stream message))
                (recur))))))

      (run
       {:input-stream (stream/partition-anomalies input-message-stream identity anomalies-stream)
        :output-stream output-stream}))}))
