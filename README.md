# ![Logo](https://i.imgur.com/zdlOSZD.png?1) Onyx

**A masterless, fault-tolerant, high-performance distributed computation system for Clojure.**

This is a community-maintained fork of [onyx-platform/onyx](https://github.com/onyx-platform/onyx) (now archived), updated to run on modern JDKs.

[![Clojars](https://img.shields.io/clojars/v/com.github.clojure-finance/onyx.svg)](https://clojars.org/com.github.clojure-finance/onyx)

## Installation

Add to your `project.clj`:

```clojure
[com.github.clojure-finance/onyx "0.16.0"]
```

Clojars also lists 0.1.0 and 0.2.0 from the fork's earlier version scheme. 0.2.0 has the same content as 0.15.0; prefer 0.15.0 or later.

## JVM Requirements

**JDK 17 or newer** is required (tested on JDK 21). Onyx uses Aeron 1.53.x for messaging, and the Agrona library bundled with it reads `jdk.internal.misc.Unsafe`, so the JVM that starts peers needs this flag:

```
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
```

In Leiningen:

```clojure
:jvm-opts ["--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"]
```

Without this flag, the embedded media driver fails with:

```
IllegalAccessError: class org.agrona.UnsafeApi ... cannot access class jdk.internal.misc.Unsafe
```

On JDK 24 and newer, also pass `--enable-native-access=ALL-UNNAMED`. Otherwise the JVM warns that nippy's LZ4 compression calls a restricted method (`System.load`) that will be blocked in a future release. The flag exists since JDK 17, so it is safe to add unconditionally.

## What is Onyx?

Onyx is a distributed computation system written in pure Clojure. It features:

- **Masterless architecture** — no single point of failure
- **Cloud-scale** fault tolerance and high performance
- **Hybrid batch/stream** processing model
- **Information model** for describing and constructing distributed workflows

It occupies the same space as Storm, Flink, Spark and similar systems.

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
- ZooKeeper now logs via slf4j. Onyx ships `slf4j-nop`, so add your own binding to see its logs.

**0.15.0**
- Aeron upgraded from 1.21.2 to 1.53.1
- JDK 17+ required (the previous `sun.nio.ch` and `java.lang` opens are no longer needed)
- The coordination log version is now `0.15.0`. It had stayed at `0.14.6-SNAPSHOT`, which silently skipped the cluster version compatibility check, so peers of different Onyx versions now refuse to share a tenancy. Use a new `:onyx/tenancy-id` when upgrading.

**Earlier fork work (2023, before 0.15.0)**
- Clojure 1.11, core.async 1.6.673, ZooKeeper 3.4.12 → 3.5.5, Curator 4.0.1 → 5.5.0
- `onyx.static.uuid/random-uuid` renamed to `onyx-random-uuid` to avoid clashing with `clojure.core/random-uuid` (Clojure 1.11)

See [changes.md](changes.md) for the full changelog including upstream history.

## Documentation

> **Note:** The upstream project is archived, so some external links may be outdated.

- [User Guide](https://www.onyxplatform.org/docs) — comprehensive documentation (sources in [`doc/user-guide`](doc/user-guide/index.adoc))
- API reference — generated HTML is in [`doc/api`](doc/api); the public API lives in [`src/onyx/api.clj`](src/onyx/api.clj)
- Data model reference — every job, catalog, lifecycle and peer-config key is described in [`src/onyx/information_model.cljc`](src/onyx/information_model.cljc)
- [Onyx Starter](https://github.com/onyx-platform/onyx-starter) — quick start project with [walkthrough](https://github.com/onyx-platform/onyx-starter/blob/master/WALKTHROUGH.md)
- [Onyx Template](https://github.com/onyx-platform/onyx-template) — Leiningen application template

## Plugins

Built into Onyx core:

- [`onyx-core-async`](doc/user-guide/core-async-plugin.adoc)
- [`onyx-seq`](doc/user-guide/seq-plugin.adoc)

Official plugins from upstream (may require updates for this fork):

- [`onyx-kafka`](https://github.com/onyx-platform/onyx-kafka)
- [`onyx-datomic`](https://github.com/onyx-platform/onyx-datomic)
- [`onyx-redis`](https://github.com/onyx-platform/onyx-redis)
- [`onyx-sql`](https://github.com/onyx-platform/onyx-sql)
- [`onyx-amazon-sqs`](https://github.com/onyx-platform/onyx-amazon-sqs)
- [`onyx-amazon-s3`](https://github.com/onyx-platform/onyx-amazon-s3)
- [`onyx-http`](https://github.com/onyx-platform/onyx-http)

Plugin template for building your own: [`onyx-plugin`](https://github.com/onyx-platform/onyx-plugin)

## Building from Source

Install into your local Maven repository:

```bash
lein install
```

Run the test suite:

```bash
lein test
```

The default selector skips tests tagged `^:broken` or `^:stress`. Run namespaces one at a time if you encounter issues — embedded ZooKeeper and the Aeron directory under `/dev/shm` can collide across parallel runs.

## Contributors

Onyx was created by [Michael Drogalis](https://github.com/MichaelDrogalis) and
developed by the [upstream contributors](https://github.com/onyx-platform/onyx/graphs/contributors).

## Acknowledgements

Some code incorporated from:
- [Riemann](https://github.com/aphyr/riemann)
- [zookeeper-clj](https://github.com/liebke/zookeeper-clj)

## License

Copyright © 2017 Michael Drogalis

Distributed under the Eclipse Public License, the same as Clojure.
