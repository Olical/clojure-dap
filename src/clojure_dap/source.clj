(ns clojure-dap.source
  "Tools for parsing and modifying Clojure source code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure-dap.util :as util]))

;; TODO Take a reader or something we can reuse in memory. So that we read the file once and work with it many times, not stream each time, risks race condition.

(defn find-form-at-line
  "Return the start and end line and column for a top level form found at the given line in the input. The input must be able to be cast to an io/reader such as an io/file or char-array.

  Returns nil if we don't find anything or if the thing we found lacks positional metadata."
  [{:keys [input line]}]
  (with-open [r (rt/indexing-push-back-reader (io/reader input))]
    (loop []
      (let [form (r/read {:read-cond :preserve, :eof nil} r)]
        (if (nil? form)
          nil

          (let [{start-line :line
                 end-line :end-line
                 :as location}
                (util/safe-meta form)]

            (if (and location (<= start-line line end-line))
              location
              (recur))))))))

(defn insert-break-at-line
  "Given a position (from find-form-at-line), input (can be given to io/reader) and line number, will parse out the form at the position, insert a #break statement at the line and return it.

  The line number is not relative, it starts from the first line of the file."
  [{:keys [position input line]}]
  (when-let [{start-line :line, end-line :end-line} position]
    (with-open [r (io/reader input)]
      (let [length (inc (- end-line start-line))
            source-lines (->> (line-seq r)
                              (drop (dec start-line))
                              (take length)
                              (vec))]
        (->> (update source-lines (- line start-line) #(str "#break " %))
             (str/join "\n"))))))
