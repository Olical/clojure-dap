(ns clojure-dap.server.handler
  "The pure handler functions called by clojure-dap.server that take in a DAP request and return a DAP response."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [malli.experimental :as mx]
            [cognitect.anomalies :as anom]
            [de.otto.nom.core :as nom]
            [manifold.stream :as s]
            [camel-snake-kebab.core :as csk]
            [clojure-dap.schema :as schema]
            [clojure-dap.stream :as stream]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]
            [clojure-dap.debuggee.nrepl :as nrepl-debuggee]))

(def initialised-response-body
  {:supportsCancelRequest false
   :supportsConfigurationDoneRequest true
   :supportsSingleThreadExecutionRequests true
   :supportsEvaluateForHovers true
   :supportsSteppingGranularity false
   :supportsSetVariable false
   :supportsRestartRequest false})

(mx/defn socket-exception-anomaly?
  "Given an anomaly, returs true if it's caused by a java.net.SocketException."
  [a :- ::schema/anomaly]
  :- :boolean
  (= java.net.SocketException (type (:exception (nom/payload a)))))

(mx/defn handle-anomaly
  "If you give it an anomaly, it'll respond appropriately by returning errors (possibly also notifying the client of a disconnect!). If the argument isn't an anomaly though it'll return nil so you can continue on your merry way. Intended for use in an or statement."
  [a :- (schema/result :any)
   {:keys [resp input]}
   :- [:map
       [:input ::protocol/message]
       [:resp fn?]]]
  (when (nom/anomaly? a)
    (tel/log! :error ["Handling anomaly" a])

    (cond->
     [(resp
       {:success false
        :message (str (:command input) " command failed (" (nom/kind a) ")")})]

      (socket-exception-anomaly? a)
      (conj
       {:type "event"
        :event "terminated"
        :seq protocol/seq-placeholder
        :body {}}))))

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
  [{:keys [anomaly]}
   :- [:map
       [:anomaly ::schema/anomaly]]]
  [{:type "event"
    :event "output"
    :seq protocol/seq-placeholder
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
  "Takes a message from a DAP client and returns any required responses in a seq of some kind."
  [{:keys [input debuggee! output-stream]} :-
   [:map
    [:input ::protocol/message]
    [:debuggee! ::schema/atom]
    [:output-stream [:fn s/stream?]]]]
  (handle-client-input*
   {:input input
    :debuggee! debuggee!
    :debuggee @debuggee!
    :output-stream output-stream
    :resp (fn [m]
            (merge
             {:type "response"
              :command (:command input)
              :seq protocol/seq-placeholder
              :request_seq (:seq input)
              :success true
              :body {}}
             m))}))

(schema/define! ::create-debuggee-opts
  [:map
   [:type [:enum "fake" "nrepl"]]
   [:fake {:optional true} ::fake-debuggee/create-opts]
   [:nrepl {:optional true} ::nrepl-debuggee/create-opts]])

(schema/define! ::extra-opts
  [:maybe ::nrepl-debuggee/extra-opts])

(schema/define! ::attach-opts [:map [:clojure_dap {:optional true} ::create-debuggee-opts]])

(mx/defn create-debuggee :- (schema/result ::debuggee/debuggee)
  [opts :- ::create-debuggee-opts
   extra-opts :- ::extra-opts]
  (let [debuggee-opts (get opts (keyword (:type opts)) {})]
    (case (:type opts)
      "fake" (fake-debuggee/create debuggee-opts)
      "nrepl" (nrepl-debuggee/create debuggee-opts extra-opts))))

(defmethod handle-client-input* "initialize"
  [{:keys [resp]}]
  [(resp
    {:body initialised-response-body})
   {:type "event"
    :event "initialized"
    :seq protocol/seq-placeholder}])

(defmethod handle-client-input* "disconnect"
  [{:keys [resp]}]
  [(resp {})
   {:type "event"
    :event "terminated"
    :seq protocol/seq-placeholder
    :body {}}])

(defmethod handle-client-input* "configurationDone"
  [{:keys [resp]}]
  [(resp {})])

(defmethod handle-client-input* "attach"
  [{:keys [input resp debuggee! output-stream]}]
  (if-let [{:keys [explanation value]}
           (some-> (schema/validate ::attach-opts (:arguments input))
                   (render-anomaly))]
    [(resp
      {:success false
       :message explanation
       :body {:value value}})]
    (let [debuggee-opts (get-in input [:arguments :clojure_dap]
                                {:type "nrepl"})
          debuggee (create-debuggee debuggee-opts {:output-stream output-stream})]
      (if (nom/anomaly? debuggee)
        (let [{:keys [explanation]} (render-anomaly debuggee)]
          [(resp
            {:success false
             :message (str/join
                       "\n"
                       ["Failed to connect to nREPL."
                        "Do you have one running?"
                        "Is there a .nrepl-port file with a port inside it?"
                        debuggee-opts
                        explanation])
             :body {:value (:arguments input)}})])
        (do
          (reset! debuggee! debuggee)
          [(resp {})])))))

(def missing-debuggee-warning
  {:success false
   :message "Debuggee not initialised, you must attach to one first"})

(defn- with-debuggee
  "Common pattern for handlers that require an attached debuggee.
  Calls (debuggee-fn debuggee) to get the result, handles anomalies,
  and wraps the result with (body-fn result) to build the response body."
  [{:keys [debuggee resp] :as opts} debuggee-fn body-fn]
  (if debuggee
    (let [res (debuggee-fn debuggee)]
      (or (handle-anomaly res opts)
          [(resp {:body (body-fn res)})]))
    [(resp missing-debuggee-warning)]))

(defmethod handle-client-input* "setBreakpoints"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/set-breakpoints % {:source (get-in input [:arguments :source])
                                  :breakpoints (get-in input [:arguments :breakpoints])})
    identity))

