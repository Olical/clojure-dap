(ns clojure-dap.stream
  (:require [manifold.stream :as s]))

(defn io
  "Create an input/output stream pair. Input is coming towards your code, output is heading out from your code."
  []
  {:input (s/stream)
   :output (s/stream)})
