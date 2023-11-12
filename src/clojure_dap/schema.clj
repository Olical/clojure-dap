(ns clojure-dap.schema
  "Schema registration and validation."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.experimental :as mx]
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

(mx/defn define! :- :qualified-keyword
  "Define a new schema, accepts a qualified keyword and a schema. Will be precompiled into a explainer. It may refer to other previously defined schemas by their qualified keyword. Returns the keyword for the schema you're defining so you can embed it in other schemas."
  [id :- :qualified-keyword
   schema :- :some]
  (swap! schemas! assoc id schema)

  ;; Reset the cache each time so we don't get into weird dev states.
  (reset! explainers! {})

  id)

(define! ::id :qualified-keyword)
(define! ::anomaly [:fn nom/abominable?])
(define! ::atom [:fn #(instance? clojure.lang.Atom %)])

(mx/defn result :- vector?
  "Wraps the given schema in [:or ... :clojure-dap.schema/anomaly], prompting callers to handle your potential failure cases. Sort of modeled on Rust's Result<T, E> type which can return Ok(T) or Err(E)."
  [schema :- :some]
  [:or schema ::anomaly])

(mx/defn ^:private upsert-explainer! :- (result fn?)
  "Either return the explainer if compiled already or compile the explainer and cache it. Can throw malli errors if the schema is bad."
  [id :- ::id]
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

(mx/defn validate :- (result :nil)
  "Validates the value against the schema referred to by the qualified keyword. Returns nil when everything is okay, returns an anomaly map explaining the issue when there is a problem."
  [id :- ::id
   value :- :any]
  (nom/let-nom> [explainer (upsert-explainer! id)]
    (when-let [explanation (explainer value)]
      (nom/fail
       ::anom/incorrect
       {::anom/message (str "Failed to validate against schema " id)
        ::explanation explanation
        ::humanized (me/humanize explanation)}))))

(mx/defn dap-json-schema->malli :- vector?
  [definition-key :- :keyword]
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
