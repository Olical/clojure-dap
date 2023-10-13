(ns clojure-dap.schema
  "Schema registration and validation."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [malli.registry :as mr]
            [json-schema.core :as json-schema]
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
  "Define a new schema, accepts a qualified keyword and a schema. Will be precompiled into a explainer. It may refer to other previously defined schemas by their qualified keyword. Returns the keyword for the schema you're defining so you can embed it in other schemas."
  [id schema]
  (swap! schemas! assoc id schema)

  ;; Reset the cache each time so we don't get into weird dev states.
  (reset! explainers! {})

  id)
(m/=> define! [:=> [:cat :qualified-keyword some?] :qualified-keyword])

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
        ::explanation explanation
        ::humanized (me/humanize explanation)}))))
(m/=> validate [:=> [:cat ::id any?] (result nil?)])

(defn dap-json-schema->malli [definition-key]
  (let [prepared-schema
        (json-schema/prepare-schema
         {:$schema "http://json-schema.org/draft-07/schema"
          :id (str "clojure-dap.schema/" (name definition-key))
          :$ref (str "classpath://clojure-dap/dap-json-schema.json#/definitions/" (name definition-key))}
         {:classpath-aware? true})]

    [:fn
     {:error/fn
      (fn [{:keys [_schema value]} _]
        (try
          (json-schema/validate prepared-schema value)
          (catch clojure.lang.ExceptionInfo e
            (let [cause (.getMessage e)
                  {:keys [errors]} (ex-data e)]
              (str cause " " (str/join ", " errors))))))}

     (fn [x]
       (try
         (json-schema/validate prepared-schema x)
         true
         (catch clojure.lang.ExceptionInfo _e
           false)))]))
(m/=> dap-json-schema->malli [:=> [:cat keyword?] vector?])

(define! ::message
  [:or
   (define! ::initialize-request (dap-json-schema->malli :InitializeRequest))
   (define! ::initialize-response (dap-json-schema->malli :InitializeResponse))
   (define! ::initialized-event (dap-json-schema->malli :InitializedEvent))

   (define! ::next-request (dap-json-schema->malli :NextRequest))
   (define! ::next-response (dap-json-schema->malli :NextResponse))])
