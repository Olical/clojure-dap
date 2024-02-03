(ns clojure-dap.source
  "Tools for parsing and modifying Clojure source code."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure-dap.util :as util]))

(def read-opts
  {:read-cond :preserve
   :eof nil})

(defn- with-forms
  "Reads the forms from the source string, calling your function `f` with each form. Return ::recur to recur."
  [source f]
  (binding [r/*read-eval* false
            r/*alias-map* identity]
    (let [reader (rt/indexing-push-back-reader source)]
      (loop []
        (when-let [form (r/read read-opts reader)]
          (let [result (f form)]
            (if (= ::recur result)
              (recur)
              result)))))))

(defn find-form-at-line
  "Return the start and end line and column for a top level form found at the given line in the source.

  Returns nil if we don't find anything or if the thing we found lacks positional metadata."
  [{:keys [source line]}]
  (with-forms source
    (fn [form]
      (let [{start-line :line
             end-line :end-line
             :as location}
            (util/safe-meta form)]
        (if (and location (<= start-line line end-line))
          location
          ::recur)))))

(defn find-ns-form
  "Find and return the namespace form in the given source code string, returns it's position."
  [source]
  (with-forms source
    (fn [form]
      (if (and (seq? form) (= 'ns (first form)))
        (util/safe-meta form)
        ::recur))))

(defn extract-position
  "Given a source string and position, extracts the string from the source denoted by the position and returns it. Also takes a lines-fn which is called with the lines after they're extracted, before they're joined. If provided, you can perform any changes to the lines before the joining happens."
  [{:keys [source position lines-fn]}]
  (when position
    (let [{start-line :line
           end-line :end-line
           start-column :column
           end-column :end-column}
          position
          source-lines (->> (line-seq (io/reader (char-array source)))
                            (drop (dec start-line))
                            (take (inc (- end-line start-line)))
                            (vec))]
      (-> source-lines
          (update 0 subs (dec start-column))
          (update (dec (count source-lines)) subs 0 (dec end-column))
          (cond-> lines-fn (lines-fn))
          (->> (str/join "\n"))))))

(defn insert-break-at-line
  "Given a position (from find-form-at-line), source string and line number, will parse out the form at the position, insert a #break statement at the line and return it.

  The line number is not relative, it starts from the first line of the file."
  [{:keys [position source line]}]
  (extract-position
   {:source source
    :position position
    :lines-fn
    (fn [lines]
      (update lines (- line (:line position)) #(str "#break " %)))}))

(defn insert-breakpoints
  "Given a source string and a seq of breakpoints, inserts #break statements at the start of each of those lines. If the breakpoint is out of bounds, it's ignored. Lines start at 1."
  [{:keys [source breakpoints]}]
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
