(ns clojure-dap.debuggee.nrepl
  "Connects to an nREPL server and uses the CIDER debugger middleware as an implementation."
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [me.raynes.fs :as rfs]
            [nrepl.core :as nrepl]
            [de.otto.nom.core :as nom]
            [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.debuggee :as debuggee]))

(defn set-breakpoints [this _opts]
  (nom/try-nom
    (if (:fail? this)
      (nom/fail ::set-breakpoints-failure {:detail "Oh no!"})
      nil)))

(defn evaluate [this {:keys [expression]}]
  (nom/try-nom
    (log/info "---"
              (nrepl/message
               (get-in this [:connection :client])
               {:op "eval"
                :code expression}))
    nil))

(schema/define!
  ::create-opts
  [:map
   [:host {:optional true} :string]
   [:port {:optional true} pos-int?]
   [:port-file-name {:optional true} :string]
   [:root-dir {:optional true} :string]
   [:response-timeout-ms {:optional true} pos-int?]])

(mx/defn create :- (schema/result ::debuggee/debuggee)
  [{:keys [host port port-file-name root-dir response-timeout-ms]
    :or {host "127.0.0.1"
         port-file-name ".nrepl-port"
         root-dir "."
         response-timeout-ms 1000}} :- ::create-opts]
  (nom/try-nom
    (let [port (or port
                   (let [f (io/file root-dir port-file-name)]
                     (when (rfs/readable? f)
                       (parse-long (slurp f)))))
          transport (nrepl/connect
                     {:host host
                      :port port})
          client (nrepl/client transport response-timeout-ms)]

      (try
        (doall (nrepl/message client {:op "init-debugger"}))
        (catch Exception e
          (log/error e "Exception from init-debugger, closing transport")
          (.close transport)
          (throw e)))

      {:connection {:transport transport
                    :client client}
       :set-breakpoints set-breakpoints
       :evaluate evaluate})))
