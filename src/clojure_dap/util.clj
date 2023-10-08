(ns clojure-dap.util
  "Utility functions! Everyone's favourite namespace."
  (:require [manifold.deferred :as d]
            [taoensso.timbre :as log]))

(defmacro with-thread
  "Create a new thread using manifold.defferred/future. Will catch any Throwable thrown inside and taoensso.timbre/error log it. The exception is then re-thrown so you can pull it out of the deferred if required."
  [thread-name & body]
  (assert
   (and (keyword? thread-name) (not (simple-keyword? thread-name)))
   "with-thread expects a namespaced keyword for a thread-name")

  `(d/future
     (try
       ~@body
       (catch Throwable t#
         (log/error t# "Caught error in thread" ~thread-name)
         (throw t#)))))
