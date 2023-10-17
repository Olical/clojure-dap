(ns clojure-dap.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [manifold.stream :as s]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.main :as main]))

;; TODO More tests, break it!
(t/deftest start-server-with-io
  (t/testing "responds to an initialize request appropriately"
    (with-open [output-writer (java.io.StringWriter.)
                input-reader (io/reader
                              (.getBytes
                               (protocol/render-message
                                {:seq 1
                                 :type "request"
                                 :command "initialize"
                                 :arguments {:adapterID "12345"}})))]
      (let [{:keys [server-complete anomalies-stream]}
            (main/start-server-with-io
             {:input-reader input-reader
              :output-writer output-writer})]

        @server-complete

        (t/is (= (str/join
                  (map
                   protocol/render-message
                   [{:request_seq 1
                     :command "initialize"
                     :type "response"
                     :success true
                     :seq 1
                     :body {:supportsConfigurationDoneRequest false
                            :supportsCancelRequest false}}
                    {:type "event"
                     :event "initialized"
                     :seq 2}]))
                 (str output-writer)))
        (t/is (= [] (vec (s/stream->seq anomalies-stream))))))))
