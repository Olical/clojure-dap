(ns clojure-dap.util
  (:require [cognitect.anomalies :as anom]))

(defn anomaly?
  "Is the given value x an anomaly? If it is a map and contains the :cognitect.anomalies/category key, we consider it so."
  [x]
  (boolean (and (map? x) (qualified-keyword? (::anom/category x)))))
