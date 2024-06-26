(ns clojure-dap.debuggee.fake
  "A fake debuggee used for development and testing. All of it's methods have spy wrappers for testing."
  (:require [de.otto.nom.core :as nom]
            [malli.experimental :as mx]
            [spy.core :as spy]
            [clojure-dap.schema :as schema]
            [clojure-dap.debuggee :as debuggee]))

(defn set-breakpoints [this {:keys [_source breakpoints]}]
  (cond
    (:fail? this)
    (nom/fail ::set-breakpoints-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else {:breakpoints (mapv #(assoc % :verified true) breakpoints)}))

(defn evaluate [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::evaluate-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else {:result ":fake-eval-result"}))

(defn threads [this]
  (cond
    (:fail? this)
    (nom/fail ::threads-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else {:threads [{:id -1070493020
                      :name "4ee25650-d4dd-4be0-aaa3-ba832562f5e9"}]}))

(schema/define!
  ::create-opts
  [:map
   [:fail? {:optional true} :boolean]
   [:socket-exception? {:optional true} :boolean]
   [:create-error? {:optional true} :boolean]])

(mx/defn create :- (schema/result ::debuggee/debuggee)
  [{:keys [fail? create-error? socket-exception?]} :- ::create-opts]
  (if create-error?
    (nom/fail ::oh-no {:message "Creation failed!"})
    {:fail? fail?
     :socket-exception? socket-exception?
     :set-breakpoints (spy/spy set-breakpoints)
     :evaluate (spy/spy evaluate)
     :threads (spy/spy threads)}))

(defn stack-trace [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::stack-trace-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else {:todo true}))

(defn scopes [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::scopes-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else {:todo true}))

(defn variables [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::variables-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else {:todo true}))
