(ns clojure-dap.util-test
  (:require [clojure.test :as t]
            [clojure-dap.util :as util]))

(t/deftest with-thread
  (t/testing "returns a deffered we can pull a value out of"
    (t/is (= 30 @(util/with-thread ::foo
                   (+ 10 20)))))

  (t/testing "exceptions can be pulled out of the deffered"
    (t/is (thrown?
           AssertionError
           @(util/with-thread ::foo
              (assert false)))))

  (t/testing "if the exception isn't pulled out, it logs"
    (t/is (re-find
           #"Caught error in thread :clojure-dap.util-test/foo"
           (with-out-str
             (binding [*err* *out*]
               (try
                 @(util/with-thread ::foo
                    (assert false))
                 (catch AssertionError _))))))))
