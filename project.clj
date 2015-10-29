(defproject excavator "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [manifold "0.1.0"]
                 ;[org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/core.async "0.2.371"]
                 [aleph "0.4.0"]
                 [base64-clj "0.1.1"]
                 [environ "1.0.1"]
                 [org.clojure/core.incubator "0.1.3"]
                 [docker-client-clj "0.1.1-SNAPSHOT"]
                 [com.cognitect/transit-clj "0.8.269"]]
  :main ^:skip-aot excavator.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
