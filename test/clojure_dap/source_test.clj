(ns clojure-dap.source-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure-dap.source :as source]))

(def example-code "(ns foo)

10
:foo-bar

:before (defn some-fn [a]
  (print \"hi\")
  (inc a)) :after

(+ 10 20)

{::something/invalid 10
 ::another 20}")

(defn line [n]
  (source/find-form-at-line
   {:input example-code
    :line n}))

(t/deftest find-form-at-line
  (t/testing "reads a form at a specific line from an in memeory Clojure string"
    (t/is (= {:column 1, :end-column 9, :end-line 1, :line 1}
             (line 1)))
    (t/is (nil? (line 2)))
    (t/is (nil? (line 3)))
    (t/is (nil? (line 4)))
    (t/is (nil? (line 5)))
    (t/is (= {:column 9, :end-column 11, :end-line 8, :line 6}
             (line 6) (line 7) (line 8)))
    (t/is (nil? (line 9)))
    (t/is (= {:column 1, :end-column 10, :end-line 10, :line 10}
             (line 10)))
    (t/is (nil? (line 11)))
    (t/is (= {:line 12, :column 1, :end-line 13, :end-column 15}
             (line 12) (line 13)))
    (t/is (nil? (line 14)))))

(t/deftest insert-break-at-line
  (t/testing "inserts a #break statement at the start of the given line in the source string"
    (t/is (= "(defn some-fn [a]\n#break   (print \"hi\")\n  (inc a))"
             (source/insert-break-at-line
              {:position (line 7)
               :input (io/reader (char-array example-code))
               :line 7})))

    (t/is (= "#break (defn some-fn [a]\n  (print \"hi\")\n  (inc a))"
             (source/insert-break-at-line
              {:position (line 6)
               :input (io/reader (char-array example-code))
               :line 6})))

    (t/is (= "#break (ns foo)"
             (source/insert-break-at-line
              {:position (line 1)
               :input (io/reader (char-array example-code))
               :line 1})))

    (t/is (= "#break (+ 10 20)"
             (source/insert-break-at-line
              {:position (line 10)
               :input (io/reader (char-array example-code))
               :line 10})))

    (t/is (= "#break {::something/invalid 10\n ::another 20}"
             (source/insert-break-at-line
              {:position (line 12)
               :input (io/reader (char-array example-code))
               :line 12})))

    (t/is (= "{::something/invalid 10\n#break  ::another 20}"
             (source/insert-break-at-line
              {:position (line 13)
               :input (io/reader (char-array example-code))
               :line 13})))

    (t/is (= nil
             (source/insert-break-at-line
              {:position nil
               :input (io/reader (char-array example-code))
               :line 7})))))
