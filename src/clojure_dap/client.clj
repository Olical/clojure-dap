(ns clojure-dap.client
  "Tools to connect a client up to a clojure-dap.io/stream-pair."
  (:require [malli.core :as m]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [de.otto.nom.core :as nom]
            [clojure-dap.stream :as stream]))

(defn create
  "Takes a client stream pair and returns another stream pair containing parsed DAP messages. When feeding it DAP messages to send it'll do the encoding for you and send them to the client."
  [outer-io-pair]
  (let [inner-io-pair (stream/io)
        anomalies (s/stream stream/*stream-buffer-size*)
        close-all! (fn []
                     (s/close! anomalies)
                     (stream/close-io! inner-io-pair)
                     (stream/close-io! outer-io-pair))]
    (d/future
      (loop []
        (if (or (s/closed? (:input inner-io-pair))
                (s/closed? (:input outer-io-pair)))
          (close-all!)
          (let [message (stream/read-message (:input outer-io-pair))]
            (if (nom/anomaly? message)
              @(s/put! anomalies message)
              @(s/put! (:input inner-io-pair) message))
            (recur)))))

    (d/future
      (loop []
        (if (or (s/closed? (:output inner-io-pair))
                (s/closed? (:output outer-io-pair)))
          (close-all!)
          (let [message (stream/render-message @(s/take! (:output inner-io-pair)))]
            (if (nom/anomaly? message)
              @(s/put! anomalies message)
              @(s/put! (:output outer-io-pair) message))
            (recur)))))

    {:client-io inner-io-pair
     :anomalies anomalies}))
(m/=>
 create
 [:=>
  [:cat ::stream/io]
  [:map
   [:client-io ::stream/io]
   [:anomalies [:fn s/stream?]]]])
