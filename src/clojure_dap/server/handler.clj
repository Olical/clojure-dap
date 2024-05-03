(ns clojure-dap.server.handler
  "The pure handler functions called by clojure-dap.server that take in a DAP request and return a DAP response."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
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
   :supportsConfigurationDoneRequest true})

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
    (log/error "Handling anomaly" a)

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
  [(resp {})])

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

(defmethod handle-client-input* "setBreakpoints"
  [{:keys [debuggee resp input] :as opts}]
  (if debuggee
    (let [res (debuggee/set-breakpoints
               debuggee
               {:source (get-in input [:arguments :source])
                :breakpoints (get-in input [:arguments :breakpoints])})]
      (or (handle-anomaly res opts)
          [(resp {:body res})]))
    [(resp missing-debuggee-warning)]))

(defmethod handle-client-input* "evaluate"
  [{:keys [debuggee resp input] :as opts}]
  (if debuggee
    (let [res (debuggee/evaluate
               debuggee
               {:expression (get-in input [:arguments :expression])})]
      (or (handle-anomaly res opts)
          [(resp
            {:body
             {:variablesReference 0
              :result (:result res)}})]))
    [(resp missing-debuggee-warning)]))

(defmethod handle-client-input* "threads"
  [{:keys [debuggee resp _input] :as opts}]
  (if debuggee
    (let [res (debuggee/threads debuggee)]
      (or (handle-anomaly res opts)
          [(resp
            {:body {:threads (:threads res)}})]))
    [(resp missing-debuggee-warning)]))

(defmethod handle-client-input* "stackTrace"
  [{:keys [debuggee resp input] :as opts}]
  (if debuggee
    (let [stack-trace-opts
          (-> (get input :arguments)
              (select-keys #{:threadId :startFrame :levels :format})
              (update-keys csk/->kebab-case))

          res (debuggee/stack-trace
               debuggee
               (cond-> stack-trace-opts
                 (:format stack-trace-opts)
                 (update :format update-keys csk/->kebab-case)))]
      (or (handle-anomaly res opts)
          [(resp
            {:body {}})]))
    [(resp missing-debuggee-warning)]))

(defmethod handle-client-input* "scopes"
  [{:keys [debuggee resp input] :as opts}]
  (if debuggee
    (let [res (debuggee/scopes
               debuggee
               {:frame-id (get-in input [:arguments :frameId])})]
      (or (handle-anomaly res opts)
          [(resp
            {:body {}})]))
    [(resp missing-debuggee-warning)]))

(defmethod handle-client-input* "variables"
  [{:keys [debuggee resp input] :as opts}]
  (if debuggee
    (let [variables-opts
          (-> (get input :arguments)
              (select-keys #{:variablesReference :filter :start :count :format})
              (update-keys csk/->kebab-case))

          res (debuggee/variables
               debuggee
               (cond-> variables-opts
                 (:format variables-opts)
                 (update :format update-keys csk/->kebab-case)))]
      (or (handle-anomaly res opts)
          [(resp
            {:body {}})]))
    [(resp missing-debuggee-warning)]))
