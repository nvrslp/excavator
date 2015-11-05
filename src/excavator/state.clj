(ns excavator.state
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [clojure.core.async :refer [chan dropping-buffer close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout pipeline pipeline-blocking pipeline-async]]
            [excavator.util :as util]))


(def ws-server (atom nil))

(defonce excavator-uuid (atom nil))

(defn generate-excavator-uuid
  "Generates excavator-uuid, runs at excavator start and generates only once"
  []
  (swap! excavator-uuid (fn [x]
                          (if (nil? x)
                            (util/random-uuid-str)
                            x))))