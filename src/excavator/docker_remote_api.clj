(ns excavator.docker-remote-api
  (:require [clojure.core.async :refer [chan dropping-buffer close! offer! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]])
  (:import (com.spotify.docker.client DockerClient DefaultDockerClient LogMessage DockerClient$ListContainersParam DockerClient$LogsParam)
           (com.spotify.docker.client.messages Container)
           (java.nio HeapByteBufferR CharBuffer)
           (java.nio.charset StandardCharsets)
           (clojure.lang Atom)))

(declare init)

(defonce docker-client (atom nil))

(defn create-client []
  (reset! docker-client
          (DefaultDockerClient. "unix:///var/run/docker.sock")))

(defn get-docker-version [^DockerClient c]
  (.version c))


(defn logs [^DockerClient c ^String container-id]
  (.logs c container-id (into-array [(DockerClient$LogsParam/follow)
                                     (DockerClient$LogsParam/stdout)
                                     (DockerClient$LogsParam/stderr)
                                     (DockerClient$LogsParam/tail (int 100))])))

(defn list-containers [^DockerClient c]
  (.listContainers c (into-array [(DockerClient$ListContainersParam/allContainers false)])))

(defn get-container-name
  [^Container c]
  (subs (first (.names c)) 1))

(defn get-container-info [^Container c]
  {:name (get-container-name c)
   :created (.created c)
   :image (.image c)
   :id   (.id c)})


(defn log-message-content [^LogMessage lm]
  (let [^HeapByteBufferR content (.content lm)
        ^CharBuffer char-buffer (.asReadOnlyBuffer content)
        char-set (.decode StandardCharsets/UTF_8 char-buffer)
        a-string (.toString char-set)]
    a-string))


(defn start-ping-loop [^Atom docker-client]
  (thread
    (loop []
      ;wait
      (<!! (timeout 10000))
      (let [ping-result
            (try
              (.ping ^DockerClient @docker-client)
              (catch Exception e e))]
        (if (= "OK" ping-result)
          (recur)
          (init))))))

(defn init []
  (create-client)
  (start-ping-loop docker-client))

