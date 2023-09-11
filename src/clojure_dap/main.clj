(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.pprint :as pp]
            [malli.core :as m]))

(defn run
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts]
  (pp/pprint opts)
  (println "Hello, World!"))
(m/=>
 run
 [:=>
  [:cat
   [:map]]
  nil?])
