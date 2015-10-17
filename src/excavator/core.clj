(ns excavator.core
  (:require [excavator.excavator :as excavator]
            [excavator.aleph-netty :as aleph-netty])
  (:gen-class))

(defn -main
  "Starts excavator"
  [& args]

  (aleph-netty/start-server)

  (excavator/init)
  (println "Hello, excavator"))

