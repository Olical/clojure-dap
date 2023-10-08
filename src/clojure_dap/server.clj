(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through and an nREPL server to drive from user inputs and it'll handle the rest. Understands both the DAP and nREPL messages, relaying ideas between the two."
  (:require [clojure.core.match :refer [match]]
            [taoensso.timbre :as log]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [malli.core :as m]
            [clojure-dap.schema :as schema]
            [clojure-dap.util :as util]
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
  "Takes a message from a DAP client and responds accordingly."
  [{:keys [input respond]}]
  (log/trace "Handling input from client" input)

  (match input
    {:type "request"
     :command "initialize"}
    (respond
     {:success true
      :body {:supportsCancelRequest false}})

    ;; We should rarely (if ever) get here because the Malli instrumentation.
    :else
    (respond
     {:success false
      :message "Cound not handle client input."})))
(m/=>
 handle-client-input
 [:=>
  [:cat [:map
         [:input ::schema/message]
         [:respond [:function [:=> [:cat map?] any?]]]]]
  any?])

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

    (util/with-thread ::client-read-loop
      (loop []
        (let [input @(d/alt stop-promise! (s/take! (:input client-io)))]
          (if (= input ::stop)
            ::stopped
            (let [respond (fn [message]
                            (s/put! (:output client-io)
                                    (merge
                                     {:type "response"
                                      :seq (next-seq)
                                      :request_seq (:seq input)
                                      :command (:command input)}
                                     message)))]
              (try
                (handle-client-input
                 {:input input
                  :respond respond})
                (catch Exception e
                  (log/error e "Error from handle-client-input")
                  (respond
                   {:success false
                    :message (str "Error while handling input: " (.getMessage e))})))
              (recur))))))

    (util/with-thread ::nrepl-read-loop
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
