(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.pprint :as pp]))

;; TODO DAP IO layer (can read from stdio or a channel)
;; TODO nREPL IO layer (can write to an nREPL or channel)
;; TODO System in the middle that can talk to both side's channels

(defn run
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts]
  (pp/pprint opts)
  (println "Hello, World!"))
