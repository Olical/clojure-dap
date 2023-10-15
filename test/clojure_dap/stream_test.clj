(ns clojure-dap.stream-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [malli.core :as m]
            [matcher-combinators.test]
            [manifold.stream :as s]
            [clojure-dap.stream :as stream]))

(def example-message
  (str "Content-Length: 112" stream/double-header-sep "{
    \"seq\": 153,
    \"type\": \"request\",
    \"command\": \"next\",
    \"arguments\": {
        \"threadId\": 3
    }
}"))

(def invalid-message
  (str "Content-Length: 111" stream/double-header-sep "{
    \"seq\": 153,
    \"type\": \"reqest\",
    \"command\": \"next\",
    \"arguments\": {
        \"threadId\": 3
    }
}"))

(t/deftest parse-header
  (t/testing "simple header"
    (t/is (= {:Content-Length 119}
             (stream/parse-header
              (str "Content-Length: 119" stream/double-header-sep)))))

  (t/testing "a bad header returns an anomaly"
    (t/is (match?
           [:de.otto.nom.core/anomaly
            :cognitect.anomalies/incorrect
            {:cognitect.anomalies/message "Failed to parse DAP header"
             ::stream/header (str "Content-Length: ohno" stream/double-header-sep)}]
           (stream/parse-header (str "Content-Length: ohno" stream/double-header-sep))))))

(t/deftest read-message
  (t/testing "reads a DAP message from a input-stream"
    (with-open [stream (s/stream)]
      (s/put-all! stream (char-array example-message))

      (t/is (= {:seq 153
                :type "request"
                :command "next"
                :arguments {:threadId 3}}
               (stream/read-message stream)))))

  (t/testing "returns an anomaly if we get some bad input"
    (with-open [stream (s/stream)]
      (s/put-all! stream (char-array (str "Content-Length: ohno" stream/double-header-sep)))

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Failed to parse DAP header"}]
             (stream/read-message stream))))

    (with-open [stream (s/stream)]
      (s/put-all!
       stream
       (char-array
        (str "Content-Length: 3"
             stream/double-header-sep
             "{\"thisisbad\": true}")))

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Failed to parse DAP message JSON"
               ::stream/headers {:Content-Length 3}
               ::stream/body "{\"t"}]
             (stream/read-message stream)))))

  (t/testing "returns an anomaly if we can read a message but it's malformed"
    (with-open [stream (s/stream)]
      (s/put-all! stream (char-array invalid-message))

      (t/is
       (match?
        [:de.otto.nom.core/anomaly
         :cognitect.anomalies/incorrect
         {:clojure-dap.schema/explanation
          {:errors some?
           :schema m/schema?
           :value {:arguments {:threadId 3}
                   :command "next"
                   :seq 153
                   :type "reqest"}}

          :cognitect.anomalies/message
          "Failed to validate against schema :clojure-dap.schema/message"}]
        (stream/read-message stream)))))

  (t/testing "returns an anomaly if the stream closes"
    (with-open [stream (s/stream)]
      (s/close! stream)

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Received a non-character while reading the next DAP message. A nil probably means the stream closed."
               ::stream/value nil}]
             (stream/read-message stream))))))

(t/deftest render-header
  (t/testing "nil / empty map produces an empty header"
    (t/is (= stream/header-sep (stream/render-header nil)))
    (t/is (= stream/header-sep (stream/render-header {}))))

  (t/testing "we can render content length headers"
    (t/is (= (str "Content-Length: 123" stream/double-header-sep) (stream/render-header {:Content-Length 123})))))

(t/deftest render-message
  (t/testing "a simple valid message"
    (t/is (= (str
              "Content-Length: 72"
              stream/double-header-sep
              "{\"seq\":153,\"type\":\"request\",\"command\":\"next\",\"arguments\":{\"threadId\":3}}")
             (stream/render-message
              {:seq 153
               :type "request"
               :command "next"
               :arguments {:threadId 3}}))))

  (t/testing "we can round trip through the render and read functions"
    (with-open [stream (s/stream)]
      (let [message {:seq 153
                     :type "request"
                     :command "next"
                     :arguments {:threadId 3}}]
        (s/put-all! stream (char-array (stream/render-message message)))
        (t/is (match? message (stream/read-message stream))))))

  (t/testing "a bad message returns an anomaly"
    (t/is
     (match?
      [:de.otto.nom.core/anomaly
       :cognitect.anomalies/incorrect
       {:clojure-dap.schema/explanation
        {:errors some?
         :schema m/schema?
         :value {:arguments {:threadId 3}
                 :command "next"
                 :seq 153
                 :type "reqest"}}

        :cognitect.anomalies/message
        "Failed to validate against schema :clojure-dap.schema/message"}]
      (stream/render-message
       {:arguments {:threadId 3}
        :command "next"
        :seq 153
        :type "reqest"})))))

(t/deftest reader-into-stream!
  (t/testing "it reads a reader into"
    (with-open [stream (s/stream)
                reader (io/reader (.getBytes "abc"))]
      (stream/reader-into-stream!
       {:reader reader
        :stream stream})
      (t/is (match? [\a \b \c] (s/stream->seq (s/map char stream))))

      (t/testing "and closes the stream at the end"
        (t/is (s/closed? stream)))))

  (t/testing "if the stream is already closed it stops instantly"
    (with-open [stream (s/stream)
                reader (io/reader (.getBytes "abc"))]
      (s/close! stream)
      (stream/reader-into-stream!
       {:reader reader
        :stream stream})
      (t/is (empty? (s/stream->seq (s/map char stream))))

      (t/testing "but the reader is still open and can be read"
        (t/is (= \a (char (.read reader)))))))

  (t/testing "if the reader is closed the stream closes too"
    (with-open [stream (s/stream)
                reader (io/reader (.getBytes "abc"))]
      (.close reader)
      (stream/reader-into-stream!
       {:reader reader
        :stream stream})
      (t/is (s/closed? stream))
      (t/is (thrown? java.io.IOException (.read reader)))
      (t/is (empty? (s/stream->seq (s/map char stream)))))))

(t/deftest stream-into-writer!
  (t/testing "writes everything into the writer"
    (with-open [stream (s/stream)
                writer (java.io.StringWriter.)]
      (s/put-all! stream ["foo" "bar" "baz"])
      (stream/stream-into-writer!
       {:writer writer
        :stream stream})
      (t/is (= "foobarbaz" (str writer)))))

  (t/testing "writing to a closed stream does nothing"
    (with-open [stream (s/stream)
                writer (java.io.StringWriter.)]
      (.close writer)
      (stream/stream-into-writer!
       {:writer writer
        :stream stream})
      (t/is (= "" (str writer)))

      (t/testing "but the stream is drained"
        (s/drained? stream)))))
