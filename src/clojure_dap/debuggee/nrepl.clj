(ns clojure-dap.debuggee.nrepl
  "Connects to an nREPL server and uses the CIDER debugger middleware as an implementation."
  (:require [de.otto.nom.core :as nom]
            [malli.experimental :as mx]
            [spy.core :as spy]
            [clojure-dap.schema :as schema]
            [clojure-dap.debuggee :as debuggee]))

(defn set-breakpoints [this _opts]
  (if (:fail? this)
    (nom/fail ::set-breakpoints-failure {:detail "Oh no!"})
    nil))

(defn evaluate [this _opts]
  (if (:fail? this)
    (nom/fail ::evaluate-failure {:detail "Oh no!"})
    nil))

(mx/defn create :- (schema/result ::debuggee/debuggee)
  ([] (create {}))
  ([{:keys []} :- [:map]]
   {:set-breakpoints #'set-breakpoints
    :evaluate #'evaluate}))
