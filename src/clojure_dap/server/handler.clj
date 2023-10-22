(ns clojure-dap.server.handler
  (:require [malli.experimental :as mx]
            [clojure-dap.protocol :as protocol]))

(def initialised-response-body
  {:supportsCancelRequest false
   :supportsConfigurationDoneRequest true})

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
        :body initialised-response-body}
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
        :body {}}]

      "configurationDone"
      [{:type "response"
        :command "configurationDone"
        :seq (next-seq)
        :request_seq req-seq
        :success true
        :body {}}])))
