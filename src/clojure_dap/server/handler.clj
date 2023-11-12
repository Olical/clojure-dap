(ns clojure-dap.server.handler
  "The pure handler functions called by clojure-dap.server that take in a DAP request and return a DAP response."
  (:require [clojure.string :as str]
            [malli.experimental :as mx]
            [cognitect.anomalies :as anom]
            [de.otto.nom.core :as nom]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(def initialised-response-body
  {:supportsCancelRequest false
   :supportsConfigurationDoneRequest true})

(mx/defn render-anomaly
  "Render an anomaly into a humanized explanation alongside the bad value we were checking."
  [anomaly :- ::schema/anomaly]
  :- [:map
      [:explanation :string]
      [:value any?]]
  (let [{::anom/keys [message]
         ::schema/keys [explanation humanized]}
        (nom/payload anomaly)

        {:keys [value]} explanation]
    {:explanation (str message ": " (str/join ", " humanized))
     :value value}))

(mx/defn handle-anomalous-client-input :- [:sequential ::protocol/message]
  "Takes some bad input that failed validation and turns it into some kind of error response."
  [{:keys [anomaly next-seq]}
   :- [:map
       [:anomaly ::schema/anomaly]
       [:next-seq ::protocol/next-seq-fn]]]
  (let [{:keys [explanation value]} (render-anomaly anomaly)]
    [{:type "event"
      :event "output"
      :seq (next-seq)
      :body {:category "important"
             :output explanation
             :data value}}]))

(schema/define! ::create-debuggee-opts [:map [:type [:enum "fake"]]])
(schema/define! ::attach-opts [:map [:clojure_dap ::create-debuggee-opts]])

(mx/defn create-debuggee :- ::debuggee/debuggee
  [opts :- ::create-debuggee-opts]
  (case (:type opts)
    "fake" (fake-debuggee/create)))

(mx/defn handle-client-input :- [:sequential ::protocol/message]
  "Takes a message from a DAP client and a next-seq function (always returns the next sequence number, maintains it's own state) and returns any required responses in a seq of some kind."
  [{:keys [input next-seq debuggee!]}
   :- [:map
       [:input ::protocol/message]
       [:next-seq ::protocol/next-seq-fn]]]
  (let [req-seq (:seq input)
        resp (fn [m]
               (merge
                {:type "response"
                 :command (:command input)
                 :seq (next-seq)
                 :request_seq req-seq
                 :success true
                 :body {}}
                m))
        debuggee @debuggee!]

    (case (:command input)
      "initialize"
      [(resp
        {:body initialised-response-body})
       {:type "event"
        :event "initialized"
        :seq (next-seq)}]

      "disconnect"
      [(resp {})]

      "configurationDone"
      [(resp {})]

      "attach"
      (if-let [{:keys [explanation value]}
               (some-> (schema/validate ::attach-opts (:arguments input))
                       (render-anomaly))]
        [(resp
          {:success false
           :message explanation
           :body {:value value}})]
        (let [debuggee-opts (get-in input [:arguments :clojure_dap])]
          (reset! debuggee! (create-debuggee debuggee-opts))
          [(resp {})]))

      "setBreakpoints"
      [(if debuggee
         (let [res (debuggee/set-breakpoints
                    debuggee
                    {})]
           (if (nom/anomaly? res)
             (resp
              {:success false
               :message "Setting breakpoints failed"})
             (resp
              {})))
         (resp
          {:success false
           :message "Debuggee not initialised, you must attach to one first"}))])))
