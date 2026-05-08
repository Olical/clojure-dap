(ns dev.repl
  "Boots an nREPL server with CIDER middleware so the Clojure DAP server
  has something to attach to."
  (:require [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]))

(defn -main [& _]
  (let [{:keys [port]} (nrepl/start-server :handler cider-nrepl-handler)]
    (spit ".nrepl-port" (str port))
    (println "nREPL with CIDER middleware ready on port" port)
    (println "Set breakpoints in VS Code and attach via the Clojure DAP extension.")
    (println "Stop with Ctrl+C."))
  @(promise))
