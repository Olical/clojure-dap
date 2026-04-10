(ns clojure-dap.debuggee.nrepl
  "Connects to an nREPL server and uses the CIDER debugger middleware as an implementation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel]
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
     (tel/log! {:level :debug :data {:instrumented-source instrumented-source
                                     :result results}}
               "set-breakpoints results")
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
     (tel/log! :debug ["evaluate results" messages])
     {:result
      (->> messages
           (keep (fn [result]
                   (some result #{:out :err :value})))
           (str/join))})))

;; TODO Don't treat sessions as threads, let's actually get the thread IDs.
(defn threads [this]
  (nom/try-nom
   (let [messages (nrepl/message
                   (get-in this [:connection :client])
                   {:op "ls-sessions"})
         [{:keys [sessions]}] messages]
     (tel/log! :debug ["ls-sessions results" messages])
     {:threads
      (map
       (fn [session-id]
         {:id (hash session-id)
          :name session-id})
       sessions)})))

(defn stack-trace [this _opts]
  (nom/try-nom
   (if-let [bp @(:breakpoint-state! this)]
     {:stackFrames [{:id 1
                     :name (or (:code bp) "unknown")
                     :source {:path (:file bp)}
                     :line (:line bp)
                     :column (:column bp)}]
      :totalFrames 1}
     {:stackFrames [] :totalFrames 0})))

(defn scopes [this _opts]
  (nom/try-nom
   (if-let [_bp @(:breakpoint-state! this)]
     {:scopes [{:name "Locals"
                :variablesReference 1
                :expensive false}]}
     {:scopes []})))

(defn variables [this _opts]
  (nom/try-nom
   (if-let [bp @(:breakpoint-state! this)]
     {:variables (mapv (fn [[n v]]
                         {:name n :value v :variablesReference 0})
                       (:locals bp))}
     {:variables []})))

(defn- send-debug-input
  "Send a debug-input command to CIDER and clear the breakpoint state."
  [this command]
  (nom/try-nom
   (let [bp @(:breakpoint-state! this)]
     (when bp
       (let [client (get-in this [:connection :client])]
         (tel/log! :debug ["Sending debug-input" command "with key" (:key bp)])
         (nrepl/message client {:op "debug-input"
                                :key (:key bp)
                                :input (str command)}))
       (reset! (:breakpoint-state! this) nil))
     {})))

(defn continue-command [this _opts]
  (send-debug-input this :continue))

(defn next-command [this _opts]
  (send-debug-input this :next))

(defn step-in-command [this _opts]
  (send-debug-input this :in))

(defn step-out-command [this _opts]
  (send-debug-input this :out))

(mx/defn handle-init-debugger-output
  :- [:maybe [:sequential ::protocol/message]]
  "Takes an nREPL message from the init-debugger call (such as need-debug-input) and turns it into messages to send up to the DAP client."
  [{:keys [status session]
    :as message}
   :- [:map [:status [:vector :string]]]
   breakpoint-state!
   :- ::schema/atom]
  (let [status (set (map keyword status))]
    (when (:need-debug-input status)
      (reset! breakpoint-state! (select-keys message [:key :debug-value :coor :locals
                                                      :file :line :column :code
                                                      :input-type :session :original-ns]))
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
                 {:session (nrepl/new-session raw-client)})
         breakpoint-state! (atom nil)]

     (util/with-thread ::init-debugger
       @(s/connect-via
         (s/->source (nrepl/message client {:op "init-debugger"}))
         (fn [message]
           (tel/log! :info ["init-debugger output" message])
           (s/put-all! output-stream (handle-init-debugger-output message breakpoint-state!)))
         output-stream))

     {:connection {:transport transport
                   :client client}
      :breakpoint-state! breakpoint-state!
      :set-breakpoints set-breakpoints
      :evaluate evaluate
      :threads threads
      :stack-trace stack-trace
      :scopes scopes
      :variables variables
      :continue continue-command
      :next next-command
      :step-in step-in-command
      :step-out step-out-command})))
