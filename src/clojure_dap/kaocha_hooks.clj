(ns clojure-dap.kaocha-hooks
  "Custom hooks for Kaocha used to customise the test runner. See tests.edn for configuration."
  (:require [malli.instrument :as mi]
            [malli.dev.pretty :as malli-pretty]
            [taoensso.timbre :as log]))

(defn pre-run
  "Run after the config is loaded and the tests are planned. Just before the tests are actually executed."
  [test-plan]
  (mi/instrument! {:report (malli-pretty/thrower)})
  (log/set-min-level! :trace)
  test-plan)
