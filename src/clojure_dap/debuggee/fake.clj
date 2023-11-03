(ns clojure-dap.debuggee.fake
  "A fake debuggee used for development and testing. All of it's methods have spy wrappers for testing."
  (:require [spy.core :as spy]
            [malli.experimental :as mx]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee :as debuggee]))

(mx/defn initialize :- ::debuggee/messages-result
  [this :- ::debuggee/this
   arguments :- ::protocol/initialize-request-arguments]
  [])

(mx/defn set-breakpoints :- ::debuggee/messages-result
  [this :- ::debuggee/this
   arguments :- ::protocol/set-breakpoints-arguments]
  [])

(mx/defn evaluate  :- ::debuggee/messages-result
  [this :- ::debuggee/this
   arguments :- ::protocol/evaluate-arguments]
  [])

(defn create []
  {:initialize (spy/spy #'initialize)
   :set-breakpoints (spy/spy #'set-breakpoints)
   :evaluate (spy/spy #'evaluate)})
