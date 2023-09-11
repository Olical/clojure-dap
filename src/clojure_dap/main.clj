(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.pprint :as pp]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [malli.core :as m]))

(defn run
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts]
  ;; Always log to stderr through timbre.
  ;; This is because the DAP server may be communicating with a client over stdout.
  ;; We also install extra deps that hook up essentially every other Java
  ;; logging system to timbre, so it all goes out under stderr with the same formatting.
  (log/merge-config!
   {:appenders
    {:println (appenders/println-appender {:stream :*err*})}})

  (pp/pprint opts)
  (log/info "Hello, World!"))
(m/=>
 run
 [:=>
  [:cat
   [:map]]
  nil?])
