(ns excavator.aleph-netty
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [excavator.util :as util]
            [excavator.state :as state]
            [clojure.core.async :refer [chan close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]
            [excavator.constants :as constants]))


(defn create-ws-response->bytes [event new-data]
  (util/data-to-transit
    (if (instance? Throwable new-data)
      (do
        (println new-data)
        (assoc event :error {:message     (.getMessage new-data)
                             :stack-trace (util/stack-trace-as-string new-data)}))
      ;else ok, return normal response
      (assoc event :data new-data))))

(defn event-dispatch
  "Routes WebSocket events to the appropriate function"
  [{:keys [event-name data] :or {data {}} :as event} user-uuid]
  ;safety check for data, if not passed assume empty map
  (try
    (condp = event-name
      ;heartbeat
      :heartbeat {}
      ;else,nothing
      {})
    (catch Exception e e)))

(defn ws-handler-dispatch [{:keys [stream-in-ch stream-out-ch req] :as ws-connection}]
  (let [{:keys [headers]} req
        {:keys [sec-websocket-protocol sec-websocket-key]} headers
        ;pass user-uuid and auth-token inside of a base64 encoded transit string
        _ (println "sec-websocket-protocol::" sec-websocket-protocol)
        _ (println "headers:" headers)
        {:keys [user-uuid auth-token]}
        (if sec-websocket-protocol
          (util/decode-websocket-auth-data sec-websocket-protocol)
          headers)]
    (cond
      ;================================
      ;logs service user
      (= constants/monster-user user-uuid)
      (do
        (println "logs service user ...")
        (state/add-socket-transaction user-uuid sec-websocket-key ws-connection)
        (go
          (loop []
            (let [ws-data (<! stream-in-ch)]
              (if-not (nil? ws-data)
                ;received data
                (let [event (util/transit-to-data ws-data)
                      _ (println "GOT event::" event)
                      new-data-ch (thread (event-dispatch event user-uuid))]
                  ;schedule a response
                  (go
                    (let [new-data (<! new-data-ch)]
                      (println "GOT new-data::" new-data)
                      (>! stream-out-ch (create-ws-response->bytes event new-data))))
                  ;recur for next event
                  (recur))
                ;received nil, cleanup
                (state/remove-socket-transaction user-uuid sec-websocket-key))))))
      :else
      ;user NOT OK, close
      (do
        ;send event to client
        (println "invalid auth token ...")
        ;close channel and socket
        (close! stream-out-ch)))))

(defn ws-handler
  "Handler for all WebSocket incoming connections"
  [{:keys [headers] :as req}]
  (if (= "websocket" (get headers "upgrade"))
    ;websocket request
    (let [{:keys [sec-websocket-protocol]} headers
          s @(http/websocket-connection req {:headers {"Sec-WebSocket-Protocol" sec-websocket-protocol}})
          stream-in-ch (chan 1024)
          stream-out-ch (chan 1024)]
      ;connect streams
      (s/connect s stream-in-ch)
      (s/connect stream-out-ch s)
      ;forward to api for processing
      (ws-handler-dispatch
        {:stream-in-ch  stream-in-ch
         :stream-out-ch stream-out-ch
         :req           req}))
    ;else, HTTP
    (do
      (clojure.pprint/pprint req)
      {:status 200
       :body "got http?"})))

(defn start-server
  "Starts WebSocket server"
  []
  (reset! state/ws-server (http/start-server ws-handler {:port 8081})))
