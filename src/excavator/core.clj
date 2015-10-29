(ns excavator.core
  (:require [excavator.excavator :as excavator]
            [excavator.aleph-netty :as aleph-netty]
            [excavator.web-socket-client :as ws-client])
  (:gen-class))

(defn -main
  "Starts excavator"
  [& args]

  (aleph-netty/start-server)

  (ws-client/init)

  (excavator/init)
  (println "Hello, excavator"))

