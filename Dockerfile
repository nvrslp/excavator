FROM clojure
RUN mkdir -p /usr/src/app
RUN (mkdir -p /root/.ssh/ && touch /root/.ssh/known_hosts)
WORKDIR /usr/src/app
COPY target/uberjar/excavator-0.1.0-SNAPSHOT-standalone.jar /usr/src/app/
CMD ["java", "-jar", "excavator-0.1.0-SNAPSHOT-standalone.jar"]
EXPOSE 8081
