(ns clojure-dap.source
  "Tools for parsing and modifying Clojure source code."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [malli.experimental :as mx]
            [clojure-dap.schema :as schema]
            [clojure-dap.util :as util]))

(mx/defn insert-breakpoints :- :string
  "Given a source string and a seq of breakpoints, inserts #break statements at the start of each of those lines. If the breakpoint is out of bounds, it's ignored. Lines start at 1."
  [{:keys [source breakpoints]}
   :- [:map
       [:source :string]
       [:breakpoints [:vector [:map [:line :int]]]]]]
  (str/join
   "\n"
   (reduce
    (fn [source-lines {:keys [line]}]
      (let [idx (dec line)]
        (cond-> source-lines
          (contains? source-lines idx)
          (update idx #(str "#break " %)))))
    (str/split-lines source)
    breakpoints)))

(schema/define! ::position
  [:map
   [:line :int]
   [:column :int]
   [:end-line :int]
   [:end-column :int]])

(mx/defn extract-position :- :string
  "Given a source string and position, extracts the string from the source denoted by the position and returns it."
  [{:keys [source position]}
   :- [:map
       [:source :string]
       [:position ::position]]]
  (when position
    (let [{start-line :line
           end-line :end-line
           start-column :column
           end-column :end-column}
          position
          source-lines (->> (line-seq (io/reader (char-array source)))
                            (drop (dec start-line))
                            (take (inc (- end-line start-line)))
                            (vec))
          line-count (count source-lines)]
      (str/join
       "\n"
       (if (> line-count 1)
         (-> source-lines
             (update 0 subs (dec start-column))
             (update (dec line-count) subs 0 (dec end-column)))
         (update source-lines 0 subs
                 (dec start-column)
                 (dec end-column)))))))

(mx/defn read-all-forms :- [:vector
                            [:map
                             [:form :string]
                             [:position ::position]]]
  "Reads all the forms from the given source string."
  [source :- :string]
  (binding [r/*read-eval* false
            r/*alias-map* identity
            r/*default-data-reader-fn* (fn [_tag value] value)]
    (let [reader (rt/indexing-push-back-reader source)]
      (loop [forms []]
        (let [form (r/read
                    {:read-cond :preserve
                     :eof ::eof}
                    reader)]
          (if (= ::eof form)
            forms
            (let [position (util/safe-meta form)]
              (recur
               (if position
                 (conj
                  forms
                  {:form (extract-position
                          {:source source
                           :position position})
                   :position position})
                 forms)))))))))
