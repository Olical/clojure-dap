(ns clojure-dap.debuggee
  "Interface for talking to a debuggee through some debugger tooling. Geared towards nREPL / CIDER's debug suite for now but should be able to support Flow-storm at some point."
  (:require [malli.experimental :as mx]
            [clojure-dap.schema :as schema]))

(schema/define! ::debuggee
  [:map
   [:set-breakpoints [:fn fn?]]
   [:evaluate [:fn fn?]]])

(mx/defn set-breakpoints :- (schema/result :nil)
  [this :- ::debuggee
   opts :- [:map]]
  ((:set-breakpoints this) this opts))

(mx/defn evaluate :- (schema/result :nil)
  [this :- ::debuggee
   opts :- [:map
            [:expression :string]]]
  ((:evaluate this) this opts))
