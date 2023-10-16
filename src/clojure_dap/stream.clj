(ns clojure-dap.stream
  "Tools to work with DAP streams and streams in general."
  (:require [clojure.string :as str]
            [malli.experimental :as mx]
            [de.otto.nom.core :as nom]
            [cognitect.anomalies :as anom]
            [jsonista.core :as json]
            [taoensso.timbre :as log]
            [manifold.stream :as s]
            [clojure-dap.schema :as schema]))

(def header-sep "\r\n")
(def double-header-sep (str header-sep header-sep))

(schema/define! ::stream [:fn s/stream?])
(schema/define! ::reader [:fn #(instance? java.io.Reader %)])
(schema/define! ::writer [:fn #(instance? java.io.Writer %)])

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

(mx/defn read-message :- (schema/result [:map-of :keyword :any])
  "Reads a DAP message from the input stream. Assumes a few things: The first character we're going to read will be the beginning of a new messages header AND the stream will consist of single characters.

  It works by reading the header (Content-Length: 119\\n\\n) until a double \\n\\n at which point it knows the Content-Length and can read the rest of the message.

  Once read, we decode the JSON body and validate it against the JSON schemas before returning the valid message or an anomaly.

  Will block until a message is read!"
  [input-stream :- ::stream]
  (loop [header-buffer ""]
    (let [next-char @(s/take! input-stream)]
      (if (char? next-char)
        (let [header-buffer (str header-buffer next-char)]
          (if (or (= header-buffer header-sep)
                  (str/ends-with? header-buffer double-header-sep))
            (nom/let-nom> [{:keys [Content-Length] :as headers} (parse-header header-buffer)
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

(mx/defn render-message :- (schema/result :string)
  "Takes a DAP message, validates it against the various possible schemas and then encodes it as a DAP JSON message with a header. This string can then be sent across the wire to the development tool."
  [message :- [:maybe [:map-of :keyword :any]]]
  (nom/with-nom [(schema/validate ::schema/message message)]
    (let [encoded (json/write-value-as-string message)]
      (str (render-header {:Content-Length (count (.getBytes encoded))}) encoded))))

(mx/defn reader-into-stream! :- :nil
  "Pour a reader into a stream. Once we get a -1 or an error on .read we close both ends."
  [{:keys [reader stream] :as opts}
   :- [:map
       [:reader ::reader]
       [:stream ::stream]]]
  (when-not (s/closed? stream)
    (let [value (try
                  (.read reader)
                  (catch java.io.IOException e
                    (log/error e "Exception while reading into stream")
                    -1))]
      (if (= -1 value)
        (s/close! stream)
        (do
          (s/put! stream value)
          (recur opts))))))

(mx/defn stream-into-writer! :- :nil
  "Pour a stream into a writer. If the stream closes then we close the writer too."
  [{:keys [stream writer]}
   :- [:map
       [:writer ::writer]
       [:stream ::stream]]]
  (s/consume
   (fn [value]
     (try
       (.write writer value)
       (catch java.io.IOException e
         (log/error e "Exception while writing into writer"))))
   stream)
  nil)
