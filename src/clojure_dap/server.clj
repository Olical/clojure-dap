(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through and an nREPL server to drive from user inputs and it'll handle the rest. Understands both the DAP and nREPL messages, relaying ideas between the two."
  (:require [clojure.core.match :refer [match]]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [malli.core :as m]
            [clojure-dap.stream :as stream]))

(defn auto-seq
  "Returns a function that when called returns a sequence number one greater than the last time it was called. Starts at 1."
  []
  (let [state (atom 0)]
    (fn []
      (swap! state inc))))
(m/=>
 auto-seq
 [:=>
  [:cat]
  [:function
   [:=> [:cat] number?]]])

(defn handle-client-input
  "Takes a message from a DAP client and acts accordingly."
  [{:keys [input respond]}]
  (match input
    {:type "request"
     :command "initialize"}
    (respond
     {:type "response"
      :command "initialize"
      :success true
      :body {:supportsCancelRequest false}})

    {:command command}
    (respond
     {:type "response"
      :command command
      :success false
      :message "Unknown or unsupported command."})))

(defn start
  "Creates a new server that contains a few processes. Returns a server that you can pass to the stop function to stop. It speaks :clojure-dap.schema/message maps, something outside of this should translate those messages to and from the DAP wire format.

  Assumes the messages heading in and out of these streams are schema checked elsewhere when they're encoded and decoded.

  This is essentially the bridge between a DAP client and an nREPL."
  [{:keys [client-io nrepl-io]}]
  (let [next-seq (auto-seq)
        stop-promise! (promise)
        stop-fn (fn []
                  (deliver stop-promise! ::stop)
                  true)]

    ;; Client read loop
    (d/future
      (loop []
        (let [input @(d/alt stop-promise! (s/take! (:input client-io)))]
          (if (= input ::stop)
            ::stopped
            (do
              (handle-client-input
               {:input input
                :respond (fn [message]
                           (s/put! (:output client-io)
                                   (merge
                                    {:seq (next-seq)
                                     :request_seq (:seq input)}
                                    message)))})
              (recur))))))

    ;; nREPL read loop
    (d/future
      (loop []
        (let [input @(d/alt stop-promise! (s/take! (:input nrepl-io)))]
          (if (= input ::stop)
            ::stopped
            (recur)))))

    {:stop stop-fn}))
(m/=>
 start
 [:=>
  [:cat [:map
         [:client-io ::stream/io]
         [:nrepl-io ::stream/io]]]
  [:map [:stop fn?]]])

(defn stop
  "Stops the given server process."
  [server]
  ((:stop server))
  nil)
(m/=>
 stop
 [:=>
  [:cat [:map [:stop fn?]]]
  nil?])
