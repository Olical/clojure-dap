(ns clojure-dap.source-test
  (:require [clojure.test :as t]
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

(t/deftest insert-breakpoints
  (t/testing "inserts breakpoints at each of the given lines"
    (t/is (= "#break (ns foo)\n\n10\n:foo-bar\n\n#break :before (defn some-fn [a]\n#break   (print \"hi\")\n#break   (inc a)) :after\n\n#break (+ 10 20)\n\n#break {::something/invalid 10\n ::another 20}"
             (source/insert-breakpoints
              {:source example-code
               :breakpoints [{:line 1}
                             {:line 6}
                             {:line 7}
                             {:line 8}
                             {:line 10}
                             {:line 12}]}))))

  (t/testing "anything out of bounds just isn't inserted"
    (t/is (= example-code
             (source/insert-breakpoints
              {:source example-code
               :breakpoints [{:line 0}
                             {:line 100}]})))))

(t/deftest extract-position
  (t/testing "extracts a position from the given source string"
    (t/is (= "(ns foo)"
             (source/extract-position
              {:position {:line 1, :column 1, :end-line 1, :end-column 9}
               :source example-code})))))

(t/deftest read-all-forms
  (t/testing "reads all forms from a source string as strings"
    (t/is (= [{:form "(ns foo)",
               :position {:line 1, :column 1, :end-line 1, :end-column 9}}
              {:form "(defn some-fn [a]\n  (print \"hi\")\n  (inc a))",
               :position {:line 6, :column 9, :end-line 8, :end-column 11}}
              {:form "(+ 10 20)",
               :position {:line 10, :column 1, :end-line 10, :end-column 10}}
              {:form "{::something/invalid 10\n ::another 20}",
               :position {:line 12, :column 1, :end-line 13, :end-column 15}}]
             (source/read-all-forms example-code)))
    (t/is (= [] (source/read-all-forms ""))))

  (t/testing "reads with #break tags if present"
    (t/is (= [{:form "(ns foo)",
               :position {:line 1, :column 8, :end-line 1, :end-column 16}}
              {:form
               "(defn some-fn [a]\n#break   (print \"hi\")\n#break   (inc a))",
               :position {:line 6, :column 9, :end-line 8, :end-column 18}}
              {:form "(+ 10 20)",
               :position {:line 10, :column 1, :end-line 10, :end-column 10}}
              {:form "{::something/invalid 10\n#break  ::another 20}",
               :position {:line 12, :column 8, :end-line 13, :end-column 22}}]
             (source/read-all-forms
              (source/insert-breakpoints
               {:source example-code
                :breakpoints [{:line 1}
                              {:line 7}
                              {:line 8}
                              {:line 12}
                              {:line 13}
                              {:line 14}]}))))))
