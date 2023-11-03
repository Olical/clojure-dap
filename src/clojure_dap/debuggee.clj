(ns clojure-dap.debuggee
  "Interface for talking to a debuggee through some debugger tooling. Geared towards nREPL / CIDER's debug suite for now but should be able to support Flow-storm at some point."
  (:require [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]))

(schema/define! ::this
  [:map
   [:init [:fn fn?]]
   [:set-breakpoints [:fn fn?]]
   [:evaluate [:fn fn?]]])

(schema/define! ::messages
  (schema/result [:vector ::protocol/message]))

(mx/defn init :- ::messages
  [this :- ::this]
  ((:init this) this))

(mx/defn set-breakpoints :- ::messages
  [this :- ::this
   arguments :- ::protocol/set-breakpoints-arguments]
  ((:set-breakpoints this) this arguments))

(mx/defn evaluate :- ::messages
  [this :- ::this
   arguments :- ::protocol/evaluate-arguments]
  ((:evaluate this) this arguments))
