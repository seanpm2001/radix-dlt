(ns jepsen.radix-dlt.checker.util-test
  (:require [clojure [test :refer :all]]
            [clojure.core.typed :as t]
            [jepsen.util :refer [map-vals]]
            [jepsen.radix-dlt.checker.util :refer :all]
            [knossos.history :as history]))

;(deftest typecheck
;  (is (t/check-ns 'jepsen.radix-dlt.checker.util)))

(def xrd "xrd_rb1qya85pwq")

(defn strip-sub-indices
  "Removes :sub-index fields from a history."
  [history]
  (mapv (fn [op] (dissoc op :sub-index)) history))

(defn check-sub-indices
  "Takes a history and verifies sub-indices are in ascending order."
  [history]
  (is (= (range 0 (count history))
         (map :sub-index history))))

(defn analysis-
  "A stripped down version of analysis which doesn't require a pair index etc."
  [history]
  (let [history (history/index history)
        a       (analysis history)]
    (is (map? (:pair-index a)))
    (is (= history (:history a)))
    (-> a
        (dissoc :pair-index :history)
        (update :accounts (partial map-vals
                                   (fn [account-analysis]
                                     (check-sub-indices (:history account-analysis))
                                     (is (map? (:pair-index account-analysis)))
                                     (-> account-analysis
                                         (update :history strip-sub-indices)
                                         (dissoc :pair-index))))))))

(deftest analysis-test
  (testing "empty"
    (let [history []]
          (is (= {:txn-index  {}
                  :accounts   {}}
                 (analysis- history)))))

  (testing "initial nil balance read"
    (let [history
          [{:index 0, :type :invoke, :process 0, :f :balance, :value {:account 10}}
           {:index 1, :type :ok, :process 0, :f :balance, :value {:account 10, :balance nil}}]]
      (is (= {:txn-index {}
              :accounts {10 {:account 10
                             :history history
                             :txn-log nil
                             :txn-ids #{}
                             :logged-txn-ids #{}
                             :unlogged-txn-ids #{}
                             :inexplicable-balances []}}}
             (analysis- history)))))

  (testing "read of unexpected balance"
    (let [b1  {:index 0, :type :invoke, :process 0, :f :balance, :value {:account 1}}
          b1' {:index 1, :type :ok, :process 0, :f :balance, :value {:account 1, :balance -1}}
          history [b1 b1']]
      (is (= {:txn-index {}
              :accounts {1 {:account 1
                            :history history
                            :txn-log nil
                            :txn-ids #{}
                            :logged-txn-ids #{}
                            :unlogged-txn-ids #{}
                            :inexplicable-balances [{:op       (assoc b1' :sub-index 1)
                                                     :expected #{1000000000000000000000000000000000000000000000N}}]}}}
             (analysis- history)))))

  (testing "unlogged txn"
    (let [t1  {:index 0, :type :invoke, :process 0, :f :txn, :value {:id 0, :from 1, :ops [[:transfer 1 6 50]]}}
          t1' {:index 1, :type :info,   :process 0, :f :txn, :value {:id 0, :from 1, :ops [[:transfer 1 6 50]]}}
          history [t1 t1']]
      (is (= {:txn-index {0 t1'}
              :accounts {1 {:account 1
                            :history history
                            :txn-log nil
                            :txn-ids #{0}
                            :logged-txn-ids #{}
                            :unlogged-txn-ids #{0}
                            :inexplicable-balances []}
                         6 {:account 6
                            :history history
                            :txn-log nil
                            :txn-ids #{0}
                            :logged-txn-ids #{}
                            :unlogged-txn-ids #{0}
                            :inexplicable-balances []}}}
             (analysis- history)))))

  (testing "info txn unlogged but visible by balance read"
    (let [; Inexplicable read: balance of 6 must be 0 initially
          b6  {:index 0, :type :invoke, :process 1, :f :balance, :value {:account 6}}
          b6' {:index 1, :type :ok,     :process 1, :f :balance, :value {:account 6, :balance 50}}
          ; Transaction adds 50 to 6
          t1  {:index 2, :type :invoke, :process 0, :f :txn, :value {:id 0, :from 1, :ops [[:transfer 1 6 50]]}}
          t1' {:index 3, :type :info,   :process 0, :f :txn, :value {:id 0, :from 1, :ops [[:transfer 1 6 50]]}}
          ; Now that same read is explicable
          b6-2  {:index 4, :type :invoke, :process 1, :f :balance, :value {:account 6}}
          b6-2' {:index 5, :type :ok,     :process 1, :f :balance, :value {:account 6, :balance 50}}
          history [b6 b6' t1 t1' b6-2 b6-2']]
      (is (= {:txn-index {0 t1'}
              :accounts {1 {:account 1
                            :history [t1 t1']
                            :txn-log nil
                            :txn-ids #{0}
                            :logged-txn-ids #{}
                            :unlogged-txn-ids #{0}
                            :inexplicable-balances []}
                         6 {:account 6
                            :history history
                            :txn-log nil
                            :txn-ids #{0}
                            :logged-txn-ids #{}
                            :unlogged-txn-ids #{0}
                            :inexplicable-balances [{:op (assoc b6' :sub-index 1)
                                                     :expected #{0}}]}}}
             (analysis- history)))))

  (testing "logged txn"
    (let [; Transaction log 1's view of txn 0
          l1-0 {:fee 100000000000000000N
                 :message "t0"
                 :actions [{:type :transfer
                            :rri xrd
                            :from 1
                            :to 6
                            :amount 50}
                           {:type :transfer
                            :rri xrd
                            :from 1
                            :to 7
                            :amount 60}]}
          ; And the augmented representation with inferred id and balances
          l1-0+ (assoc l1-0
                        :id 0
                        :balance 1000000000000000000000000000000000000000000000N
                        ; On account 1, we should subtract both the fee *and* the 50 XRD
                        :balance' 999999999999999999999999999899999999999999890N)
          ; On log 6, we have the same view
          l6-0 l1-0
          ; But different balances
          l6-0+ (assoc l6-0
                       :id 0
                       :balance 0N
                       :balance' 50)
          t1  {:index 0, :type :invoke, :process 0, :f :txn, :value {:id 0, :from 1, :ops [[:transfer 1 6 50]
                                                                                           [:transfer 1 7 60]]}}
          t1' {:index 1, :type :info,   :process 0, :f :txn, :value {:id 0, :from 1, :ops [[:transfer 1 6 50]
                                                                                           [:transfer 1 7 60]]}}
          l1  {:index 2, :type :invoke, :process 1, :f :txn-log, :value {:account 1}}
          l1' {:index 3, :type :ok,     :process 1, :f :txn-log, :value {:account 1, :txns [l1-0]}}
          l6  {:index 4, :type :invoke, :process 2, :f :txn-log, :value {:account 6}}
          l6' {:index 5, :type :ok,     :process 2, :f :txn-log, :value {:account 6, :txns [l6-0]}}
          history [t1 t1' l1 l1' l6 l6']]
      (is (= {:txn-index {0 t1'}
              :accounts {1 {:account 1
                            :history [t1 t1' l1 l1']
                            :txn-log {:account 1
                                      :txns [l1-0+]
                                      :by-id {0 l1-0+}
                                      :by-balance' {999999999999999999999999999899999999999999890N l1-0+}}
                            :txn-ids #{0}
                            :logged-txn-ids #{0}
                            :unlogged-txn-ids #{}
                            :inexplicable-balances []}
                         ; Also visible on account 6 as a deposit
                         6 {:account 6
                            :history [t1 t1' l6 l6']
                            :txn-log {:account 6
                                      :txns [l6-0+]
                                      :by-id {0 l6-0+}
                                      :by-balance' {50 l6-0+}}
                            :txn-ids #{0}
                            :logged-txn-ids #{0}
                            :unlogged-txn-ids #{}
                            :inexplicable-balances []}
                         ; Not logged on acct 7!
                         7 {:account 7
                            :history [t1 t1']
                            :txn-log nil
                            ; We know this could have touched 7
                            :txn-ids #{0}
                            ; But it wasn't in the log
                            :logged-txn-ids #{}
                            :unlogged-txn-ids #{0}
                            :inexplicable-balances []}}}
             (analysis- history)))))


  )