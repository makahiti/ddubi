# Architecture

## Runtime flow

```text
Public APIs → Python Collectors → Kafka ─→ PySpark → Redis
                                         └→ Python Archiver → Parquet → HDFS

Flutter Android/Web → Nginx → Java Backend → MySQL / Redis
                                           └→ Kafka → Java Notification → FCM
```

## Server 1 — Data

Kafka, Collectors, Spark Master/Worker, Archiver, HDFS NameNode/DataNode1,
Kafka/Spark exporters

## Server 2 — Service

Nginx + Flutter Web, Backend, Notification, MySQL, Redis,
HDFS DataNode2, Prometheus, Grafana
