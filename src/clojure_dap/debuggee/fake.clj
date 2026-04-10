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

(defn stack-trace [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::stack-trace-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else
    (if-let [bp @(:breakpoint-state! this)]
      {:stackFrames [{:id 1
                      :name (:code bp)
                      :source {:path (:file bp)}
                      :line (:line bp)
                      :column (:column bp)}]
       :totalFrames 1}
      {:stackFrames [] :totalFrames 0})))

(defn scopes [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::scopes-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else
    (if-let [_bp @(:breakpoint-state! this)]
      {:scopes [{:name "Locals"
                 :variablesReference 1
                 :expensive false}]}
      {:scopes []})))

(defn variables [this _opts]
  (cond
    (:fail? this)
    (nom/fail ::variables-failure {:detail "Oh no!"})

    (:socket-exception? this)
    (nom/fail ::socket-exception {:exception (java.net.SocketException.)})

    :else
    (if-let [bp @(:breakpoint-state! this)]
      {:variables (mapv (fn [[n v]]
                          {:name n :value v :variablesReference 0})
                        (:locals bp))}
      {:variables []})))

(defn- resume [this _opts]
  (reset! (:breakpoint-state! this) nil)
  {})

(def continue-command resume)
(def next-command resume)
(def step-in-command resume)
(def step-out-command resume)

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
     :breakpoint-state! (atom nil)
     :set-breakpoints (spy/spy set-breakpoints)
     :evaluate (spy/spy evaluate)
     :threads (spy/spy threads)
     :stack-trace (spy/spy stack-trace)
     :scopes (spy/spy scopes)
     :variables (spy/spy variables)
     :continue (spy/spy continue-command)
     :next (spy/spy next-command)
     :step-in (spy/spy step-in-command)
     :step-out (spy/spy step-out-command)}))
