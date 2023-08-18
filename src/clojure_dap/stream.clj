(ns clojure-dap.stream
  "Tools to work with DAP streams and streams in general."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [de.otto.nom.core :as nom]
            [jsonista.core :as json]
            [cognitect.anomalies :as anom]
            [manifold.stream :as s]
            [clojure-dap.schema :as schema]))

(def header-sep "\r\n")
(def double-header-sep (str header-sep header-sep))

(defn io
  "Create an input/output stream pair. Input is coming towards your code, output is heading out from your code."
  []
  {:input (s/stream)
   :output (s/stream)})
(m/=>
 io
 [:=>
  [:cat]
  [:map
   [:input [:fn s/stream?]]
   [:output [:fn s/stream?]]]])

(defn parse-header
  "Given a header string of the format 'Content-Length: 119\\r\\n\\r\\n' it returns a map containing the key value pairs."
  [header]
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
(m/=> parse-header [:=> [:cat string?] (schema/result [:map-of keyword? any?])])

(defn render-header
  "Turns a map of k->v into a header string."
  [x]
  (str
   (str/join
    (map
     (fn [[k v]]
       (str (name k) ": " v header-sep))
     x))
   header-sep))
(m/=> render-header [:=> [:cat [:or nil? [:map-of keyword? any?]]] string?])

(defn read-message
  "Reads a DAP message from the input stream. Assumes a few things: The first character we're going to read will be the beginning of a new messages header AND the stream will consist of single characters.

  It works by reading the header (Content-Length: 119\\r\\n\\r\\n) until a double \\r\\n\\r\\n at which point it knows the Content-Length and can read the rest of the message.

  Once read, we decode the JSON body and validate it against the JSON schemas before returning the valid message or an anomaly.

  Will block until a message is read!"
  [input-stream]
  (loop [header-buffer ""]
    (let [next-char @(s/take! input-stream)]
      (if (char? next-char)
        (let [header-buffer (str header-buffer next-char)]
          (if (or (= header-buffer header-sep)
                  (str/ends-with? header-buffer double-header-sep))
            (nom/let-nom> [{:keys [Content-Length] :as headers}
                           (parse-header header-buffer)
                           body (str/join @(s/take! (s/batch Content-Length input-stream)))]
              (try
                (let [parsed (json/read-value body json/keyword-keys-object-mapper)]
                  (nom/with-nom [(schema/validate ::schema/message parsed)]
                    parsed))
                (catch Exception e
                  (nom/fail
                   ::anom/incorrect
                   {::anom/message "Failed to parse DAP message JSON"
                    ::headers headers
                    ::body body
                    ::error (Throwable->map e)}))))
            (recur header-buffer)))
        (nom/fail
         ::anom/incorrect
         {::anom/message "Received a non-character while reading the next DAP message. A nil probably means the stream closed."
          ::value next-char})))))
(m/=>
 read-message
 [:=>
  [:cat [:fn s/stream?]]
  (schema/result [:map-of keyword? any?])])

(defn render-message
  "Takes a DAP message, validates it against the various possible schemas and then encodes it as a DAP JSON message with a header. This string can then be sent across the wire to the development tool."
  [message]
  (nom/with-nom [(schema/validate ::schema/message message)]
    (let [encoded (json/write-value-as-string message)]
      (str (render-header {:Content-Length (count encoded)}) encoded))))
(m/=>
 render-message
 [:=>
  [:cat [:map-of keyword? any?]]
  (schema/result string?)])
