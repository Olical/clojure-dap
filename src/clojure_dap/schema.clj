(ns clojure-dap.schema
  (:require [malli.core :as m]
            [malli.util :as mu]))

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

(defn- upsert-explainer! [id]
  (assert (qualified-keyword? id) "Schema ID must be a qualified keyword")
  (assert (get @registry! id) (str "Unknown schema: " id))

  (if-let [explainer (get @explainers! id)]
    explainer
    (let [explainer (m/explainer (get @registry! id) {:registry @registry!})]
      (swap! explainers! id explainer)
      explainer)))

(defn explain
  "Validates the value against the schema referred to by the qualified keyword. Returns nil when everything is okay, returns a map explaining the issue when there is a problem."
  [id value]
  ((upsert-explainer! id) value))