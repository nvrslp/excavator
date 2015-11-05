(ns excavator.aleph-netty
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [excavator.state :as state]
            [clojure.core.async :refer [chan close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]))


(defn ws-handler-dispatch [{:keys [stream-in-ch stream-out-ch req] :as ws-connection}]
  ;immediately close the websocket connection, nothing to do
  (close! stream-in-ch)
  (close! stream-out-ch))

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
      {:status 200
       :body "got http?"})))

(defn start-server
  "Starts WebSocket server"
  []
  (reset! state/ws-server (http/start-server ws-handler {:port 8082})))
