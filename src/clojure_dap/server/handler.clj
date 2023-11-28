(ns clojure-dap.server.handler
  "The pure handler functions called by clojure-dap.server that take in a DAP request and return a DAP response."
  (:require [clojure.string :as str]
            [malli.experimental :as mx]
            [taoensso.timbre :as log]
            [cognitect.anomalies :as anom]
            [de.otto.nom.core :as nom]
            [clojure-dap.schema :as schema]
            [clojure-dap.stream :as stream]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]
            [clojure-dap.debuggee.nrepl :as nrepl-debuggee]))

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
    {:explanation (str
                   "[" (nom/kind anomaly) "] "
                   (or message "No message")
                   (when (seq humanized)
                     (str ": " (str/join ", " humanized))))
     :value value}))

(mx/defn handle-anomalous-client-input :- [:sequential ::protocol/message]
  "Takes some bad input that failed validation and turns it into some kind of error response."
  [{:keys [anomaly next-seq]}
   :- [:map
       [:anomaly ::schema/anomaly]
       [:next-seq ::protocol/next-seq-fn]]]
  [{:type "event"
    :event "output"
    :seq (next-seq)
    :body
    (if (= ::stream/closed (nom/kind anomaly))
      {:category "important"
       :output "Input stream closed, clojure-dap will shut down."}
      (let [{:keys [explanation value]} (render-anomaly anomaly)]
        {:category "important"
         :output explanation
         :data value}))}])

(defmulti handle-client-input* #(get-in % [:input :command]))

(mx/defn handle-client-input :- [:sequential ::protocol/message]
  "Takes a message from a DAP client and a next-seq function (always returns the next sequence number, maintains it's own state) and returns any required responses in a seq of some kind."
  [{:keys [input next-seq debuggee!]}
   :- [:map
       [:input ::protocol/message]
       [:next-seq ::protocol/next-seq-fn]
       [:debuggee! ::schema/atom]]]
  (handle-client-input*
   {:input input
    :next-seq next-seq
    :debuggee! debuggee!
    :debuggee @debuggee!
    :resp (fn [m]
            (merge
             {:type "response"
              :command (:command input)
              :seq (next-seq)
              :request_seq (:seq input)
              :success true
              :body {}}
             m))}))

(schema/define! ::create-debuggee-opts
  [:map
   [:type [:enum "fake" "nrepl"]]
   [:fake {:optional true} ::fake-debuggee/create-opts]
   [:nrepl {:optional true} ::nrepl-debuggee/create-opts]])

(schema/define! ::attach-opts [:map [:clojure_dap {:optional true} ::create-debuggee-opts]])

(mx/defn create-debuggee :- (schema/result ::debuggee/debuggee)
  [opts :- ::create-debuggee-opts]
  (let [debuggee-opts (get opts (keyword (:type opts)) {})]
    (case (:type opts)
      "fake" (fake-debuggee/create debuggee-opts)
      "nrepl" (nrepl-debuggee/create debuggee-opts))))

(defmethod handle-client-input* "initialize"
  [{:keys [next-seq resp]}]
  [(resp
    {:body initialised-response-body})
   {:type "event"
    :event "initialized"
    :seq (next-seq)}])

(defmethod handle-client-input* "disconnect"
  [{:keys [resp]}]
  [(resp {})])

(defmethod handle-client-input* "configurationDone"
  [{:keys [resp]}]
  [(resp {})])

(defmethod handle-client-input* "attach"
  [{:keys [input resp debuggee!]}]
  (if-let [{:keys [explanation value]}
           (some-> (schema/validate ::attach-opts (:arguments input))
                   (render-anomaly))]
    [(resp
      {:success false
       :message explanation
       :body {:value value}})]
    (let [debuggee-opts (get-in input [:arguments :clojure_dap])
          debuggee (create-debuggee (or debuggee-opts {:type "nrepl"}))]
      (if (nom/anomaly? debuggee)
        (let [{:keys [explanation]} (render-anomaly debuggee)]
          [(resp
            {:success false
             :message explanation
             :body {:value (:arguments input)}})])
        (do
          (reset! debuggee! debuggee)
          [(resp {})])))))

(defmethod handle-client-input* "setBreakpoints"
  [{:keys [debuggee resp]}]
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
       :message "Debuggee not initialised, you must attach to one first"}))])

(defmethod handle-client-input* "evaluate"
  [{:keys [debuggee resp input]}]
  [(if debuggee
     (let [res (debuggee/evaluate
                debuggee
                {:expression (get-in input [:arguments :expression])})]
       (if (nom/anomaly? res)
         (resp
          {:success false
           :message "Evaluate failed"})
         (resp
          {:body
           {:variablesReference 0
            :result (:result res)}})))
     (resp
      {:success false
       :message "Debuggee not initialised, you must attach to one first"}))])
