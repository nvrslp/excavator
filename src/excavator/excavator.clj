(ns excavator.excavator
  (:require [excavator.docker-remote-api :as remote-api]
            [clojure.core.async :refer [chan dropping-buffer close! offer! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]
            [manifold.stream :as s]
            [byte-streams :as bs]
            [excavator.constants :as constants]
            [environ.core :refer [env]]
            [excavator.state :as state]
            [aleph.http :as aleph-http]
            [excavator.web-socket-client :as ws-client]
            [excavator.util :as util])
  (:import (clojure.lang Atom IPersistentSet)
           (com.spotify.docker.client LogMessage LogStream DockerClient)))



(declare init)

(defn build-endpoint [ip-address port]
  (str "ws://" ip-address ":" port "/"))

(def live-containers (atom #{}))

(defn create-event->bytes [event-name data]
  (util/data-to-transit
    {:event-name event-name
     :data       data}))

(defn get-external-public-ip! []
  (-> @(aleph-http/get "http://checkip.amazonaws.com/")
      :body
      (bs/to-string)
      (clojure.string/replace #"\n" "")))


;SCHEDULER EVENTS
;=====================================
(defn call-a-monster! [{:keys [src]}]
  (let [{:keys [excavator-host-port api-key]
         :or {excavator-host-port 9081
              api-key @state/api-key}} env
        public-ip (get-external-public-ip!)
        {:keys [data] :as event-response}
        (ws-client/one-off-message
          {:user-uuid "excavator-user"
           :api-key   api-key
           :host      constants/main-endpoint}
          {:event-name :call-a-monster
           :data       {:excavator-public-ip-address public-ip
                        :excavator-port              excavator-host-port
                        :api-key                     api-key
                        :src                         src}})]
    ;docker run -d -v /var/run/docker.sock:/var/run/docker.sock -p 9081:8081 -e "EXCAVATOR_HOST_PORT=9081" -e "API_KEY=your-api-key" rangelspasov/excavator
    (if-not (nil? event-response)
      (let [{:keys [monster]} data
            {:keys [public-ip-address port]} monster]
        (println "CALLED MONSTER OK")
        ;connect to a monster
        (ws-client/init-connection-with-heartbeat
          {:user-uuid "excavator-user-stream"
           :api-key api-key
           :host      (build-endpoint public-ip-address port)}
          ;don't care for server messages
          (chan (dropping-buffer 1))))

      (println "FAILED TO CALL A MONSTER"))
    true))

(defn update-containers-state! [containers-state]
  (let [;ENV
        {:keys [excavator-host-port container-source api-key]
         :or {excavator-host-port 9081
              api-key @state/api-key}}
        env
        ;SRC
        src container-source
        ;make request
        result
        (ws-client/one-off-message
          {:user-uuid "excavator-user"
           :api-key   api-key
           :host      constants/main-endpoint}
          {:event-name :update-containers-state
           :data       {:api-key                     api-key
                        :src                         src
                        :containers-state containers-state}})]
    result))


(def call-a-monster-ch (chan (dropping-buffer 1)))

(defn start-call-a-monster-loop
  "Calls a monster at most once per 10 seconds"
  []
  (thread
    (loop []
      (let [data (<!! call-a-monster-ch)]
        (try
          (call-a-monster! data)
          (catch Exception e e))
        (<!! (timeout 10000))
        (recur)))))

(defn start-container-log-streaming
  "Starts log streaming for a specific container-id"
  [docker-client ^Atom containers {image :image container-id :id container-name :name :as new-live-container}]
  (let [^LogStream log-stream (remote-api/logs docker-client container-id)
        log-stream-source (s/->source log-stream)
        a-chan (chan 1024)
        chan-sink (s/->sink a-chan)
        _ (s/connect log-stream-source chan-sink)
        ;get a random socket for the monster user
        random-socket
        (ws-client/get-random-socket)
        {:keys [container-source]} env]
    ;start async loop
    (go-loop [prev-result :no-prev-result
              {:keys [stream-out-ch] :as a-socket} random-socket]
              ;get a new result if we don't already have one
             (let [^LogMessage result (if (= :no-prev-result prev-result)
                                        ;new result
                                        (<! a-chan)
                                        ;going to retry the same result
                                        prev-result)]
               (if (not (nil? result))
                 ;got data
                 (let [log-line (remote-api/log-message-content result)

                       ;prepare the event
                       event-bytes
                       (create-event->bytes :stream-log-entry {:log-entry
                                                               {:container-name container-name
                                                                :container-id   container-id
                                                                :image-name     image
                                                                :src            container-source
                                                                :log-line       log-line}})]
                   ;send the event if stream-out-ch is available
                   (if stream-out-ch
                     ;try to put on the stream-out-ch
                     (let [result-val (offer! stream-out-ch event-bytes)]
                       ;result-val - true, false or :channel-full
                       (condp = result-val
                         ;looks OK, proceed
                         true (recur :no-prev-result a-socket)
                         ;stream-out-ch closed, retry
                         false (recur result (ws-client/get-random-socket))
                         ;channel is full, call a monster
                         nil (do (println ":channel-full, call a monster")
                                           (>! call-a-monster-ch {:src container-source})
                                           (<! (timeout 1000))
                                           (recur result (ws-client/get-random-socket)))))
                     ;no stream-out-ch, no monsters connected
                     (do (println "no stream-out-ch, call a monster")
                         (>! call-a-monster-ch {:src container-source})
                         (<! (timeout 1000))
                         (recur result (ws-client/get-random-socket)))))
                 ;else, closed
                 (do
                   (println "channel closed, bye")
                   ;remove from atom
                   (swap! containers (fn [x] (disj x new-live-container)))
                   :channel-closed))))))

(defn update-live-containers-loop
  "Runs every few seconds to update the atom live-containers"
  [^Atom docker-client ^Atom live-containers]
  (thread
    (loop []
      (let [current-live-containers
            (into #{} (map remote-api/get-container-info (remote-api/list-containers @docker-client)))

            old-live-containers' @live-containers

            new-live-containers (clojure.set/difference current-live-containers old-live-containers')]
        (when (< 0 (count new-live-containers))
          ;START STREAMING NEW CONTAINERS
          (doseq [{:keys [name id] :as new-live-container} new-live-containers]
            ;save in atom
            (swap! live-containers (fn [^IPersistentSet x] (conj x new-live-container)))
            (println "starting streaming for container:: " name id)
            (if-not (or (.startsWith name "ecs-agent")
                        (.startsWith name "ecs-monster")
                        (.startsWith name "ecs-excavator"))
              (thread
                (start-container-log-streaming @docker-client live-containers new-live-container))
              (println "SKIPPING::" name)))
          ;UPDATE CONTAINER LIST
          (update-containers-state! current-live-containers))
        ;wait
        (<!! (timeout 5000))
        (recur)))))

(defn start-ping-loop [^Atom docker-client]
  (thread
    (loop []
      ;wait
      (<!! (timeout 5000))
      (let [ping-result
            (try
              (.ping ^DockerClient @docker-client)
              (catch Exception e e))]
        (if (= "OK" ping-result)
          (recur)
          (init))))))

(defn init []
  (remote-api/create-client)
  (update-live-containers-loop remote-api/docker-client live-containers)
  (start-ping-loop remote-api/docker-client)
  (start-call-a-monster-loop))