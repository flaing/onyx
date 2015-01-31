(ns ^:no-doc onyx.peer.function
    (:require [clojure.core.async :refer [chan >! go alts!! close! timeout]]
              [clojure.data.fressian :as fressian]
              [onyx.peer.task-lifecycle-extensions :as l-ext]
              [onyx.peer.pipeline-extensions :as p-ext]
              [onyx.planning :refer [find-task]]
              [onyx.peer.operation :as operation]
              [onyx.extensions :as extensions]
              [taoensso.timbre :refer [debug]]
              [dire.core :refer [with-post-hook!]])
    (:import [java.util UUID]
             [java.security MessageDigest]))

(defn hash-value [x]
  (let [md5 (MessageDigest/getInstance "MD5")]
    (apply str (.digest md5 (.getBytes (pr-str x) "UTF-8")))))

(defn group-message [segment catalog task]
  (let [t (find-task catalog task)]
    (if-let [k (:onyx/group-by-key t)]
      (hash-value (get segment k))
      (when-let [f (:onyx/group-by-fn t)]
        (hash-value ((operation/resolve-fn {:onyx/fn f}) segment))))))

(defn compress-segment [next-tasks catalog segment]
  {:compressed (.array (fressian/write segment))
   :hash-group (reduce (fn [groups t]
                         (assoc groups t (group-message segment catalog t)))
                       {} next-tasks)})

(defmethod l-ext/start-lifecycle? :function
  [_ event]
  {:onyx.core/start-lifecycle? (operation/start-lifecycle? event)})

(defmethod l-ext/inject-lifecycle-resources :function
  [_ {:keys [onyx.core/task-map]}]
  {:onyx.function/fn (operation/resolve-fn task-map)})

(defmethod p-ext/read-batch :default
  [{:keys [onyx.core/messenger] :as event}]
  {:onyx.core/batch (onyx.extensions/receive-messages messenger event)})

(defmethod p-ext/decompress-batch :default
  [{:keys [onyx.core/queue onyx.core/batch] :as event}]
  {:onyx.core/decompressed (map fressian/read batch)})

(defmethod p-ext/apply-fn :default
  [{:keys [onyx.core/batch onyx.core/decompressed onyx.core/params
           onyx.core/task-map] :as event}]
  (let [op (partial operation/apply-fn (:onyx.function/fn event) params)]
    (reduce
     (fn [rets [raw thawed]]
       (let [new-segments (op thawed)
             result (if coll? new-segments) new-segments (into [] new-segments)]
         (-> rets
             (update-in [:onyx.core/results] conj result)
             (assoc-in [:oynx.core/children] raw result))))
     {:onyx.core/results [] :onyx.core/children {}}
     (map vector batch decompressed))))

(defmethod p-ext/compress-batch :default
  [{:keys [onyx.core/results onyx.core/catalog onyx.core/serialized-task]
    :as event}]
  (let [next-tasks (keys (:egress-queues serialized-task))
        compressed-msgs (map (partial compress-segment next-tasks catalog) results)]
    (merge event {:onyx.core/compressed compressed-msgs})))

(defmethod p-ext/write-batch :default
  [{:keys [onyx.core/messenger onyx.core/job-id] :as event}]
  (let [replica @(:onyx.core/replica event)]
    (doseq [task-id (vals (:egress-ids (:onyx.core/serialized-task event)))]
      (let [peers (get-in replica [:allocations job-id task-id])
            active-peers (filter #(= (get-in replica [:peer-state %]) :active) peers)
            target (rand-nth active-peers)]
        (onyx.extensions/send-messages messenger event (get-in replica [:peer-site target]))))))

(defmethod p-ext/seal-resource :default
  [{:keys [] :as event}]
  {})

