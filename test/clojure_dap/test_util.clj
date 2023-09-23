(ns clojure-dap.test-util)

(defn block-until
  [message pred]
  (or
   (some
    (fn [ms]
      (or
       (pred)
       (Thread/sleep ms)))
    (reductions * (repeat 10 2)))

   (throw (ex-info "Timeout from block-until" {::message message}))))

