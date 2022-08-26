(ns clojure-dap.main
  (:require [clojure.pprint :as pp]))

(defn run [opts]
  (pp/pprint opts)
  (println "Hello, Twitch and YouTube!"))
