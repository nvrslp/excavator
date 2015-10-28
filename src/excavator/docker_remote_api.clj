(ns excavator.docker-remote-api
  (:import (com.spotify.docker.client DockerClient DefaultDockerClient LogStream LogMessage DockerClient$ListContainersParam DockerClient$LogsParam)
           (com.spotify.docker.client.messages ContainerInfo Container ContainerStats MemoryStats)
           (java.nio HeapByteBufferR CharBuffer)
           (java.nio.charset StandardCharsets)))



(defonce docker-client (atom nil))

(defn create-client []
  (reset! docker-client
          (DefaultDockerClient. "unix:///var/run/docker.sock")))

(defn create-temp-client []
  (DefaultDockerClient. "unix:///var/run/docker.sock"))

(defn inspect-container [^DockerClient c ^String container-id]
  (.inspectContainer c container-id))

(defn container-info->clj-data [^ContainerInfo container-info]
  (.created container-info))

(defn stats [^DockerClient c ^String container-id]
  (.stats c container-id))

(defn get-stats-info [^ContainerStats s]
  (.memoryStats s))

(defn get-memory-stats-info [^MemoryStats ms]
  (.usage ms))

(defn get-max-memory-usage[^MemoryStats ms]
  (.maxUsage ms))


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

