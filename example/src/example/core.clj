(ns example.core)

(defn classify [n]
  (cond
    (zero? n) :zero
    (pos? n)  :positive
    :else     :negative))

(defn factorial [n]
  (if (<= n 1)
    1
    (* n (factorial (dec n)))))

(defn analyze
  "Sample function for stepping through with the debugger.
  Set a breakpoint inside the let, then call (analyze 5) from the REPL."
  [n]
  (let [class (classify n)
        fact (when (pos? n) (factorial n))
        result {:n n
                :class class
                :factorial fact}]
    result))

(comment
  (analyze 5)
  (analyze -3)
  (analyze 0))
