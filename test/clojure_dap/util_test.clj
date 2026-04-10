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
           #"End of thread :clojure-dap.util-test/foo - caught error"
           (with-out-str
             (binding [*err* *out*]
               (try
                 @(util/with-thread ::foo
                    (assert false))
                 (catch AssertionError _))))))))

(t/deftest walk-sorted-map
  (t/testing "replaces all maps with sorted maps"
    (let [res (util/walk-sorted-map {:foo 10 :bar {:baz 20}})]
      (t/is (and (sorted? res) (map? res)))
      (t/is (and (sorted? (:bar res)) (map? (:bar res))))
      (t/is (= 10 (:foo res)))
      (t/is (= 20 (get-in res [:bar :baz]))))))

(t/deftest clean-ansi
  (t/testing "strips escape codes"
    (t/is (= " here  we  go!  done."
             (util/clean-ansi
              "\033[1m here \033[45m we \033[31m go! \033[0m done.")))))

(t/deftest malli-reporter
  (t/testing "throws ExceptionInfo with ANSI-cleaned message"
    (let [reporter (util/malli-reporter)]
      (try
        (reporter :malli.instrument/invalid-input
                  {:fn-name 'test-fn
                   :input {:schema [:cat :int]
                           :args ["not-an-int"]
                           :errors [{:in [0] :schema :int :value "not-an-int"}]}})
        (t/is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (t/is (string? (ex-message e)))
          (t/is (not (re-find #"\033\[" (ex-message e)))
                "Message should not contain ANSI escape codes")
          (t/is (= :malli.instrument/invalid-input
                   (:type (ex-data e)))))))))

(t/deftest safe-meta
  (t/testing "returns meta from things that can have it, nil if not"
    (t/is (= {:foo 10} (util/safe-meta (with-meta {} {:foo 10}))))
    (t/is (nil? (util/safe-meta {})))
    (t/is (nil? (util/safe-meta 10)))))
