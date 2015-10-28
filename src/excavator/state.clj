(ns excavator.state
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [clojure.core.async :refer [chan dropping-buffer close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]
            [excavator.util :as util]))


(def ws-server (atom nil))


;WebSockets state
;map that holds { socket-key uuid } pairs
(def socket-uuid (ref {}))


;map that holds a map like this: { uuid {socket-key-1 {:stream-in-ch (chan) :stream-out-ch (chan)}, socket-key-2 {:stream-in-ch (chan) :stream-out-ch (chan)}} }
(def uuid-sockets (ref {}))

(def api-key (atom "test-key"))

(defn get-socket-uuid
  "Get the user-id for a socket-key"
  [socket-key]
  (get @socket-uuid socket-key))

(defn get-sockets
  "Get all sockets for that user on this server"
  [uuid]
  (get @uuid-sockets uuid))


;socket transaction functions
(defn add-socket-transaction
  "Modifies (adds) socket-uuid and uuid-sockets maps with a Clojure STM transaction"
  [uuid socket-key {:keys [stream-in-ch stream-out-ch] :as ws-chans}]
  (dosync
    (alter socket-uuid assoc socket-key uuid)
    (alter uuid-sockets assoc-in [uuid socket-key] (assoc ws-chans :timestamp (System/currentTimeMillis)))))

(defn remove-socket-transaction
  "Modifies (removes) socket-uuid and uuid-sockets maps with a Clojure STM transaction"
  [uuid socket-key]
  (dosync
    (alter socket-uuid dissoc socket-key)
    (alter uuid-sockets dissoc-in [uuid socket-key])))


;socket transaction functions
(defn add-socket-transaction
  "Modifies (adds) socket-uuid and uuid-sockets maps with a Clojure STM transaction"
  [uuid socket-key {:keys [stream-in-ch stream-out-ch] :as ws-chans}]
  (dosync
    (alter socket-uuid assoc socket-key uuid)
    (alter uuid-sockets assoc-in [uuid socket-key] (assoc ws-chans :timestamp (System/currentTimeMillis)))))

(defn remove-socket-transaction
  "Modifies (removes) socket-uuid and uuid-sockets maps with a Clojure STM transaction"
  [uuid socket-key]
  (dosync
    (alter socket-uuid dissoc socket-key)
    (alter uuid-sockets dissoc-in [uuid socket-key])))

(defn get-random-socket
  "Return a random {:stream-in-ch (chan) :stream-out-ch (chan)} for an user uuid"
  [uuid]
  (let [key-vals
        (->> (get @uuid-sockets uuid)
             (vals)
             (into []))]
    (if-not (empty? key-vals)
      (rand-nth key-vals)
      nil)))

(defn send-to-sockets! [uuid string-or-byte-array]
  "Sends byte-array to all websockets for that user on this server (one or more)"
  (if (or (instance? String string-or-byte-array)
          (instance? util/byte-array-class string-or-byte-array))
    (doseq [{:keys [stream-out-ch]} (vals (get @uuid-sockets uuid))]
      (>!! stream-out-ch string-or-byte-array))))

