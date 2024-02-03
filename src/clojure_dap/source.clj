(ns clojure-dap.source
  "Tools for parsing and modifying Clojure source code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure-dap.util :as util]))

(defn find-form-at-line
  "Return the start and end line and column for a top level form found at the given line in the reader.

  Returns nil if we don't find anything or if the thing we found lacks positional metadata."
  [{:keys [reader line]}]
  (let [reader (rt/indexing-push-back-reader (io/reader reader))]
    (loop []
      (let [form (r/read {:read-cond :preserve, :eof nil} reader)]
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
  "Given a position (from find-form-at-line), reader and line number, will parse out the form at the position, insert a #break statement at the line and return it.

  The line number is not relative, it starts from the first line of the file."
  [{:keys [position reader line]}]
  (when position
    (let [{start-line :line
           end-line :end-line
           start-column :column
           end-column :end-column}
          position

          length (inc (- end-line start-line))
          source-lines (->> (line-seq reader)
                            (drop (dec start-line))
                            (take length)
                            (vec))]

      (-> (update source-lines (- line start-line) #(str "#break " %))
          (update 0 subs (dec start-column))
          (update (dec (count source-lines)) subs 0 (dec end-column))
          (->> (str/join "\n"))))))
