(ns clojure-dap.debuggee
  "Interface for talking to a debuggee through some debugger tooling. Geared towards nREPL / CIDER's debug suite for now but should be able to support Flow-storm at some point."
  (:require [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]))

(schema/define! ::debuggee
  [:map
   [:init [:fn fn?]]
   [:set-breakpoints [:fn fn?]]
   [:evaluate [:fn fn?]]])

(mx/defn init :- (schema/result [:vector ::protocol/message])
  [this :- ::debuggee]
  ((:init this) this))

(mx/defn set-breakpoints :- (schema/result [:vector ::protocol/message])
  [this :- ::debuggee
   arguments :- ::protocol/set-breakpoints-arguments]
  ((:set-breakpoints this) this arguments))

(mx/defn evaluate :- (schema/result [:vector ::protocol/message])
  [this :- ::debuggee
   arguments :- ::protocol/evaluate-arguments]
  ((:evaluate this) this arguments))
