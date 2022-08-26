(ns clojure-dap.main-test
  (:require [clojure.test :as t]
            [clojure-dap.main :as main]))

(t/deftest hello
  (t/testing "math still works"
    (t/is (= 2 (+ 1 1)))))
