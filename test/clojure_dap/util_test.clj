(ns clojure-dap.util-test
  (:require [clojure.test :as t]))

(t/deftest placeholder
  (t/is (= 2 (+ 1 1))))
