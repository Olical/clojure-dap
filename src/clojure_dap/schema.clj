(ns clojure-dap.schema
  "Schema registration and validation."
  (:require [clojure.java.io :as io]
            [malli.core :as m]
            [malli.util :as mu]
            [jsonista.core :as json]
            [clojure.set :as set]
            [json-schema.core :as json-schema]
            [cognitect.anomalies :as anom]))

(defonce registry! (atom (merge (m/default-schemas) (mu/schemas))))
(defonce explainers! (atom {}))

(defn define!
  "Define a new schema, accepts a qualified keyword and a schema. Will be precompiled into a explainer. It may refer to other previously defined schemas by their qualified keyword."
  [id schema]
  (assert (qualified-keyword? id) "Schema ID must be a qualified keyword")
  (swap! registry! assoc id schema)

  ;; Reset the cache each time so we don't get into weird dev states.
  (reset! explainers! {})

  nil)

(defn- upsert-explainer!
  "Either return the explainer if compiled already or compile the explainer and cache it. Can throw malli errors if the schema is bad."
  [id]
  (assert (qualified-keyword? id) "Schema ID must be a qualified keyword")
  (assert (get @registry! id) (str "Unknown schema: " id))

  (if-let [explainer (get @explainers! id)]
    explainer
    (let [explainer (m/explainer (get @registry! id) {:registry @registry!})]
      (swap! explainers! id explainer)
      explainer)))

(defn validate
  "Validates the value against the schema referred to by the qualified keyword. Returns nil when everything is okay, returns an anomaly map explaining the issue when there is a problem."
  [id value]
  (when-let [explanation ((upsert-explainer! id) value)]
    (merge
     {::anom/category ::anom/incorrect
      ::anom/message (str "Failed to validate against schema " id)
      ::explanation explanation})))

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
