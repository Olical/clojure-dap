(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through it'll handle the rest. Understands DAP messages and eventually nREPL too, acting as the trade hub of all the various processes, servers and clients."
  (:require [taoensso.timbre :as log]
            [manifold.stream :as s]
            [malli.core :as m]
            [clojure-dap.schema :as schema]))

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
  "Takes a message from a DAP client and a next-seq function (always returns the next sequence number, maintains it's own state) and returns any required responses in a seq of some kind."
  [{:keys [input next-seq]}]
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
        :body {}}])))

(m/=>
 handle-client-input
 [:=>
  [:cat [:map
         [:input ::schema/message]
         [:next-seq [:function [:=> [:cat] number?]]]]]
  [:sequential ::schema/message]])

(defn run
  "Consumes messages from the input stream and writes the respones to the output stream. We work with Clojure data structures at this level of abstraction, another system should handle the encoding and decoding of DAP messages.

  Errors that occur in handle-client-input are fed into the output-stream as errors."
  [{:keys [input-stream output-stream]}]
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

     output-stream)
    nil))
(m/=>
 run
 [:=>
  [:cat [:map
         [:input-stream [:fn s/stream?]]
         [:output-stream [:fn s/stream?]]]]
  nil?])
