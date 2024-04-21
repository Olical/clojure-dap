(ns clojure-dap.debuggee.nrepl
  "Connects to an nREPL server and uses the CIDER debugger middleware as an implementation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [me.raynes.fs :as rfs]
            [nrepl.core :as nrepl]
            [de.otto.nom.core :as nom]
            [malli.experimental :as mx]
            [manifold.stream :as s]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.util :as util]
            [clojure-dap.schema :as schema]
            [clojure-dap.source :as source]
            [clojure-dap.debuggee :as debuggee]))

(defn set-breakpoints [this {:keys [source breakpoints]}]
  (nom/try-nom
    (let [path (:path source)
          source (slurp path)
          client (get-in this [:connection :client])
          instrumented-source (source/insert-breakpoints
                               {:source source
                                :breakpoints breakpoints})
          forms (source/read-all-forms instrumented-source)
          results
          (reduce
           (fn [results {:keys [form position]}]
             (let [prev-ns (:ns (last results))]
               (conj
                results
                (nrepl/combine-responses
                 (nrepl/message
                  client
                  (cond->
                   {:op "eval"
                    :file path
                    :code form
                    :line (:line position)
                    :column (:column position)}
                    prev-ns (assoc :ns prev-ns)))))))
           []
           forms)]
      (log/debug
       "set-breakpoints results"
       {:instrumented-source instrumented-source
        :result results})
      {:breakpoints
       (mapv
        (fn [breakpoint]
          (assoc breakpoint :verified true))
        breakpoints)})))

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

(defn threads [this]
  (nom/try-nom
    (let [messages (nrepl/message
                    (get-in this [:connection :client])
                    {:op "ls-sessions"})
          [{:keys [sessions]}] messages]
      (log/debug "ls-sessions results" messages)
      {:threads
       (map
        (fn [session-id]
          {:id (hash session-id)
           :name session-id})
        sessions)})))

(comment
  ;; Example from the init-debugger message
  {:debug-value "30",
   :original-ns "clojure-dap.main",
   :key "cc2448d9-4858-4199-8536-dba04c491131",
   :locals [["a" "10"] ["b" "20"]],
   :file "/home/olical/repos/Olical/clojure-dap/src/clojure_dap/main.clj",
   :column 1,
   :input-type ["continue" "locals" "inspect" "trace" "here" "continue-all" "next" "out" "inject" "stacktrace" "inspect-prompt" "quit" "in" "eval"],
   :prompt [],
   :coor [3],
   :line 13,
   :status ["need-debug-input"],
   :id "40e61455-0430-45b5-800c-d8bff1faef9b",
   :code "(defn foo [a b]\\n#break   (+ a b))",
   :original-id "ba89be97-bfff-4984-bf16-6718ac10a0cd",
   :session "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"})

(mx/defn handle-init-debugger-output
  :- [:maybe [:sequential ::protocol/message]]
  "Takes an nREPL message from the init-debugger call (such as need-debug-input) and turns it into messages to send up to the DAP client."
  [{:keys [status session]
    :as _message}
   :- [:map [:status [:vector :string]]]]
  (let [status (set (map keyword status))]
    (when (:need-debug-input status)
      [{:type "event"
        :event "stopped"
        :seq protocol/seq-placeholder
        :body {:reason "breakpoint"
               :threadId (hash session)}}])))

(schema/define!
  ::create-opts
  [:map
   [:host {:optional true} :string]
   [:port {:optional true} pos-int?]
   [:port-file-name {:optional true} :string]
   [:root-dir {:optional true} :string]])

(schema/define!
  ::extra-opts
  [:map
   [:output-stream [:fn s/stream?]]])

(mx/defn create :- (schema/result ::debuggee/debuggee)
  [{:keys [host port port-file-name root-dir]
    :or {host "127.0.0.1"
         port-file-name ".nrepl-port"
         root-dir "."}} :- ::create-opts
   {:keys [output-stream]} :- ::extra-opts]
  (nom/try-nom
    (let [port (or port
                   (let [f (io/file root-dir port-file-name)]
                     (when (rfs/readable? f)
                       (parse-long (slurp f)))))
          transport (nrepl/connect
                     {:host host
                      :port port})
          raw-client (nrepl/client transport Long/MAX_VALUE)
          client (nrepl/client-session
                  raw-client
                  {:session (nrepl/new-session raw-client)})]

      (util/with-thread ::init-debugger
        (log/info "Sending init-debugger")
        (run!
         (fn [message]
           (log/info "init-debugger output" message)
           (s/put-all! output-stream (handle-init-debugger-output message)))
         (nrepl/message client {:op "init-debugger"}))
        (log/info "init-debugger ended!"))

      {:connection {:transport transport
                    :client client}
       :set-breakpoints set-breakpoints
       :evaluate evaluate
       :threads threads})))
