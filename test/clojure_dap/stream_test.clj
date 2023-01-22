(ns clojure-dap.stream-test
  (:require [clojure.test :as t]
            [clojure-dap.stream :as stream]
            [manifold.stream :as s]))

(t/deftest io
  (t/testing "it's a pair of streams, do what you want with them!"
    (let [{:keys [input output]} (stream/io)]
      (t/is (s/stream? input))
      (t/is (s/stream? output)))))
