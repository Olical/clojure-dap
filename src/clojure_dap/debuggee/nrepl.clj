(ns clojure-dap.debuggee.nrepl
  "Connects to an nREPL server and uses the CIDER debugger middleware as an implementation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [me.raynes.fs :as rfs]
            [nrepl.core :as nrepl]
            [de.otto.nom.core :as nom]
            [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.debuggee :as debuggee]))

(defn set-breakpoints [this {:keys [source breakpoints]}]
  (nom/try-nom
    (let [path (:path source)
          source-lines (vec (str/split-lines (slurp path)))
          instrumented-source
          (->> (reduce
                (fn [source-lines {:keys [line]}]
                  (update source-lines (dec line) #(str "#break " %)))
                source-lines
                breakpoints)
               (str/join "\n"))]
      (log/debug
       "set-breakpoints eval result"
       (nrepl/combine-responses
        (nrepl/message
         (get-in this [:connection :client])
         {:op "eval"
          :file path
          :code instrumented-source}))))
    {:breakpoints
     (mapv
      (fn [breakpoint]
        (assoc breakpoint :verified true))
      breakpoints)}))

(defn evaluate [this {:keys [expression]}]
  (nom/try-nom
    (let [messages (nrepl/message
                    (get-in this [:connection :client])
                    {:op "eval"
                     :code expression})]
      (log/debug "evaluate results" messages)
      {:result
       (->> messages
            (keep (fn [result]
                    (some result #{:out :err :value})))
            (str/join))})))

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
          raw-client (nrepl/client transport response-timeout-ms)
          client (nrepl/client-session
                  raw-client
                  {:session (nrepl/new-session raw-client)})]

      (nrepl/message client {:op "init-debugger"})

      {:connection {:transport transport
                    :client client}
       :set-breakpoints set-breakpoints
       :evaluate evaluate})))
