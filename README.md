# ![Logo](http://i.imgur.com/zdlOSZD.png?1) Onyx

**A masterless, fault-tolerant, high-performance distributed computation system for Clojure.**

This is a community-maintained fork of [onyx-platform/onyx](https://github.com/onyx-platform/onyx) (now archived), updated to run on modern JDKs.

[![Clojars](https://img.shields.io/clojars/v/com.github.clojure-finance/onyx.svg)](https://clojars.org/com.github.clojure-finance/onyx)

## Installation

Add to your `project.clj`:

```clojure
[com.github.clojure-finance/onyx "0.16.0"]
```

Or install locally:

```bash
lein install
```

## JVM Requirements

**JDK 17 or newer** is required (tested on JDK 21). Onyx uses Aeron 1.53.x for messaging, which requires access to internal JVM APIs. Add this flag when starting peers:

```
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
```

In Leiningen, add it to `:jvm-opts`. Without this flag, the embedded media driver fails with:

```
IllegalAccessError: class org.agrona.UnsafeApi ... cannot access class jdk.internal.misc.Unsafe
```

## What is Onyx?

Onyx is a distributed computation system written in pure Clojure. It features:

- **Masterless architecture** — no single point of failure
- **Cloud-scale** fault tolerance and high performance
- **Hybrid batch/stream** processing model
- **Information model** for describing and constructing distributed workflows

It competes with Storm, Flink, Spark, Kafka Streams, and similar systems.

## Use Cases

- Real-time event stream processing
- Continuous computation and CQRS
- Extract, transform, load (ETL) pipelines
- Data transformation (map-reduce style)
- Data ingestion between storage systems
- Data cleaning and normalization

## Changes in This Fork

**0.16.0** (Latest)
- ZooKeeper 3.9.5, Curator 5.9.0, slf4j 1.7.36
- ZooKeeper now logs via slf4j (add your own binding to see its logs)

**0.15.0**
- Aeron upgraded from 1.21.2 to 1.53.1
- JDK 17+ required (previous `sun.nio.ch` and `java.lang` opens no longer needed)
- Coordination log version set properly (was stuck at 0.14.6-SNAPSHOT)

See [changes.md](changes.md) for the full changelog including upstream history.

## Documentation

> **Note:** The upstream project is archived, so some external links may be outdated.

- [User Guide](http://www.onyxplatform.org/docs) — comprehensive documentation
- [API Reference](http://www.onyxplatform.org/docs/api/latest)
- [Cheat Sheet](http://www.onyxplatform.org/docs/cheat-sheet/latest) — searchable data model reference
- [Onyx Starter](https://github.com/onyx-platform/onyx-starter) — quick start template with [walkthrough](https://github.com/onyx-platform/onyx-starter/blob/master/WALKTHROUGH.md)

## Plugins

The core-async plugin is built in:

- [`onyx-core-async`](doc/user-guide/core-async-plugin.adoc)

Official plugins from upstream (may require updates for this fork):

- [`onyx-kafka`](https://github.com/onyx-platform/onyx-kafka)
- [`onyx-datomic`](https://github.com/onyx-platform/onyx-datomic)
- [`onyx-redis`](https://github.com/onyx-platform/onyx-redis)
- [`onyx-sql`](https://github.com/onyx-platform/onyx-sql)
- [`onyx-amazon-sqs`](https://github.com/onyx-platform/onyx-amazon-sqs)
- [`onyx-amazon-s3`](https://github.com/onyx-platform/onyx-amazon-s3)
- [`onyx-http`](https://github.com/onyx-platform/onyx-http)

Plugin template for building your own: [`onyx-plugin`](https://github.com/onyx-platform/onyx-plugin)

## Running Tests

```bash
lein test
```

The test suite includes 84 namespaces with 160 tests. Run namespaces one at a time if you encounter issues — embedded ZooKeeper and the Aeron directory under `/dev/shm` can collide across parallel runs.

## Contributors

Originally created by [Michael Drogalis](https://github.com/MichaelDrogalis) with contributions from:

Lucas Bradstreet, Owen Jones, Bruce Durling, Malcolm Sparks, Bryce Blanton, David Rupp, Tyler van Hensbergen, David Leatherman, Daniel Compton, Jeff Rose, Ole Krüger, Juho Teperi, Nicolas Ha, Andrew Meredith, Bridget Hillyer, Ivan Mushketyk, Jochen Rau, Tienson Qin, Roman Volosovskyi, Vijay Kiran, Paul Kehrer, Scott Bennett, Nathan Todd.stone, Mariusz Jachimowicz, Jason Bell, and others.

## Acknowledgements

Some code incorporated from:
- [Riemann](https://github.com/aphyr/riemann)
- [zookeeper-clj](https://github.com/liebke/zookeeper-clj)

## License

Copyright © 2017 Michael Drogalis

Distributed under the Eclipse Public License, the same as Clojure.
