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
            [clojure-dap.debuggee :as debuggee]
            [nrepl.transport :as nrepl-transport]))

;; ---------------------------------------------------------------------------
;; Debug state machine
;;
;; States:
;;   :idle                       - no breakpoint active
;;   :stopped                    - stopped at a breakpoint
;;   :awaiting-expression-prompt - sent :eval debug-input, waiting for CIDER
;;                                 to ask for the expression
;;   :awaiting-eval-result       - sent expression, waiting for CIDER to
;;                                 return to the breakpoint with the result
;;
;; The state lives in an atom: {:state :idle, :breakpoint nil, ...}
;; Only the init-debugger handler thread transitions state.
;; ---------------------------------------------------------------------------

(defn initial-debug-state []
  {:state :idle
   :breakpoint nil
   :eval-expression nil
   :eval-result! nil})

(defn breakpoint-line
  "Compute the actual breakpoint line from the CIDER debug state.
  CIDER reports the form's start line, but the #break may be deeper
  in the form. We find all #break positions and use the one matching
  the debug-value's position (last coor element as a rough guide)."
  [{:keys [line code coor]}]
  (if (and code (str/includes? code "#break"))
    (let [break-lines (->> (str/split-lines code)
                           (map-indexed vector)
                           (filter (fn [[_i l]] (str/includes? l "#break")))
                           (mapv (fn [[i _l]] (+ line i))))
          break-idx (min (max 0 (dec (count coor)))
                         (dec (count break-lines)))]
      (get break-lines break-idx line))
    line))

