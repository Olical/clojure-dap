(ns clojure-dap.stream-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [malli.core :as m]
            [matcher-combinators.test]
            [de.otto.nom.core :as nom]
            [manifold.stream :as s]
            [clojure-dap.stream :as stream]
            [clojure-dap.protocol :as protocol]))

(def example-message
  (str "Content-Length: 112" protocol/double-header-sep "{
    \"seq\": 153,
    \"type\": \"request\",
    \"command\": \"next\",
    \"arguments\": {
        \"threadId\": 3
    }
}"))

(def invalid-message
  (str "Content-Length: 111" protocol/double-header-sep "{
    \"seq\": 153,
    \"type\": \"reqest\",
    \"command\": \"next\",
    \"arguments\": {
        \"threadId\": 3
    }
}"))

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
      (s/put-all! stream (char-array (str "Content-Length: ohno" protocol/double-header-sep)))

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
             protocol/double-header-sep
             "{\"thisisbad\": true}")))

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Failed to parse DAP message JSON"
               ::protocol/body "{\"t"}]
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
          "Failed to validate against schema :clojure-dap.protocol/message"}]
        (stream/read-message stream)))))

  (t/testing "returns an anomaly if the stream closes"
    (with-open [stream (s/stream)]
      (s/close! stream)

      (t/is (match?
             [:de.otto.nom.core/anomaly
              ::stream/closed
              {:cognitect.anomalies/message "Received a nil reading the next DAP message. This means the stream has closed."}]
             (stream/read-message stream))))

    (with-open [stream (s/stream)]
      (s/put! stream :not-a-char)

      (t/is (match?
             [:de.otto.nom.core/anomaly
              :cognitect.anomalies/incorrect
              {:cognitect.anomalies/message "Received a non-character while reading the next DAP message."
               ::stream/value :not-a-char}]
             (stream/read-message stream))))))

(t/deftest reader-into-stream!
  (t/testing "it reads a reader into"
    (with-open [stream (s/stream 24)
                reader (io/reader (.getBytes "abc"))]
      (stream/reader-into-stream!
       {:reader reader
        :stream stream})
      (t/is (match? [\a \b \c] (s/stream->seq (s/map char stream))))

      (t/testing "and closes the stream at the end"
        (t/is (s/closed? stream)))))

  (t/testing "if the stream is already closed it stops instantly"
    (with-open [stream (s/stream 24)
                reader (io/reader (.getBytes "abc"))]
      (s/close! stream)
      (stream/reader-into-stream!
       {:reader reader
        :stream stream})
      (t/is (empty? (s/stream->seq (s/map char stream))))

      (t/testing "but the reader is still open and can be read"
        (t/is (= \a (char (.read reader)))))))

  (t/testing "if the reader is closed the stream closes too"
    (with-open [stream (s/stream 24)
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

(t/deftest partition-anomalies
  (t/testing "it puts anomalies onto the anomaly stream, yay"
    (with-open [values (s/stream)
                anomalies (s/stream)
                good-values (stream/partition-anomalies values identity anomalies)]
      (let [good-values! (atom [])
            anomalies! (atom [])]
        (s/consume #(swap! good-values! conj %) good-values)
        (s/consume #(swap! anomalies! conj %) anomalies)
        @(s/put! values "foo")
        @(s/put! values "bar")
        @(s/put! values (nom/fail :uhoh {:x 10}))
        @(s/put! values "baz")
        @(s/put! values (nom/fail :boom {:y 20}))
        (s/close! values)
        (t/is (= [[:de.otto.nom.core/anomaly :uhoh {:x 10}]
                  [:de.otto.nom.core/anomaly :boom {:y 20}]]
                 @anomalies!))
        (t/is (= ["foo" "bar" "baz"]
                 @good-values!))))))
