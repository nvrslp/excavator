(ns excavator.web-socket-client
  (:require [manifold.stream :as s]
            [clojure.core.async :refer [chan offer! close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]
            [clojure.core.async.impl.protocols :refer [closed?]]
            [aleph.http :as http]
            [excavator.util :as util])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)))


(declare init-persistent-connection)


;STATE
;=============================
(defonce in-order-ch (atom nil))

(defonce ws-clients (atom {}))

(defn add-ws-client [host ws-conn]
  (swap! ws-clients assoc host ws-conn))

(defn get-ws-client [host]
  (get @ws-clients host))

(defn remove-ws-client [host]
  (swap! ws-clients dissoc host))

(defn get-random-socket
  "Return a random {:stream-in-ch (chan) :stream-out-ch (chan)}"
  []
  (let [key-vals
        (->> @ws-clients
             (vals)
             (into []))]
    (if-not (empty? key-vals)
      (rand-nth key-vals)
      nil)))

;=============================

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
      (do
        (println "got exception while trying to connect::")
        (clojure.pprint/pprint s)
        s)
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
  (let [[data _] (alts!! [stream-in-ch (timeout 20000)])]
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

(defn start-ws-heartbeat-loop [{:keys [stream-in-ch stream-out-ch] :as ws-instance}]
  (go-loop []
    (let [_ (println "[INFO] heartbeat request...")
          offer-result (offer! stream-out-ch (util/data-to-transit {:event-name :heartbeat :data {}}))]
      ;when offer-result is not false, stream-out-ch seems to be open, continue sending heartbeats
      (when-not (= false offer-result)
        (<! (timeout 5000))
        (recur)))))

(defn start-ws-server-triggered-loop
  [{:keys [stream-in-ch stream-out-ch host] :as ws-instance} ^ManyToManyChannel output-ch]
  (go-loop []
    ;block here
    (let [[transit-data _] (alts! [stream-in-ch (timeout 10000)])]
      (if (nil? transit-data)
        ;got nil, stream-in-ch read timed out, going to close connection
        (do
          (close-connection ws-instance)
          (remove-ws-client host)
          nil)
        ;else
        (let [{:keys [event-name] :as data} (util/transit-to-data transit-data)]
          (println "[INFO] got heartbeat response")
          ;if not a heartbeat, put it on the output-ch
          (when-not (= :heartbeat event-name)
            (>! output-ch data))
          (recur))))))


(defn init-connection-with-heartbeat
  "Initiates a websocket connection with a heartbeat;
   All incoming messages will be put onto the output-ch
   :singleton-connection? - should we only have one connection to a host (default: true)"
  [{:keys [user-uuid host api-key]
    :or   {host "ws://localhost:8081/"
           api-key "na"}
    :as data}
   ^ManyToManyChannel output-ch]
  (println "[INFO] goint to connect to websocket" data)
  (let [in-order-ch @in-order-ch]
    (>!! in-order-ch
         (fn []
           (let [ws-conn (get-ws-client host)]
             (if (nil? ws-conn)
               ;new ws-conn
               (let [ws-conn (ws-client-instance {:user-uuid user-uuid :host host :api-key api-key})]
                 (when-not (instance? Throwable ws-conn)
                   (start-ws-heartbeat-loop ws-conn)
                   (start-ws-server-triggered-loop ws-conn output-ch)
                   (add-ws-client host ws-conn))
                 ws-conn)
               ;return existing ws-conn
               ws-conn))))))


(defn init-in-order-ch []
  (let [c (util/create-in-order-loop)]
    (reset! in-order-ch c)))

(defn init []
  (init-in-order-ch))