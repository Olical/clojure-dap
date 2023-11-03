(ns clojure-dap.debuggee.fake
  "A fake debuggee used for development and testing. All of it's methods have spy wrappers for testing."
  (:require [spy.core :as spy]))

(defn initialize [this opts]
  nil)

(defn set-breakpoints [this opts]
  nil)

(defn evaluate [this opts]
  nil)

(defn create []
  {:initialize (spy/spy #'initialize)
   :set-breakpoints (spy/spy #'set-breakpoints)
   :evaluate (spy/spy #'evaluate)})
