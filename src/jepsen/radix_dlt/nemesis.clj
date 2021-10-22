(ns jepsen.radix-dlt.nemesis
  "Fault injection for Radix clusters"
  (:require [clojure.tools.logging :refer [info warn]]
            [dom-top.core :as dt :refer [letr]]
            [jepsen [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as n]
                    [util :as util :refer [pprint-str rand-nth-empty]]]
            [jepsen.nemesis [combined :as nc]
                            [membership :as membership]]
            [jepsen.radix-dlt [client :as rc]
                              [db :as rdb]]
            [slingshot.slingshot :refer [try+ throw+]]))


(def staker
  "Which account do we use to restake funds?"
  2)

(def supermajority
  "How much stake do you need to dominate consensus?"
  2/3)

(defn total-stake
  "What's the total stake in this view of the cluster?"
  [view]
  (->> (:validators view)
       (map :total-delegated-stake)
       (reduce + 0)))

(defn supermajority-node
  "Which node currently holds a supermajority of this view? Nil if no node is
  known."
  [view]
  (letr [total-stake (total-stake view)
         _ (when (= 0 total-stake) (return nil))
         threshold (* supermajority total-stake)]
    (->> view
         (filter (comp (partial < threshold) :total-delegated-stake))
         first
         :node)))

(defn stake-op
  "Takes a Membership state and returns an op which restakes a supermajority of
  the stake onto a single node, or nil if no such op is needed."
  [{:keys [view pending] :as membership}]
  ; Look at the distribution of stake
  (letr [_ (when (seq pending)
             ; Don't bother emitting a stake action if one is still
             ; pending.
             (return nil))
         total-stake (total-stake view)
         _ (when (= 0 total-stake) (return nil))
         ; Pick a target we want to make a heavyweight
         heavyweight (last (sort-by (juxt :total-delegated-stake
                                          :address)
                                    (:validators view)))
         ; What's their fraction of the total?
         heavyweight-frac (/ (:total-delegated-stake heavyweight)
                             total-stake)
         ; How much do we need to add to tip them into supermajority territory?
         ;             supermaj = (heavy + x) / (total + x)
         ; supermaj (total + x) = heavy + x
         ; supermaj * total + supermaj * x = heavy + x
         ; supermaj * total - heavy = x - (supermaj * x)
         ; supermaj * total - heavy = x * (1 - supermaj)
         ; supermaj * total - heavy / (1 - supermaj) = x
         x (/ (- (* supermajority total-stake)
                 (:total-delegated-stake heavyweight))
              (- 1 supermajority))
         ; Give a liiiittle extra just to make sure--not sure if 2/3 exactly
         ; is enough or not.
         x (inc x)
         ;_ (info :heavyweight heavyweight :frac heavyweight-frac :has (:total-delegated-stake heavyweight) :needs x)
         ]
    {:type :info, :f :stake, :value {:validator (:address heavyweight)
                                     :amount x}}))

(defn active-nodes
  "Returns all nodes which are currently participating in the cluster."
  [{:keys [nodes free]}]
  (remove free nodes))

