(ns clojure-dap.stream
  "Tools to work with DAP streams and streams in general."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [de.otto.nom.core :as nom]
            [jsonista.core :as json]
            [cognitect.anomalies :as anom]
            [manifold.stream :as s]
            [clojure-dap.schema :as schema]))

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
          (if (str/ends-with? header-buffer "\r\n\r\n")
            (nom/let-nom> [{:keys [Content-Length] :as headers}
                           (parse-header header-buffer)
                           body (str/join @(s/take! (s/batch Content-Length input-stream)))]
              (try
                (let [parsed (json/read-value body json/keyword-keys-object-mapper)]
                  (nom/with-nom [(schema/validate ::schema/request parsed)]
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
