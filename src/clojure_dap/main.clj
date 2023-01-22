(ns clojure-dap.main
  (:require [clojure.pprint :as pp]))

;; TODO core.async or manifold + claypoole
;; TODO DAP IO layer (can read from stdio or a channel)
;; TODO nREPL IO layer (can write to an nREPL or channel)
;; TODO System in the middle that can talk to both side's channels

(defn run [opts]
  (pp/pprint opts)
  (println "Hello, World!"))
