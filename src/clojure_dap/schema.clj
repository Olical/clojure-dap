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
            [clojure-dap.util :as util]))

;; We use a global Malli registry, for better or for worse.
;; To me this is the good bits of spec with the great bits of Malli.
;; If this ever conflicts with something we can migrate away fairly easily.
(defonce registry! (atom (merge (m/default-schemas) (mu/schemas))))
(mr/set-default-registry!
 (mr/mutable-registry registry!))

;; A cache of explainer functions used by the validate function.
;; Should make repeated validate calls fairly efficient.
(defonce explainers! (atom {}))

(defn define!
  "Define a new schema, accepts a qualified keyword and a schema. Will be precompiled into a explainer. It may refer to other previously defined schemas by their qualified keyword."
  [id schema]
  (swap! registry! assoc id schema)

  ;; Reset the cache each time so we don't get into weird dev states.
  (reset! explainers! {})

  nil)
(m/=> define! [:=> [:cat :qualified-keyword some?] nil?])

;; Rewrite the cognitect.anomalies specs as Malli schemas.
(define! ::anom/anomaly
  [:map
   [::anom/category [:enum
                     ::anom/unavailable
                     ::anom/interrupted
                     ::anom/incorrect
                     ::anom/forbidden
                     ::anom/unsupported
                     ::anom/not-found
                     ::anom/conflict
                     ::anom/fault
                     ::anom/busy]]
   [::anom/message {:optional true} string?]])

(defn- upsert-explainer!
  "Either return the explainer if compiled already or compile the explainer and cache it. Can throw malli errors if the schema is bad."
  [id]
  (let [schema (get @registry! id)]
    (if schema
      (if-let [explainer (get @explainers! id)]
        explainer
        (let [explainer (m/explainer (get @registry! id))]
          (swap! explainers! id explainer)
          explainer))
      {::anom/category ::anom/not-found
       ::anom/message (str "Unknown schema: " id)})))
(m/=> upsert-explainer! [:=> [:cat :qualified-keyword] [:or fn? ::anom/anomaly]])

(defn validate
  "Validates the value against the schema referred to by the qualified keyword. Returns nil when everything is okay, returns an anomaly map explaining the issue when there is a problem."
  [id value]
  (let [explainer (upsert-explainer! id)]
    (if (util/anomaly? explainer)
      explainer
      (when-let [explanation (explainer value)]
        (merge
         {::anom/category ::anom/incorrect
          ::anom/message (str "Failed to validate against schema " id)
          ::explanation explanation})))))

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
