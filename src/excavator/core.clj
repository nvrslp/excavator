(ns excavator.core
  (:require [excavator.excavator :as excavator]
            [excavator.docker-remote-api :as docker-remote-api]
            [excavator.aleph-netty :as aleph-netty]
            [excavator.web-socket-client :as ws-client]
            [environ.core :refer [env]]
            [excavator.util :as util]
            [excavator.constants :as constants])
  (:gen-class)
  (:import (com.spotify.docker.client.messages Version)))

(defn notify-user-of-incompatible-version [{:keys [version]}]
  (let [{:keys [api-key]} env]
    (ws-client/one-off-message
      {:user-uuid "excavator-user"
       :api-key   api-key
       :host      constants/main-endpoint}
      {:event-name :incompatible-docker-version
       :data       {:api-key        api-key
                    :version version}})))

(defn -main
  "Starts excavator"
  [& args]

  ;init websocket client
  (ws-client/init)

  (docker-remote-api/init)

  ;check that API key is provided
  (let [{:keys [api-key]} env]
    (when (not (string? api-key))
      (println "[FATAL] API key must be provided")
      ;exit the JVM
      (System/exit (int 0))))

  ;check Docker version compatibility
  (let [^Version version (docker-remote-api/get-docker-version @docker-remote-api/docker-client)
        ^String docker-full-version (.version version)
        ^String docker-major-version (try (subs docker-full-version 0 3) (catch Exception e e))
        ^Long docker-major-version' (util/safe-parse-double docker-major-version nil)]
    ;only proceed with Docker <= 1.8
    (if (and (number? docker-major-version') (<= 1.8 docker-major-version'))
      (println "[INFO] Docker version" docker-full-version)
      (do
        ;notify user of incompatible Docker version
        (notify-user-of-incompatible-version {:version docker-full-version})
        (println "[FATAL] Docker version" docker-full-version "is not supported")
        ;exit the JVM
        (System/exit (int 0)))))

  (aleph-netty/start-server)



  (excavator/init)
  (println "[INFO] Excavator started"))

