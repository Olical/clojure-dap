(ns clojure-dap.source-test
  (:require [clojure.test :as t]
            [clojure-dap.source :as source]))

(def example-code "(ns foo)

10
:foo-bar

(defn some-fn [a]
  (print \"hi\")
  (inc a))

(+ 10 20)")

(defn line [n]
  (source/find-form-at-line
   {:input (char-array example-code)
    :line n}))

(t/deftest find-form-at-line
  (t/testing "reads a form at a specific line from an in memeory Clojure string"
    (t/is (= {:column 1, :end-column 9, :end-line 1, :line 1}
             (line 1)))
    (t/is (nil? (line 2)))
    (t/is (nil? (line 3)))
    (t/is (nil? (line 4)))
    (t/is (nil? (line 5)))
    (t/is (= {:column 1, :end-column 11, :end-line 8, :line 6}
             (line 6) (line 7) (line 8)))
    (t/is (nil? (line 9)))
    (t/is (= {:column 1, :end-column 10, :end-line 10, :line 10}
             (line 10)))
    (t/is (nil? (line 11)))))

(t/deftest insert-break-at-line
  (t/testing "inserts a #break statement at the start of the given line in the source string"
    (t/is (= "(defn some-fn [a]\n#break   (print \"hi\")\n  (inc a))"
             (source/insert-break-at-line
              {:position (line 7)
               :input (char-array example-code)
               :line 7})))

    (t/is (= nil
             (source/insert-break-at-line
              {:position nil
               :input (char-array example-code)
               :line 7})))))