(defn removable-node?
  "Can we remove this node from the given membership without taking it below
  the minimum stake?"
  [{:keys [view nodes free] :as membership} node]
  ;(info (pprint-str view))
  (letr [total-stake (total-stake view)
         _ (when (zero? total-stake)
             (return nil))
         ; How much stake are on active nodes?
         active-nodes (set (active-nodes membership))
         ;_ (info :active-nodes active-nodes)
         active-stake (->> (:validators view)
                           (filter (comp active-nodes :node))
                           (map :total-delegated-stake)
                           (reduce +))
         ; How much stake is on this node?
         node-stake (->> (:validators view)
                         (filter (comp #{node} :node))
                         first
                         :total-delegated-stake)
         _ (when (nil? node-stake)
             ; We don't know how much stake this node has; assume it's
             ; removable.
             ; (info "Node" node "has no stake in our validator view" (pprint-str (:validators view)) "so we're assuming it's removable.")
             (return true))
         ; How much stake would we have if we removed this node?
         projected-stake (- active-stake node-stake)
         ; Would that be a supermajority?
         projected-stake-frac (/ projected-stake total-stake)]
    ;(info "Node" node "has stake" node-stake (float (/ node-stake total-stake))
    ;      "with active stake" active-stake (float (/ active-stake total-stake))
    ;      "out of total stake" total-stake)
    (< supermajority projected-stake-frac)))

(defn remove-node-op
  "Takes a Membership state and returns an op (if possible) for removing a node
  from the cluster."
  [{:keys [view free] :as membership}]
  ; Pick a random node not in the free state
  (let [removable (->> (active-nodes membership)
                       (filter (partial removable-node? membership)))]
    (info "Removable nodes:" (sort removable) "(of active " (sort (active-nodes membership)) ")")
    (when (seq removable)
      {:type :info, :f :remove-node, :value (rand-nth removable)})))

(defn add-node-op
  "Takes a Membership state and returns an op (if possible) for
  adding a node to the cluster."
  [{:keys [view free] :as membership}]
  (when-let [node (-> free vec rand-nth-empty)]
    {:type  :info
     :f     :add-node,
     :value node}))

(defn unregister-node-op
  "Takes a membership state and returns an operation for unregistering a node
  as a validator."
  [{:keys [validators view]}]
  ; TODO
  )

(defrecord Membership
  [clients    ; A map of nodes to RadixApi clients.
   node-views ; A map of nodes to that node's view of the cluster
   view       ; Merged view of cluster state
   pending    ; Pending [op op'] pairs.
   free       ; A set of nodes which are not participating in the cluster.
   validators ; A set of nodes we've instructed to become validators.
   ]

  membership/State
  (setup! [this test]
    (assoc this
           ; We're going to need the node set frequently
           :nodes      (:nodes test)
           ; Initially, every node is a validator.
           :validators (set (:nodes test))
           ; Keep a client for each node.
           :clients (->> (:nodes test)
                         (map (juxt identity (partial rc/open test)))
                         (into {}))))

  (node-view [this test node]
    ;(info :fetching-view-for node)
    ;(info :view node (pprint-str (rc/validators (clients node))))
    (try+
      (let [validators (rc/validators (clients node))]
        ; Add node names to each validator, when possible
        {:validators (->> validators
                          (mapv (fn [validator]
                                  (assoc validator :node
                                         (try+
                                           (rdb/validator-address->node
                                             test
                                             (:address validator))
                                           (catch [:type :no-such-validator] e
                                             nil))))))
         ;:validation-info (rdb/validation-node-info node)
         })
      (catch [:type :radix-dlt/failure, :code 1604] e) ; Parse error
      (catch [:type :radix-dlt/failure, :code 1004] e) ; Conn refused
      ))

  (merge-views [this test]
    ; We take each node's own view of its validation node info, and make a map
    ; of nodes to those structures.
    {:validation-info (reduce (fn [vi [node view]]
                                (assoc vi node (:validation-info view)))
                              {}
                              node-views)
     ; And for the validators, we combine all views and pick any value for each
     ; distinct validator key. No way to get a causal timestamp here, far as I
     ; know: we're just gonna be wrong sometimes.
     :validators (->> (vals node-views)
                      (mapcat :validators)
                      (group-by :address)
                      vals
                      (mapv first))})

  (fs [this]
    #{:stake
      :unstake
      :add-node
      :remove-node})

  (op [this test]
    (->> [;(stake-op this)
          (add-node-op this)
          (remove-node-op this)
          :pending]
         (remove nil?)
         vec
         rand-nth))

  (invoke! [this test {:keys [f value] :as op}]
    (case f
      :stake
      (let [key-pair (rc/key-pair staker)
            client   (-> test :nodes rand-nth clients)
            txn'     (-> client
                         (rc/txn! key-pair
                                  "nemesis stake"
                                  [[:stake
                                    (rc/->account-address key-pair)
                                    (:validator value)
                                    (:amount value)]]))]
        ; Await txn
        (update op :value assoc
                :txn-id (:id txn')
                :status @(:status txn')))

      :add-node
      (do (rdb/add-node! test value)
          (assoc op :value [:added value]))

      ; We're doing something simple and maybe unsafe (?): just killing and
      ; wiping the node.
      :remove-node
      (do (rdb/remove-node! test value)
          (assoc op :value [:removed value]))))

  (resolve [this test]
    this)

  (resolve-op [this test [op op']]
    ;(info :resolve-op :op op :op' op')
    (case (:f op)
      ; We assume adds take place immediately too.
      :add-node
      (let [[outcome node] (:value op')]
        (if (= :added outcome)
          (update this :free disj node)
          (throw+ {:type :unexpected-add-node-value
                   :op op'})))

      ; We assume removes take place immediately.
      :remove-node
      (let [[outcome node] (:value op')]
        (if (= :removed outcome)
          ; Record that this node is now free
          (update this :free conj node)
          (throw+ {:type :unexpected-remove-node-value
                   :op op'})))

      ; Stakes resolve immediately too
      :stake
      this

      nil))

  (teardown! [this test]
    ; No way to actually close clients, I think.
    ))

(defn membership-package
  "A membership nemesis package for Radix-DLT"
  [opts]
  (info :membership opts)
  (-> opts
      (assoc
        :membership {:state (map->Membership
                              {:free #{}
                               })
                     :log-node-views? false
                     :log-resolve? true
                     :log-view? true})
      membership/package
      (assoc :perf #{{:name "member"
                      :fs   #{:add-node :remove-node :stake}
                      :color "#A66AD8"}})))

(defn rollback-package
  "Nodes in Radix basically aren't supposed to fail: when they do, a certain
  fraction of transactions will time out until the next leader can take over
  for each round. To that end, they suggest that when you need to perform
  routine maintenance, you have a non-validator standby node running, then copy
  the validator's key file over to that node, stop the old node, and restart
  the new one."
  []
  ; TODO: need capability to change nodes between validators and non-validators
  )

(defn scenario-package
  "A special package which generates specific sequences of faults. Useful for
  targeting specific scenarios instead of random schedules."
  [opts]
  (let [faults (:faults opts)]
    {:generator
     (cond
       ; The repeated-start scenario emits a series of start operations on
       ; every node. Helpful for testing the daemonization code to make sure
       ; repeated starts don't actually start multiple JVMs.
       (:repeated-start faults)
       (repeat {:type :info, :f :start, :value :all}))}))

(defn package
  "Given CLI options, constructs a package of {:generator, :final-generator,
  :nemesis, ..."
  [opts]
  ; Something a bit weird: the underlying packages are going to do their own
  ; scheduling via `gen/stagger` based on the interval we provide, but we also
  ; want to introduce long recovery periods, and we *don't* want the generators
  ; to try and catch up when those recovery windows are over. To that end, we
  ; pass an interval of 0, then wrap the entire thing in our own stagger.
  ;
  ; This is kind of a hack, and points to the need for an alternate version of
  ; stagger that plays well when not asked for operations.
  (let [pkg (-> opts
                nc/nemesis-packages
                (conj (membership-package opts))
                (conj (scenario-package opts))
                nc/compose-packages)]
    ; Intersperse recovery and quiet periods into generator
    (assoc pkg :generator
           (gen/cycle-times
             190 (:generator pkg)
             10 (gen/cycle
                  (gen/phases
                    (gen/log "Recovering")
                    (:final-generator pkg)
                    (repeat 10 (gen/sleep 1))))
             500 (gen/cycle
                   (gen/phases
                     (gen/log "Waiting for recovery")
                     (repeat 500 (gen/sleep 1))
                     ))))))