(defn- frame-name
  "Extract a clean frame name from the breakpoint state."
  [{:keys [original-ns code debug-value]}]
  (let [fn-name (when code
                  (second (re-find #"^\(defn?\s+(\S+)" code)))]
    (if fn-name
      (str (or original-ns "?") "/" fn-name)
      (or debug-value "unknown"))))

(defn- extract-breakpoint
  "Extract breakpoint fields from a CIDER need-debug-input message."
  [message]
  (select-keys message [:key :debug-value :coor :locals
                        :file :line :column :code
                        :input-type :session :original-ns]))

(defn- expression-prompt?
  "Returns true if a need-debug-input message is an expression prompt
  (CIDER asking for an expression to evaluate), not a breakpoint stop."
  [message]
  (let [input-type (:input-type message)]
    ;; Expression prompts have input-type as a string, not a vector
    ;; Or sometimes as a keyword ":expression"
    (or (= input-type "expression")
        (= input-type ":expression")
        (= input-type :expression))))

(defn handle-init-debugger-output
  "State machine for processing init-debugger messages.
  Returns a vector of [new-debug-state dap-events-to-emit].

  transport and session-id are used to send debug-input messages directly
  on the wire without consuming responses (which would steal from the
  init-debugger stream)."
  [debug-state message transport session-id]
  (let [{:keys [status session] :as message} message
        status-set (set (map keyword (or status [])))]
    (if-not (:need-debug-input status-set)
      ;; Not a debug-input request - no state change
      [debug-state nil]

      ;; need-debug-input message - transition based on current state
      (case (:state debug-state)
        ;; Idle or stopped: a new breakpoint hit
        (:idle :stopped)
        (if (:instrumenting? debug-state)
          ;; Breakpoint hit during setBreakpoints instrumentation.
          ;; Auto-continue so the eval can complete.
          (do
            (tel/log! :debug ["State machine: auto-continuing breakpoint during instrumentation"])
            (nrepl-transport/send transport {:op "debug-input"
                                             :session session-id
                                             :key (:key message)
                                             :input ":continue"})
            [debug-state nil])
          (let [bp (extract-breakpoint message)]
            (tel/log! :debug ["State machine: -> :stopped" {:key (:key bp)}])
            [{:state :stopped
              :breakpoint bp
              :eval-expression nil
              :eval-result! nil}
             [{:type "event"
               :event "stopped"
               :seq protocol/seq-placeholder
               :body {:reason "breakpoint"
                      :threadId (hash session)}}]]))

        ;; Awaiting expression prompt: CIDER is asking for the expression
        :awaiting-expression-prompt
        (if (expression-prompt? message)
          (do
            (tel/log! :debug ["State machine: expression prompt received, sending expression"
                              (:eval-expression debug-state)])
            ;; Send the expression via transport directly (not nrepl/message)
            ;; to avoid consuming responses from the init-debugger stream
            (nrepl-transport/send transport {:op "debug-input"
                                             :session session-id
                                             :key (:key message)
                                             :input (:eval-expression debug-state)})
            [(assoc debug-state :state :awaiting-eval-result)
             nil])
          ;; Unexpected: got a breakpoint instead of expression prompt
          ;; Deliver error to the eval promise and handle as normal breakpoint
          (do
            (tel/log! :warn ["State machine: expected expression prompt, got breakpoint"])
            (when-let [p (:eval-result! debug-state)]
              (deliver p {:error "Unexpected state: got breakpoint instead of expression prompt"}))
            (let [bp (extract-breakpoint message)]
              [{:state :stopped
                :breakpoint bp
                :eval-expression nil
                :eval-result! nil}
               [{:type "event"
                 :event "stopped"
                 :seq protocol/seq-placeholder
                 :body {:reason "breakpoint"
                        :threadId (hash session)}}]])))

        ;; Awaiting eval result: CIDER is returning to the breakpoint
        :awaiting-eval-result
        (let [bp (extract-breakpoint message)]
          (tel/log! :debug ["State machine: eval complete, back at breakpoint"])
          ;; Deliver the debug-value as the eval result
          (when-let [p (:eval-result! debug-state)]
            (deliver p {:result (or (:debug-value message) "nil")}))
          ;; We're back at the breakpoint - don't emit stopped again,
          ;; the client still thinks we're stopped
          [{:state :stopped
            :breakpoint bp
            :eval-expression nil
            :eval-result! nil}
           nil])))))

;; ---------------------------------------------------------------------------
;; Debuggee interface methods
;; ---------------------------------------------------------------------------

(defn set-breakpoints [this {:keys [source breakpoints]}]
  (nom/try-nom
   (let [path (:path source)
         source (slurp path)
         client (get-in this [:connection :client])
         instrumented-source (source/insert-breakpoints
                              {:source source
                               :breakpoints breakpoints})
         forms (source/read-all-forms instrumented-source)]
     ;; Mark that we're instrumenting so the init-debugger handler
     ;; auto-continues any breakpoints hit during eval.
     (swap! (:debug-state! this) assoc :instrumenting? true)
     (doseq [{:keys [form position]} forms]
       (tel/log! :debug ["set-breakpoints: eval form at line" (:line position)])
       (doall
        (nrepl/message
         client
         {:op "eval"
          :file path
          :code form
          :line (:line position)
          :column (:column position)})))
     (swap! (:debug-state! this) assoc :instrumenting? false)
     {:breakpoints
      (mapv
       (fn [breakpoint]
         (assoc breakpoint :verified true))
       breakpoints)})))

(defn evaluate [this {:keys [expression]}]
  (nom/try-nom
   (let [debug-state @(:debug-state! this)]
     (if (and (= :stopped (:state debug-state))
              (:breakpoint debug-state))
       ;; At a breakpoint - use CIDER's eval debug-input for local access
       (let [result-promise (promise)
             bp (:breakpoint debug-state)
             client (get-in this [:connection :client])]
         ;; Transition to awaiting-expression-prompt
         (swap! (:debug-state! this)
                assoc
                :state :awaiting-expression-prompt
                :eval-expression expression
                :eval-result! result-promise)
         ;; Send :eval debug-input via the debug transport
         (tel/log! :debug ["Sending :eval debug-input for expression" expression])
         (let [debug-transport (get-in this [:connection :debug-transport])
               debug-session-id (get-in this [:connection :debug-session-id])]
           (nrepl-transport/send debug-transport {:op "debug-input"
                                                  :session debug-session-id
                                                  :key (:key bp)
                                                  :input ":eval"}))
         ;; Wait for the result with a timeout
         (let [result (deref result-promise 10000 {:error "Evaluate timed out"})]
           (tel/log! :debug ["Eval result:" result])
           (if (:error result)
             (nom/fail ::eval-error {::nom/message (:error result)})
             result)))

       ;; Not at a breakpoint - regular eval.
       ;; If the eval triggers a #break, it will block here until the user
       ;; continues via DAP. This is fine because the server processes
       ;; messages concurrently, so continue/step can be handled while
       ;; this eval is pending.
       (let [messages (nrepl/message
                       (get-in this [:connection :client])
                       {:op "eval"
                        :code expression})]
         (tel/log! :debug ["evaluate results" messages])
         {:result
          (->> messages
               (keep (fn [result]
                       (some result #{:out :err :value})))
               (str/join))})))))

(defn threads [this]
  (nom/try-nom
   ;; Return a single thread representing the debug session.
   ;; The thread ID must match what we send in stopped events.
   (let [debug-session-id (get-in this [:connection :debug-session-id])]
     {:threads [{:id (hash debug-session-id)
                 :name "main"}]})))

(defn stack-trace [this _opts]
  (nom/try-nom
   (if-let [bp (:breakpoint @(:debug-state! this))]
     (let [path (:file bp)
           filename (when path (last (str/split path #"/")))]
       {:stackFrames [{:id 1
                       :name (frame-name bp)
                       :source {:path path
                                :name (or filename path)}
                       :line (breakpoint-line bp)
                       :column (:column bp)}]
        :totalFrames 1})
     {:stackFrames [] :totalFrames 0})))

(defn scopes [this _opts]
  (nom/try-nom
   (if-let [_bp (:breakpoint @(:debug-state! this))]
     {:scopes [{:name "Locals"
                :variablesReference 1
                :expensive false}]}
     {:scopes []})))

(defn variables [this _opts]
  (nom/try-nom
   (if-let [bp (:breakpoint @(:debug-state! this))]
     {:variables (into
                  [{:name "(result)" :value (or (:debug-value bp) "nil") :variablesReference 0}]
                  (mapv (fn [[n v]]
                          {:name n :value v :variablesReference 0})
                        (:locals bp)))}
     {:variables []})))

(defn- send-debug-input
  "Send a debug-input command to CIDER and clear the breakpoint state."
  [this command]
  (nom/try-nom
   (let [debug-state @(:debug-state! this)
         bp (:breakpoint debug-state)]
     (when bp
       (let [debug-transport (get-in this [:connection :debug-transport])
             debug-session-id (get-in this [:connection :debug-session-id])]
         (tel/log! :debug ["Sending debug-input" command "with key" (:key bp)])
         (nrepl-transport/send debug-transport {:op "debug-input"
                                                :session debug-session-id
                                                :key (:key bp)
                                                :input (str command)}))
       (reset! (:debug-state! this) (initial-debug-state)))
     {})))

(defn continue-command [this _opts]
  (send-debug-input this :continue))

(defn next-command [this _opts]
  (send-debug-input this :next))

(defn step-in-command [this _opts]
  (send-debug-input this :in))

(defn step-out-command [this _opts]
  (send-debug-input this :out))

;; ---------------------------------------------------------------------------
;; Create / lifecycle
;; ---------------------------------------------------------------------------

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
         ;; Two transports: one for debug channel, one for regular ops.
         ;; This avoids message interleaving between the init-debugger
         ;; stream and regular eval/ls-sessions operations.
         debug-transport (nrepl/connect {:host host :port port})
         ops-transport (nrepl/connect {:host host :port port})
         debug-raw-client (nrepl/client debug-transport Long/MAX_VALUE)
         ops-raw-client (nrepl/client ops-transport Long/MAX_VALUE)
         debug-session-id (nrepl/new-session debug-raw-client)
         ops-session-id (nrepl/new-session ops-raw-client)
         debug-client (nrepl/client-session debug-raw-client {:session debug-session-id})
         ops-client (nrepl/client-session ops-raw-client {:session ops-session-id})
         debug-state! (atom (initial-debug-state))]

     ;; Send init-debugger and then read ALL messages from the debug transport.
     ;; We can't use nrepl/message because it terminates on "done" status,
     ;; but the debug channel needs to stay open across multiple breakpoint
     ;; hits and eval exchanges.
     (nrepl-transport/send debug-transport {:op "init-debugger"
                                            :session debug-session-id
                                            :id (str (random-uuid))})

     (util/with-thread ::init-debugger
       (loop []
         (let [message (nrepl-transport/recv debug-transport Long/MAX_VALUE)]
           (when message
             (when (= (:session message) debug-session-id)
               (tel/log! :info ["init-debugger output" message])
               (let [[new-state events] (handle-init-debugger-output
                                         @debug-state! message debug-transport debug-session-id)]
                 (reset! debug-state! new-state)
                 (when events
                   @(s/put-all! output-stream events))))
             (recur)))))

     {:connection {:debug-transport debug-transport
                   :ops-transport ops-transport
                   :ops-session-id ops-session-id
                   :client ops-client
                   :debug-client debug-client
                   :debug-session-id debug-session-id}
      :debug-state! debug-state!
      ;; Backward compat: breakpoint-state! reads from debug-state!
      :breakpoint-state! (reify
                           clojure.lang.IDeref
                           (deref [_] (:breakpoint @debug-state!))
                           clojure.lang.IAtom
                           (reset [_ v]
                             (swap! debug-state! assoc :breakpoint v)
                             v))
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
