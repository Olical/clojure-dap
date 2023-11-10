(ns clojure-dap.server.handler
  "The pure handler functions called by clojure-dap.server that take in a DAP request and return a DAP response."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [malli.experimental :as mx]
            [cognitect.anomalies :as anom]
            [de.otto.nom.core :as nom]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee :as debuggee]))

(def initialised-response-body
  {:supportsCancelRequest false
   :supportsConfigurationDoneRequest true})

(mx/defn handle-anomalous-client-input :- [:sequential ::protocol/message]
  "Takes some bad input that failed validation and turns it into some kind of error response."
  [{:keys [anomaly next-seq]}
   :- [:map
       [:anomaly ::schema/anomaly]
       [:next-seq ::protocol/next-seq-fn]]]
  (let [{::anom/keys [message]
         ::schema/keys [explanation humanized]}
        (nom/payload anomaly)

        {:keys [value]} explanation]
    [{:type "event"
      :event "output"
      :seq (next-seq)
      :body {:category "important"
             :output (str message ": " (str/join ", " humanized))
             :data value}}]))

(schema/define! ::attach-opts [:map [:clojure_dap ::debuggee/create-opts]])

(mx/defn handle-client-input :- [:sequential ::protocol/message]
  "Takes a message from a DAP client and a next-seq function (always returns the next sequence number, maintains it's own state) and returns any required responses in a seq of some kind."
  [{:keys [input next-seq debuggee!]}
   :- [:map
       [:input ::protocol/message]
       [:next-seq ::protocol/next-seq-fn]]]
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

      "attach"
      (if-let [anomaly (schema/validate ::attach-opts (:arguments input))]
        (handle-anomalous-client-input
         {:anomaly anomaly
          :next-seq next-seq})
        (let [debuggee-opts (get-in input [:arguments :clojure_dap])]
          (reset! debuggee! (debuggee/create debuggee-opts))
          [{:type "response"
            :command "attach"
            :seq (next-seq)
            :request_seq req-seq
            :success true
            :body {}}]))

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
