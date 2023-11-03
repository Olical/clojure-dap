(ns clojure-dap.debuggee
  "Interface for talking to a debuggee through some debugger tooling. Geared towards nREPL / CIDER's debug suite for now but should be able to support Flow-storm at some point."
  (:require [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]))

(schema/define! ::this
  [:map
   [:initialize [:fn fn?]]
   [:set-breakpoints [:fn fn?]]
   [:evaluate [:fn fn?]]])

(schema/define! ::messages-result
  (schema/result [:vector ::protocol/message]))

(mx/defn initialize :- ::messages-result
  [this :- ::this
   arguments :- ::protocol/initialize-request-arguments]
  ((:initialize this) this arguments))

(mx/defn set-breakpoints :- ::messages-result
  [this :- ::this
   arguments :- ::protocol/set-breakpoints-arguments]
  ((:set-breakpoints this) this arguments))

(mx/defn evaluate :- ::messages-result
  [this :- ::this
   arguments :- ::protocol/evaluate-arguments]
  ((:evaluate this) this arguments))
