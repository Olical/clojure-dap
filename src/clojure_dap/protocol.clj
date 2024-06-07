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

(def seq-placeholder
  "This is set automatically when rendering messages. We use a placeholder to pass schema validation."
  -1)

(def supported-messages
  [::initialize-request
   ::initialize-response
   ::initialized-event

   ::attach-request
   ::attach-response

   ::configuration-done-request
   ::configuration-done-response

   ::disconnect-request
   ::disconnect-response

   ::next-request
   ::next-response

   ::set-breakpoints-request
   ::set-breakpoints-response

   ::evaluate-request
   ::evaluate-response

   ::threads-request
   ::threads-response

   ::stack-trace-request
   ::stack-trace-response

   ::scopes-request
   ::scopes-response

   ::variables-request
   ::variables-response

   ::continue-request
   ::continue-response

   ::output-event
   ::exited-event
   ::stopped-event
   ::terminated-event])

(schema/define! ::message
  (into
   [:or]
   (map
    (fn [k]
      (schema/define! k
        (schema/dap-json-schema->malli (csk/->PascalCaseKeyword k)))))
   supported-messages))

(schema/define! ::format
  [:map
   [:hex {:optional true} :boolean]
   [:parameters {:optional true} :boolean]
   [:parameter-types {:optional true} :boolean]
   [:parameter-names {:optional true} :boolean]
   [:parameter-values {:optional true} :boolean]
   [:line {:optional true} :boolean]
   [:module {:optional true} :boolean]
   [:include-all {:optional true} :boolean]])

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

(def keyword-keys-object-mapper-with-source
  (doto json/keyword-keys-object-mapper
    (.configure com.fasterxml.jackson.core.JsonParser$Feature/INCLUDE_SOURCE_IN_LOCATION true)))

(mx/defn parse-message :- (schema/result ::message)
  "Parse a DAP message from a string, returning an anomaly or a valid message."
  [body :- :string]
  (try
    (let [parsed (json/read-value body keyword-keys-object-mapper-with-source)]
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
