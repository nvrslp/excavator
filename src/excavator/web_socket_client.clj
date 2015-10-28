(ns excavator.web-socket-client
  (:require [manifold.stream :as s]
            [clojure.core.async :refer [chan close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]
            [clojure.core.async.impl.protocols :refer [closed?]]
            [aleph.http :as http]
            [excavator.util :as util])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)))


(declare init-persistent-connection)

(defn ws-client-instance [{:keys [user-uuid api-key host]
                           :or   {host "ws://localhost:8081/"
                                  api-key "na"}}]
  (let [s (try @(http/websocket-client
                  host
                  {:headers {:user-uuid user-uuid
                             :api-key api-key}})
               (catch Exception e e))]
    (if (instance? Throwable s)
      ;return exception
      s
      ;else, ok
      (let [stream-in-ch (chan 1024)
            stream-out-ch (chan 1024)]
        (s/connect s stream-in-ch)
        (s/connect stream-out-ch s)
        {:stream-in-ch  stream-in-ch
         :stream-out-ch stream-out-ch
         :host          host
         :user-uuid     user-uuid}))))


(defn send-message [{:keys [stream-in-ch stream-out-ch] :as ws-instance} msg]
  (>!! stream-out-ch (util/data-to-transit msg)))


(defn receive-message [{:keys [stream-in-ch stream-out-ch] :as ws-instance}]
  (let [data (<!! stream-in-ch)]
    (if-not (nil? data)
      (util/transit-to-data data)
      nil)))


(defn close-connection [{:keys [stream-in-ch stream-out-ch] :as ws-instance}]
  (close! stream-in-ch)
  (close! stream-out-ch))


(defn request-response [conn msg]
  (send-message conn msg)
  (receive-message conn))

(defn one-off-message
  "Sends an one-off message and closes the connection"
  [{:keys [user-uuid host api-key]
    :or   {host "ws://localhost:8081/"}
    :as data}
   {:keys [event-name data] :as event}]
  (let [ws-conn (ws-client-instance {:user-uuid user-uuid
                                     :api-key   api-key
                                     :host      host})]
    (if-not (instance? Throwable ws-conn)
      (let [response (request-response ws-conn event)]
        (close-connection ws-conn)
        ;OK
        response)
      ;couldn't connect
      nil)))

