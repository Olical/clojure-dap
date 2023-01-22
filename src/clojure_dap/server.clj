(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through and an nREPL server to drive from user inputs and it'll handle the rest. Understands both the DAP and nREPL messages, relaying ideas between the two."
  (:require [manifold.stream :as s]
            [manifold.deferred :as d]))

(defn start
  "Creates a new server that contains a few processes. Returns a function that when called will stop the server."
  [{:keys [client-io nrepl-io]}]
  (let [stop-promise! (promise)
        stop-fn (fn []
                  (deliver stop-promise! ::stop)
                  true)]

    ;; Client read loop
    (d/future
      (loop []
        (let [input @(d/alt stop-promise! (s/take! (:input client-io)))]
          (println "===" input)
          (if (= input ::stop)
            ::stopped
            (recur)))))

    ;; nREPL read loop
    (d/future
      (loop []
        (let [input @(d/alt stop-promise! (s/take! (:input nrepl-io)))]
          (println "---" input)
          (if (= input ::stop)
            ::stopped
            (recur)))))

    stop-fn))
