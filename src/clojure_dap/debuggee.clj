(ns clojure-dap.debuggee
  "Interface for talking to a debuggee through some debugger tooling. Geared towards nREPL / CIDER's debug suite for now but should be able to support Flow-storm at some point."
  (:require [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

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
   opts :- [:map]]
  ((:evaluate this) this opts))

(schema/define! ::create-opts
  [:map [:type [:enum "fake"]]])

(mx/defn create :- ::debuggee
  [opts :- ::create-opts]
  (case (:type opts)
    "fake" (fake-debuggee/create)))
