(ns onyx.system
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [fatal]]
            [onyx.static.logging-configuration :as logging-config]
            [onyx.peer.virtual-peer :refer [virtual-peer]]
            [onyx.messaging.acking-daemon :refer [acking-daemon]]
            [onyx.messaging.messenger-buffer :refer [messenger-buffer]]
            [onyx.messaging.common :refer [messenger messaging-require messaging-peer-group]]
            [onyx.log.zookeeper :refer [zookeeper]]
            [onyx.log.commands.prepare-join-cluster]
            [onyx.log.commands.accept-join-cluster]
            [onyx.log.commands.abort-join-cluster]
            [onyx.log.commands.notify-join-cluster]
            [onyx.log.commands.exhaust-input]
            [onyx.log.commands.seal-output]
            [onyx.log.commands.signal-ready]
            [onyx.log.commands.leave-cluster]
            [onyx.log.commands.submit-job]
            [onyx.log.commands.kill-job]
            [onyx.log.commands.gc]
            [onyx.scheduling.greedy-job-scheduler]
            [onyx.scheduling.balanced-job-scheduler]
            [onyx.scheduling.percentage-job-scheduler]
            [onyx.scheduling.balanced-task-scheduler]
            [onyx.scheduling.percentage-task-scheduler]
            [onyx.plugin.core-async]))

(def development-components [:logging-config :log])

(def client-components [:log :messaging-require])

(def peer-components [:log :messaging-require :messenger-buffer :messenger :acking-daemon :virtual-peer])

(def peer-group-components [:logging-config :messaging-require :messaging-group])

(defn rethrow-component [f]
  (try
    (f)
    (catch Throwable e
      (fatal e)
      (throw (.getCause e)))))

(defrecord OnyxDevelopmentEnv []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this development-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this development-components))))

(defrecord OnyxClient []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this client-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this client-components))))

(defrecord OnyxPeer []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this peer-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this peer-components))))

(defrecord OnyxPeerGroup []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this peer-group-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this peer-group-components))))

(defn messenger-ctor [{:keys [config] :as peer-group}]
  (let [rets ((messenger config) peer-group)]
    (when-not rets
      (throw (ex-info "Could not find Messaging implementation" {:impl (:onyx.messaging/impl config)})))
    rets))

(defn messaging-require-ctor [config]
  (try
    (messaging-require config)
    :loaded
    (catch Throwable e
      (throw (ex-info "Could not find Messaging implementation" {:impl (:onyx.messaging/impl config)})))))

(defn messaging-peer-group-ctor [config]
  (let [rets ((messaging-peer-group config) config)]
    (when-not rets
      (throw (ex-info "Could not find Messaging implementation" {:impl (:onyx.messaging/impl config)})))
    rets))

(defn onyx-development-env
  [config]
  (map->OnyxDevelopmentEnv
    {:logging-config (logging-config/logging-configuration config)
     :log (component/using (zookeeper config) [:logging-config])}))

(defn onyx-client
  [config]
  (map->OnyxClient
    {:messaging-require (messaging-require-ctor config)
     :log (zookeeper config)}))

(defn onyx-peer
  [{:keys [config] :as peer-group}]
  (map->OnyxPeer
   {:log (zookeeper config)
    :messaging-require (messaging-require-ctor config)
    :acking-daemon (component/using (acking-daemon config) [:log])
    :messenger-buffer (component/using (messenger-buffer config) [:log :acking-daemon])
    :messenger (component/using (messenger-ctor peer-group) [:messaging-require :acking-daemon :messenger-buffer])
    :virtual-peer (component/using (virtual-peer config) [:log :acking-daemon :messenger-buffer :messenger])}))

(defn onyx-peer-group
  [config]
  (map->OnyxPeerGroup
    {:config config
     :logging-config (logging-config/logging-configuration config)
     :messaging-require (messaging-require-ctor config)
     :messaging-group (component/using (messaging-peer-group-ctor config) [:messaging-require :logging-config])}))
