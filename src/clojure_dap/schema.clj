(ns clojure-dap.schema
  "Schema registration and validation."
  (:require [clojure.java.io :as io]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.registry :as mr]
            [jsonista.core :as json]
            [clojure.set :as set]
            [json-schema.core :as json-schema]
            [cognitect.anomalies :as anom]
            [de.otto.nom.core :as nom]))

(defonce schemas! (atom (merge (m/default-schemas) (mu/schemas))))

-;; We use a global Malli registry, for better or for worse.
-;; To me this is the good bits of spec with the great bits of Malli.
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

(defn maybe
  "Wraps the given schema in an or, so you may get the schema you specify or you'll get an anomaly."
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
(m/=> upsert-explainer! [:=> [:cat ::id] (maybe fn?)])

(defn validate
  "Validates the value against the schema referred to by the qualified keyword. Returns nil when everything is okay, returns an anomaly map explaining the issue when there is a problem."
  [id value]
  (nom/let-nom> [explainer (upsert-explainer! id)]
    (when-let [explanation (explainer value)]
      (nom/fail
       ::anom/incorrect
       {::anom/message (str "Failed to validate against schema " id)
        ::explanation explanation}))))
(m/=> validate [:=> [:cat ::id any?] [:or nil? ::anomaly]])

(comment
  (let [dap-json-schema (json/read-value (io/resource "clojure-dap/dap-json-schema.json"))]
    (json-schema/validate
     (json-schema/prepare-schema
      (merge
       dap-json-schema
       {"oneOf" (mapv
                 (fn [definition-name]
                   {"$ref" (str "#/definitions/" definition-name)})
                 (set/difference
                  (set (keys (get dap-json-schema "definitions")))
                  #{"ProtocolMessage"
                    "Request"
                    "Response"
                    "Event"
                    "Breakpoint"
                    "BreakpointLocation"
                    "Capabilities"
                    "Checksum"
                    "ChecksumAlgorithm"
                    "ColumnDescriptor"
                    "CompletionItem"
                    "CompletionItemType"
                    "DataBreakpoint"
                    "DataBreakpointAccessType"
                    "DisassembledInstruction"
                    "ExceptionBreakMode"
                    "ExceptionBreakpointsFilter"
                    "ExceptionDetails"
                    "ExceptionFilterOptions"
                    "ExceptionOptions"
                    "ExceptionPathSegment"
                    "FunctionBreakpoint"
                    "GotoTarget"
                    "InstructionBreakpoint"
                    "InvalidatedAreas"
                    "Message"
                    "Module"
                    "ModulesViewDescriptor"
                    "Scope"
                    "Source"
                    "SourceBreakpoint"
                    "StackFrame"
                    "StackFrameFormat"
                    "StepInTarget"
                    "SteppingGranularity"
                    "Thread"
                    "ValueFormat"
                    "Variable"
                    "VariablePresentationHint"}))}))

     {"seq" 153
      "type" "request"
      "command" "next"
      "arguments" {"threadId" 3}}))

  (ex-data *e))
