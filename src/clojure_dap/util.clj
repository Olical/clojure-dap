(ns clojure-dap.util
  "Utility functions! Everyone's favourite namespace."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [manifold.deferred :as d]
            [malli.experimental :as mx]
            [lambdaisland.ansi :as ansi]
            [malli.dev.pretty :as malli-pretty]
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

(mx/defn walk-sorted-map :- [:and [:fn sorted?] :map]
  "Turn the given map and all contained maps into sorted-maps. Useful when you want to JSON encode into a stable order."
  [m :- :map]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into (sorted-map) x)
       x))
   m))

(defn clean-ansi
  "Removes ansi escape sequences from a string.

  https://choomnuan.com/posts-output/2019-09-01-remove-ansi-escape-sequences-in-clojure/"
  [s]
  (str/join (map last (ansi/text->hiccup s))))

(defn malli-reporter
  "A malli reporter that's basically the malli.dev.pretty/thrower but with the ansi escape sequences removed so it displays nicely inside Neovim's UI."
  []
  (let [report (malli-pretty/reporter (malli-pretty/-printer))]
    (fn [type data]
      (let [message (with-out-str (report type data))]
        (throw (ex-info (clean-ansi message) {:type type :data data}))))))
