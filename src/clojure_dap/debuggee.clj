(ns clojure-dap.debuggee
  "Interface for talking to a debuggee through some debugger tooling. Geared towards nREPL / CIDER's debug suite for now but should be able to support Flow-storm at some point."
  (:require [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]))

(schema/define! ::debuggee
  [:map
   [:set-breakpoints [:fn fn?]]
   [:evaluate [:fn fn?]]
   [:threads [:fn fn?]]])

(mx/defn set-breakpoints :- (schema/result ::protocol/message-ish)
  [this :- ::debuggee
   opts :- [:map
            [:breakpoints [:vector [:map [:line pos-int?]]]]
            [:source [:map [:path :string]]]]]
  ((:set-breakpoints this) this opts))

(mx/defn evaluate :- (schema/result ::protocol/message-ish)
  [this :- ::debuggee
   opts :- [:map
            [:expression :string]]]
  ((:evaluate this) this opts))

(mx/defn threads :- (schema/result ::protocol/message-ish)
  [this :- ::debuggee]
  ((:threads this) this))

(mx/defn stack-trace :- (schema/result ::protocol/message-ish)
  [this :- ::debuggee
   opts :- [:map
            [:thread-id :int]
            [:start-frame {:optional true} :int]
            [:levels {:optional true} :int]
            [:format {:optional true} ::protocol/format]]]
  ((:stack-trace this) this opts))

(mx/defn scopes :- (schema/result ::protocol/message-ish)
  [this :- ::debuggee
   opts :- [:map
            [:frame-id :int]]]
  ((:scopes this) this opts))

(mx/defn variables :- (schema/result ::protocol/message-ish)
  [this :- ::debuggee
   opts :- [:map
            [:variables-reference :int]
            [:filter {:optional true} [:enum "indexed" "named"]]
            [:start {:optional true} :int]
            [:count {:optional true} :int]
            [:format {:optional true} ::protocol/format]]]
  ((:variables this) this opts))
