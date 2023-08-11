(ns clojure-dap.schema
  "Schema registration and validation."
  (:require [malli.core :as m]
            [malli.util :as mu]
            [malli.registry :as mr]
            [cognitect.anomalies :as anom]
            [de.otto.nom.core :as nom]))

(defonce schemas! (atom (merge (m/default-schemas) (mu/schemas))))

;; We use a global Malli registry, for better or for worse.
;; To me this is the good bits of spec with the great bits of Malli.
(mr/set-default-registry! (mr/mutable-registry schemas!))

;; A cache of explainer functions used by the validate function.
;; Should make repeated validate calls fairly efficient.
(defonce explainers! (atom {}))

(defn define!
  "Define a new schema, accepts a qualified keyword and a schema. Will be precompiled into a explainer. It may refer to other previously defined schemas by their qualified keyword."
  [id schema]
  (swap! schemas! assoc id schema)

  ;; Reset the cache each time so we don't get into weird dev states.
  (reset! explainers! {})

  nil)
(m/=> define! [:=> [:cat :qualified-keyword some?] nil?])

(define! ::id :qualified-keyword)
(define! ::anomaly [:fn nom/abominable?])

(defn result
  "Wraps the given schema in [:or ... :clojure-dap.schema/anomaly], prompting callers to handle your potential failure cases. Sort of modeled on Rust's Result<T, E> type which can return Ok(T) or Err(E)."
  [schema]
  [:or schema ::anomaly])

(defn- upsert-explainer!
  "Either return the explainer if compiled already or compile the explainer and cache it. Can throw malli errors if the schema is bad."
  [id]
  (let [schema (get @schemas! id)]
    (if schema
      (if-let [explainer (get @explainers! id)]
        explainer
        (let [explainer (m/explainer (get @schemas! id))]
          (swap! explainers! id explainer)
          explainer))
      (nom/fail
       ::anom/not-found
       {::anom/message (str "Unknown schema: " id)}))))
(m/=> upsert-explainer! [:=> [:cat ::id] (result fn?)])

(defn validate
  "Validates the value against the schema referred to by the qualified keyword. Returns nil when everything is okay, returns an anomaly map explaining the issue when there is a problem."
  [id value]
  (nom/let-nom> [explainer (upsert-explainer! id)]
    (when-let [explanation (explainer value)]
      (nom/fail
       ::anom/incorrect
       {::anom/message (str "Failed to validate against schema " id)
        ::explanation explanation}))))
(m/=> validate [:=> [:cat ::id any?] (result nil?)])

;; Schemas based on https://microsoft.github.io/debug-adapter-protocol/specification

;; interface ProtocolMessage {
;;   /**
;;    * Sequence number of the message (also known as message ID). The `seq` for
;;    * the first message sent by a client or debug adapter is 1, and for each
;;    * subsequent message is 1 greater than the previous message sent by that
;;    * actor. `seq` can be used to order requests, responses, and events, and to
;;    * associate requests with their corresponding responses. For protocol
;;    * messages of type `request` the sequence number can be used to cancel the
;;    * request.
;;    */
;;   seq: number;
;;
;;   /**
;;    * Message type.
;;    * Values: 'request', 'response', 'event', etc.
;;    */
;;   type: 'request' | 'response' | 'event' | string;
;; }

(define! ::protocol-message
  [:map
   [:seq number?]
   [:type [:or [:enum "request" "response" "event"] string?]]])

;; interface Request extends ProtocolMessage {
;;   type: 'request';
;;
;;   /**
;;    * The command to execute.
;;    */
;;   command: string;
;;
;;   /**
;;    * Object containing arguments for the command.
;;    */
;;   arguments?: any;
;; }

(define! ::request
  (mu/merge
   ::protocol-message
   [:map
    [:type [:enum "request"]]
    [:command string?]
    [:arguments {:optional true} any?]]))

;; interface Event extends ProtocolMessage {
;;   type: 'event';
;;
;;   /**
;;    * Type of event.
;;    */
;;   event: string;
;;
;;   /**
;;    * Event-specific information.
;;    */
;;   body?: any;
;; }

(define! ::event
  (mu/merge
   ::protocol-message
   [:map
    [:type [:enum "event"]]
    [:event string?]
    [:body {:optional true} any?]]))

;; interface Response extends ProtocolMessage {
;;   type: 'response';
;;
;;   /**
;;    * Sequence number of the corresponding request.
;;    */
;;   request_seq: number;
;;
;;   /**
;;    * Outcome of the request.
;;    * If true, the request was successful and the `body` attribute may contain
;;    * the result of the request.
;;    * If the value is false, the attribute `message` contains the error in short
;;    * form and the `body` may contain additional information (see
;;    * `ErrorResponse.body.error`).
;;    */
;;   success: boolean;
;;
;;   /**
;;    * The command requested.
;;    */
;;   command: string;
;;
;;   /**
;;    * Contains the raw error in short form if `success` is false.
;;    * This raw error might be interpreted by the client and is not shown in the
;;    * UI.
;;    * Some predefined values exist.
;;    * Values: 
;;    * 'cancelled': the request was cancelled.
;;    * 'notStopped': the request may be retried once the adapter is in a 'stopped'
;;    * state.
;;    * etc.
;;    */
;;   message?: 'cancelled' | 'notStopped' | string;
;;
;;   /**
;;    * Contains request result if success is true and error details if success is
;;    * false.
;;    */
;;   body?: any;
;; }

(define! ::response
  (mu/merge
   ::protocol-message
   [:map
    [:type [:enum "response"]]
    [:request_seq number?]
    [:success boolean?]
    [:command string?]
    [:message {:optional true} [:or [:enum "cancelled" "notStopped"] string?]]
    [:body {:optional true} any?]]))
