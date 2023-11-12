(ns clojure-dap.debuggee.fake
  "A fake debuggee used for development and testing. All of it's methods have spy wrappers for testing."
  (:require [de.otto.nom.core :as nom]
            [malli.experimental :as mx]
            [spy.core :as spy]
            [clojure-dap.debuggee :as debuggee]))

(defn set-breakpoints [this _opts]
  (if (:fail? this)
    (nom/fail ::set-breakpoints-failure {:detail "Oh no!"})
    nil))

(defn evaluate [this _opts]
  (if (:fail? this)
    (nom/fail ::evaluate-failure {:detail "Oh no!"})
    nil))

(mx/defn create :- ::debuggee/debuggee
  ([] (create {}))
  ([{:keys [fail?]} :- [:map [:fail? {:optional true} :boolean]]]
   {:fail? fail?
    :set-breakpoints (spy/spy #'set-breakpoints)
    :evaluate (spy/spy #'evaluate)}))
