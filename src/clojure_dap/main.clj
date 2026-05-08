(ns clojure-dap.main
  "Entrypoint for the actual program, handles starting of systems and CLI input."
  (:require [clojure.java.io :as io]
            [taoensso.telemere :as tel]
            [malli.experimental :as mx]
            [malli.instrument :as mi]
            [manifold.stream :as s]
            [clojure-dap.util :as util]
            [clojure-dap.server :as server])
  (:import [java.io File PrintStream]))

(defonce ^PrintStream dap-stdout System/out)

(defn setup-stdio!
  "Redirects System.out and *out* to stderr so anything that prints to stdout
  (telemere defaults, library banners, errant println in user code) lands on
  stderr instead of corrupting the DAP JSON-RPC transport. The original stdout
  is held in dap-stdout for the DAP server to write frames to. Idempotent."
  []
  (System/setOut System/err)
  (alter-var-root #'*out* (constantly *err*))
  (tel/remove-handler! :default/console)
  (tel/add-handler! :stderr
                    (tel/handler:console
                     {:stream :*err*
                      :output-fn (tel/format-signal-fn {})})))

(defn log-path
  "Resolves a sensible log file path for the current OS and ensures the parent
  directory exists. Honors XDG_STATE_HOME on Linux, ~/Library/Logs on macOS,
  and %LOCALAPPDATA% on Windows. The CLOJURE_DAP_LOG environment variable
  overrides everything else."
  []
  (let [override (System/getenv "CLOJURE_DAP_LOG")
        os (System/getProperty "os.name")
        home (System/getProperty "user.home")
        path (cond
               override
               override

               (re-find #"(?i)win" os)
               (str (or (System/getenv "LOCALAPPDATA")
                        (str home File/separator "AppData" File/separator "Local"))
                    File/separator "clojure-dap" File/separator "clojure-dap.log")

               (re-find #"(?i)mac" os)
               (str home "/Library/Logs/clojure-dap/clojure-dap.log")

               :else
               (str (or (System/getenv "XDG_STATE_HOME")
                        (str home "/.local/state"))
                    "/clojure-dap/clojure-dap.log"))
        f (File. ^String path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    path))

(mx/defn run :- :nil
  "CLI entrypoint to the program, boots the system and handles any CLI args."
  [opts :- :map]

  (setup-stdio!)

  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
    (fn []
      (tel/log! :info "Shutdown hook triggered, shutting down...")
      (shutdown-agents)
      (tel/log! :info "All done, goodbye!"))))

  (let [path (log-path)]
    (tel/add-handler! :file
                      (tel/handler:file
                       {:path path
                        :output-fn (tel/format-signal-fn {})}))
    (tel/log! :info ["Logging to" path]))

  (tel/set-min-level! :trace)
  (tel/log! :info ["Starting clojure-dap with configuration:" opts])

  (tel/log! :info "Initialising Malli instrumentation")
  (mi/instrument! {:report (util/malli-reporter)})

  (tel/log! :info "Starting server...")
  (let [{:keys [server-complete anomalies-stream]}
        (server/run-io-wrapped
         {:input-reader (io/reader System/in)
          :output-writer (io/writer dap-stdout)})]
    (s/consume #(tel/log! :error %) anomalies-stream)
    (tel/log! :info "Server started in single session mode (multi session mode will come later)")
    @server-complete)
  (tel/log! :info "Server completed, will exit"))
