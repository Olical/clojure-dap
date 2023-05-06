(ns giants-shoulders.repl
  (:require [nrepl.server :as nrepl]
            [cider.nrepl :as cider]
            [malli.dev :as malli-dev]
            [malli.dev.pretty :as malli-pretty]
            [taoensso.timbre :as log]
            [portal.api :as portal]
            [mount.core :as mount]
            [rebel-readline.core :as rr]
            [rebel-readline.clojure.line-reader :as rr-clr]
            [rebel-readline.clojure.service.local :as rr-csl]
            [rebel-readline.clojure.main :as rr-cm]
            [clojure.main :as clj-main]))

(alter-meta! *ns* assoc :clojure.tools.namespace.repl/load false)

(defn start!
  "Start a development REPL, intended to be invoked from ./scripts/repl"
  [{:keys [portal]}]
  (log/info "Starting malli dev instrumentation")
  (malli-dev/start! {:report (malli-pretty/reporter)})

  (log/info "Starting mount system")
  (mount/start)

  (log/info "Starting nREPL server")
  (let [{:keys [port] :as _server} (nrepl/start-server :handler cider/cider-nrepl-handler)]
    (log/info "nREPL server started on port" port)
    (log/info "Writing port to .nrepl-port")
    (spit ".nrepl-port" port))

  (when portal
    (log/info "Opening portal, use (tap> ...) to inspect values")
    (portal/open)
    (add-tap #'portal/submit))

  (log/info "Starting interactive REPL")
  (rr/with-line-reader
    (rr-clr/create (rr-csl/create))
    (clj-main/repl
     :prompt (fn [])
     :read (rr-cm/create-repl-read)))

  (log/info "Shutting down")

  (when portal
    (log/info "Closing portal")
    (portal/close))

  (log/info "Stopping mount system")
  (mount/stop)

  (shutdown-agents)
  (System/exit 0))
