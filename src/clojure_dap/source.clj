(ns clojure-dap.source
  "Tools for parsing and modifying Clojure source code."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure-dap.util :as util]))

(def read-opts
  {:read-cond :preserve
   :eof nil})

(defn find-form-at-line
  "Return the start and end line and column for a top level form found at the given line in the input (string or reader).

  Returns nil if we don't find anything or if the thing we found lacks positional metadata."
  [{:keys [input line]}]
  (binding [r/*read-eval* false
            r/*alias-map* identity]
    (let [reader (rt/indexing-push-back-reader input)]
      (loop []
        (when-let [form (r/read read-opts reader)]
          (let [{start-line :line
                 end-line :end-line
                 :as location}
                (util/safe-meta form)]
            (if (and location (<= start-line line end-line))
              location
              (recur))))))))

(defn insert-break-at-line
  "Given a position (from find-form-at-line), input reader and line number, will parse out the form at the position, insert a #break statement at the line and return it.

  The line number is not relative, it starts from the first line of the file."
  [{:keys [position input line]}]
  (when position
    (let [{start-line :line
           end-line :end-line
           start-column :column
           end-column :end-column}
          position

          length (inc (- end-line start-line))
          source-lines (->> (line-seq input)
                            (drop (dec start-line))
                            (take length)
                            (vec))]

      (-> (update source-lines 0 subs (dec start-column))
          (update (dec (count source-lines)) subs 0 (dec end-column))
          (update (- line start-line) #(str "#break " %))
          (->> (str/join "\n"))))))
