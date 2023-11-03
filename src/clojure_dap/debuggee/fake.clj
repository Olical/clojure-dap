(ns clojure-dap.debuggee.fake
  "A fake debuggee used for development and testing. All of it's methods have spy wrappers for testing."
  (:require [spy.core :as spy]
            [malli.experimental :as mx]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.schema :as schema]))

(mx/defn init :- ::debuggee/messages
  [this :- ::debuggee/this]
  [])

(mx/defn set-breakpoints :- ::debuggee/messages
  [this :- ::debuggee/this
   arguments :- ::protocol/set-breakpoints-arguments]
  [])

(mx/defn evaluate  :- ::debuggee/messages
  [this :- ::debuggee/this
   arguments :- ::protocol/evaluate-arguments]
  [])

(defn create []
  {:init (spy/spy init)
   :set-breakpoints (spy/spy set-breakpoints)
   :evaluate (spy/spy evaluate)})
