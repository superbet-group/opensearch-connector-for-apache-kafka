# How to run this when testing

 * `kubectl run kafka-connect-test --image=apache/kafka:3.8.0 --command -- sleep infinity`
 * `(from top level) ./gradlew distTar`
 * `kubectl cp . kafka-connect-test:/tmp`
 * `kubectl cp ../build/distributions/opensearch-connector-for-apache-kafka-3.1.1.tar kafka-connect-test:/tmp`
 * `kubectl exec -it kafka-connect-test -- bash"`
   * `cd /tmp`
   * `mkdir plugin`
   * `tar -xvf opensearch-connector-for-apache-kafka-3.1.1.tar -C plugin`
   * `# check and update connector.properties and worker.properties`
   * `/opt/kafka/bin/connect-standalone.sh worker.properties connector.properties`
 
