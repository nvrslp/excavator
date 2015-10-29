(ns excavator.util
  (:require [cognitect.transit :as transit]
            [clojure.core.async :refer [chan dropping-buffer close! offer! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream PrintWriter StringWriter)))

(def byte-array-class (class (byte-array 0)))

(defn data-to-transit [data]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json)]
    (try
      (transit/write writer data)
      (catch Exception e (println e "faulty data::" data)))
    (.toString out)))


(defn transit-to-data [transit-data]
  (let [in (ByteArrayInputStream. (.getBytes transit-data))
        reader (transit/reader in :json)]
    (transit/read reader)))



(defn stack-trace-as-string [^Exception e]
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)]
    (.printStackTrace e pw)
    (.toString sw)))

(defn create-in-order-loop
  "Creates a loop that executes functions in order"
  []
  (let [a-chan (chan)]
    (go
      (loop []
        (let [f (<! a-chan)]
          (try
            (let [result (<! (thread (f)))])
            (catch Exception e e))
          (recur))))
    a-chan))