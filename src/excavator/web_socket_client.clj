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

(defn start-ws-heartbeat-loop [{:keys [stream-in-ch stream-out-ch] :as ws-instance}]
  (go-loop []
           (send-message ws-instance {:event-name :heartbeat :data {}})
           (<! (timeout 5000))
           (recur)))

(defn receive-message [{:keys [stream-in-ch stream-out-ch] :as ws-instance}]
  (let [data (<!! stream-in-ch)]
    (if-not (nil? data)
      (util/transit-to-data data)
      nil)))


(defn close-connection [{:keys [stream-in-ch stream-out-ch] :as ws-instance}]
  (close! stream-in-ch)
  (close! stream-out-ch))

(defn start-ws-server-triggered-loop
  [{:keys [stream-in-ch stream-out-ch] :as ws-instance} ^ManyToManyChannel output-ch]
  (go-loop []
           ;block here
           (let [[transit-data _] (alts! [stream-in-ch (timeout 10000)])]
             (if (nil? transit-data)
               (do
                 (println "got nil, stream-in-ch read timed out, going to close connection")
                 (close-connection ws-instance)
                 ;wait
                 (<! (timeout 3000))
                 ;reconnect
                 (let [ws-conn (init-persistent-connection ws-instance output-ch)]
                   ;if an exception occurred, retry
                   (when (instance? Exception ws-conn)
                     (println "going to retry ws connect...")
                     (recur))))
               ;else
               (let [{:keys [event-name] :as data} (util/transit-to-data transit-data)]
                 ;(println "got data::" data)
                 ;if not a heartbeat, put it on the output-ch
                 (when-not (= :heartbeat event-name)
                   (>! output-ch data))
                 (recur))))))



(defn init-persistent-connection
  "Initiates a persistent websocket connection with a heartbeat;
   All incoming messages will be put onto the output-ch"
  [{:keys [user-uuid host]
    :or   {host "ws://localhost:8081/"}
    :as data}
   ^ManyToManyChannel output-ch]
  (println "goint to connect to ws::" data)
  (let [ws-conn (ws-client-instance {:user-uuid user-uuid :host host})]
    (when-not (instance? Throwable ws-conn)
      (start-ws-heartbeat-loop ws-conn)
      (start-ws-server-triggered-loop ws-conn output-ch))
    ws-conn))


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

