(ns clojure-dap.protocol
  "Tools to check, encode and decode DAP messages."
  (:require [clojure.string :as str]
            [malli.experimental :as mx]
            [de.otto.nom.core :as nom]
            [cognitect.anomalies :as anom]
            [jsonista.core :as json]
            [clojure-dap.schema :as schema]
            [camel-snake-kebab.core :as csk]
            [clojure-dap.util :as util]))

(def header-sep "\r\n")
(def double-header-sep (str header-sep header-sep))

(def supported-messages
  [::initialize-request
   ::initialize-response
   ::initialized-event

   ::attach-request
   ::attach-response

   ::disconnect-request
   ::disconnect-response

   ::next-request
   ::next-response

   ::set-breakpoints-request
   ::set-breakpoints-response

   ::evaluate-request
   ::evaluate-response

   ::configuration-done-request
   ::configuration-done-response

   ::output-event
   ::exited-event
   ::terminated-event])

(schema/define! ::message
  (into
   [:or]
   (map
    (fn [k]
      (schema/define! k
        (schema/dap-json-schema->malli (csk/->PascalCaseKeyword k)))))
   supported-messages))

(schema/define! ::next-seq-fn [:function [:=> [:cat] :int]])
(schema/define! ::message-ish
  [:map-of :keyword :any])

(mx/defn parse-header :- (schema/result [:map-of :keyword :any])
  "Given a header string of the format 'Content-Length: 119\\n\\n' it returns a map containing the key value pairs."
  [header :- :string]
  (try
    (into
     {}
     (map
      (fn [line]
        (let [[k v] (str/split line #": " 2)]
          [(keyword k) (json/read-value v)])))
     (str/split-lines (str/trim-newline header)))
    (catch Exception e
      (nom/fail
       ::anom/incorrect
       {::anom/message "Failed to parse DAP header"
        ::header header
        ::error (Throwable->map e)}))))

(mx/defn parse-message :- (schema/result ::message)
  "Parse a DAP message from a string, returning an anomaly or a valid message."
  [body :- :string]
  (try
    (let [parsed (json/read-value body json/keyword-keys-object-mapper)]
      (nom/with-nom [(schema/validate ::message parsed)]
        parsed))
    (catch Exception e
      (nom/fail
       ::anom/incorrect
       {::anom/message "Failed to parse DAP message JSON"
        ::body body
        ::error (Throwable->map e)}))))

(mx/defn render-header :- :string
  "Turns a map of k->v into a header string."
  [x :- [:maybe [:map-of :keyword :any]]]
  (str
   (str/join
    (map
     (fn [[k v]]
       (str (name k) ": " v header-sep))
     x))
   header-sep))

(mx/defn render-message :- (schema/result :string)
  "Takes a DAP message, validates it against the various possible schemas and then encodes it as a DAP JSON message with a header. This string can then be sent across the wire to the development tool."
  [message :- ::message-ish]
  (nom/with-nom [(schema/validate ::message message)]
    (let [encoded (json/write-value-as-string (util/walk-sorted-map message))]
      (str (render-header {:Content-Length (count (.getBytes encoded))}) encoded))))