(defmethod handle-client-input* "evaluate"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/evaluate % {:expression (get-in input [:arguments :expression])})
    (fn [res] {:variablesReference 0 :result (:result res)})))

(defmethod handle-client-input* "threads"
  [opts]
  (with-debuggee opts
    debuggee/threads
    (fn [res] {:threads (:threads res)})))

(defn- kebab-case-arguments
  "Extract and kebab-case argument keys from a DAP input message."
  [input ks]
  (-> (get input :arguments)
      (select-keys ks)
      (update-keys csk/->kebab-case)))

(defn- kebab-case-format
  "Kebab-case the :format sub-map if present."
  [opts]
  (cond-> opts
    (:format opts)
    (update :format update-keys csk/->kebab-case)))

(defmethod handle-client-input* "stackTrace"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/stack-trace % (kebab-case-format
                              (kebab-case-arguments input #{:threadId :startFrame :levels :format})))
    identity))

(defmethod handle-client-input* "scopes"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/scopes % {:frame-id (get-in input [:arguments :frameId])})
    identity))

(defmethod handle-client-input* "variables"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/variables % (kebab-case-format
                            (kebab-case-arguments input #{:variablesReference :filter :start :count :format})))
    identity))

(defmethod handle-client-input* "continue"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/continue % {:thread-id (get-in input [:arguments :threadId])})
    (fn [_] {:allThreadsContinued true})))

(defmethod handle-client-input* "next"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/next-request % {:thread-id (get-in input [:arguments :threadId])})
    (constantly {})))

(defmethod handle-client-input* "stepIn"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/step-in % {:thread-id (get-in input [:arguments :threadId])})
    (constantly {})))

(defmethod handle-client-input* "stepOut"
  [{:keys [input] :as opts}]
  (with-debuggee opts
    #(debuggee/step-out % {:thread-id (get-in input [:arguments :threadId])})
    (constantly {})))

(defmethod handle-client-input* "setExceptionBreakpoints"
  [{:keys [resp]}]
  [(resp {:body {:breakpoints []}})])
