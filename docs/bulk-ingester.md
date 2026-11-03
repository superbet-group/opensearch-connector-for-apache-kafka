# Maintaining the local bulk ingester

`BulkIngester.java`, `FnCondition.java`, `IngesterOperation.java` and
`RetryableBulkOperation.java` come from
[opensearch-java v3.6.0, commit `1e7d5e6e466da0356785c20cc5fb0eea909dd6eb`](https://github.com/opensearch-project/opensearch-java/tree/1e7d5e6e466da0356785c20cc5fb0eea909dd6eb/java-client/src/main/java/org/opensearch/client/opensearch/_helpers/bulk).
The local copy lives in `io.aiven.kafka.connect.opensearch.bulk`.

The mechanical import changes the package, adds the `BulkListener` import needed
after relocation, and applies the connector's Java formatter. We keep the full
upstream interface to make comparisons and updates easier. Public client types
still come from the unchanged `opensearch-java:3.6.0` dependency.

## Upstream attribution

```text
OpenSearch (https://opensearch.org/)
Copyright OpenSearch Contributors

This product includes software developed by
Elasticsearch Java Client
Copyright 2021 Elasticsearch B.V.
```
