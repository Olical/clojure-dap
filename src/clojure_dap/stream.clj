(ns clojure-dap.stream
  "Tools to work with streams. Ideally streams of DAP messages."
  (:require [clojure.string :as str]
            [malli.experimental :as mx]
            [de.otto.nom.core :as nom]
            [cognitect.anomalies :as anom]
            [taoensso.timbre :as log]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]))

(schema/define! ::stream [:fn s/stream?])
(schema/define! ::reader [:fn #(instance? java.io.Reader %)])
(schema/define! ::writer [:fn #(instance? java.io.Writer %)])

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
          (if (or (= header-buffer protocol/header-sep)
                  (str/ends-with? header-buffer protocol/double-header-sep))
            (nom/let-nom> [{:keys [Content-Length]} (protocol/parse-header header-buffer)]
              (protocol/parse-message (str/join @(s/take! (s/batch Content-Length input-stream)))))
            (recur header-buffer)))
        (nom/fail
         ::anom/incorrect
         {::anom/message "Received a non-character while reading the next DAP message. A nil probably means the stream closed."
          ::value next-char})))))

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
          @(s/put! stream value)
          (recur opts))))))

(mx/defn stream-into-writer! :- [:fn d/deferred?]
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
   stream))
