(ns clojure-dap.client
  "Tools to connect a client up to a clojure-dap.io/stream-pair."
  (:require [malli.core :as m]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [de.otto.nom.core :as nom]
            [taoensso.timbre :as log]
            [clojure-dap.stream :as stream]))

(defn create
  "Takes a client stream pair and returns another stream pair containing parsed DAP messages. When feeding it DAP messages to send it'll do the encoding for you and send them to the client.

  If the client sends us a bad message, we shut everything down. Maybe we could handle bad messages and recover? I did try this originally but ran into a bug where a bad message from a client caused the stream/read-message function to block forever and behave weirdly.

  If we can solve that bug, we can keep this running even after a bad input."
  [outer-io-pair]
  (let [inner-io-pair (stream/io)
        anomalies (s/stream stream/*stream-buffer-size*)
        close-all! (fn []
                     (stream/close-io! inner-io-pair)
                     (stream/close-io! outer-io-pair)
                     (s/close! anomalies))]
    (d/future
      (try
        (loop []
          (if (or (s/closed? (:input inner-io-pair))
                  (s/closed? (:input outer-io-pair)))
            (close-all!)
            (let [message (stream/read-message (:input outer-io-pair))]
              (log/trace "(from client)" message)
              (if (nom/anomaly? message)
                (do
                  @(s/put! anomalies message)
                  (close-all!))
                (do
                  @(s/put! (:input inner-io-pair) message)
                  (recur))))))
        (catch Exception e
          (log/error e "Unexpected error in future reading from client")
          (close-all!))))

    (d/future
      (try
        (loop []
          (if (or (s/closed? (:output inner-io-pair))
                  (s/closed? (:output outer-io-pair)))
            (close-all!)
            (let [message (stream/render-message @(s/take! (:output inner-io-pair)))]
              (log/trace "(to client)" message)
              (if (nom/anomaly? message)
                @(s/put! anomalies message)
                @(s/put! (:output outer-io-pair) message))
              (recur))))
        (catch Exception e
          (log/error e "Unexpected error in future writing to client")
          (close-all!))))

    {:client-io inner-io-pair
     :anomalies anomalies}))
(m/=>
 create
 [:=>
  [:cat ::stream/io]
  [:map
   [:client-io ::stream/io]
   [:anomalies [:fn s/stream?]]]])
