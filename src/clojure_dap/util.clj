(ns clojure-dap.util
  "Utility functions! Everyone's favourite namespace."
  (:require [cognitect.anomalies :as anom]))

(defn anomaly?
  "Is the given value x an anomaly? If it is a map and contains the :cognitect.anomalies/category key, we consider it so."
  [x]
  (boolean (and (map? x) (qualified-keyword? (::anom/category x)))))
