(ns excavator.util
  (:require [cognitect.transit :as transit]
            [base64-clj.core :as base64])
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


(defn base64-ws-sanitized->base64 [s]
  (-> s
      (clojure.string/replace #"_" "=")
      (clojure.string/replace #"PLUS" "+")
      (clojure.string/replace #"SLASH" "/")))

(defn base64-decode [s]
  (base64/decode s))

(defn base64-encode [s]
  (base64/encode s))


(defn decode-websocket-auth-data [string-data]
  (-> string-data
      (base64-ws-sanitized->base64)
      (base64-decode)
      (transit-to-data)))


(defn stack-trace-as-string [^Exception e]
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)]
    (.printStackTrace e pw)
    (.toString sw)))