(ns clojure-dap.kaocha-hooks
  "Custom hooks for Kaocha used to customise the test runner. See tests.edn for configuration."
  (:require [malli.instrument :as mi]
            [malli.dev.pretty :as malli-pretty]
            [taoensso.telemere :as tel]))

(defn pre-run
  "Run after the config is loaded and the tests are planned. Just before the tests are actually executed."
  [test-plan]
  (mi/unstrument!)
  (mi/instrument! {:report (malli-pretty/thrower)})
  (tel/set-min-level! :trace)
  test-plan)
