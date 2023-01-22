(ns clojure-dap.util-test
  (:require [clojure.test :as t]
            [cognitect.anomalies :as anom]
            [clojure-dap.util :as util]))

(t/deftest anomaly?
  (t/is (not (util/anomaly? nil)))
  (t/is (not (util/anomaly? {:hi :world})))
  (t/is (util/anomaly? {::anom/category ::anom/fault})))
