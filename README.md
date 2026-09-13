# ![Logo](http://i.imgur.com/zdlOSZD.png?1) Onyx

> A fork of onyx-platform/onyx updated to run with JDK-21

### Installation (locally):
lein install 

### Include in project:
Available on Clojars

Insert this line into your `project.clj` if using Leiningen.

```
[com.github.clojure-finance/onyx "0.15.0"]
```


### JVM requirements

This fork runs on JDK 17 or newer (JDK 21 is what it is tested on). It uses
Aeron 1.53.x, whose bundled Agrona reads `jdk.internal.misc.Unsafe`, so the
JVM that starts Onyx peers needs this flag:

```
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
```

With Leiningen put it in `:jvm-opts`; on the command line pass it to `java`
directly. Without it the embedded media driver fails to start with
`IllegalAccessError: class org.agrona.UnsafeApi ... cannot access class
jdk.internal.misc.Unsafe`. The `sun.nio.ch` and `java.lang` opens that
earlier versions of this fork needed are no longer required.

## Changes made:
Updated dependencies (including Aeron 1.53.1), renamed internal components
and fixed certain tests. See `changes.md` for details per version.


[![Join the chat at https://gitter.im/onyx-platform/onyx](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/onyx-platform/onyx?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

### What is it?

- a masterless, cloud scale, fault tolerant, high performance distributed computation system
- batch and stream hybrid processing model
- exposes an information model for the description and construction of distributed workflows
- Competes against Storm, Flink, Cascading, Cascalog, Spark, Map/Reduce, Sqoop, etc
- written in pure Clojure

### What would I use this for?

- Realtime event stream processing
- CQRS
- Continuous computation
- Extract, transform, load
- Data transformation à la map-reduce
- Data ingestion and storage medium transfer
- Data cleaning

### Changelog

Changelog can be found at [changes.md](changes.md).

### Quick Lookup Doc

A searchable set of documentation for the Onyx data model is [available](http://www.onyxplatform.org/docs/cheat-sheet/latest/).

### Project Template

A project template can be found at [onyx-template](https://github.com/onyx-platform/onyx-template).

### Plugins and Libraries

### Plugin Template

We provide a plugin template for use in building new plugins. This can be found at [onyx-plugin](https://github.com/onyx-platform/onyx-plugin).

#### Plugin Use

To use the supported plugins, please use version coordinates such as
`[org.onyxplatform/onyx-amazon-sqs "0.14.6.SNAPSHOT.0"]`, and read
the READMEs on the 0.14.x branches linked in the plugin listing below.


#### Unsupported plugins

Some plugins are currently unsupported in onyx 0.14.x. These are:

- [`onyx-durable-queue`](https://github.com/onyx-platform/onyx-durable-queue)
- [`onyx-elasticsearch`](https://github.com/onyx-platform/onyx-elasticsearch)
- [`onyx-kafka-0.8`](https://github.com/onyx-platform/onyx-kafka-0.8)

### Companies Running Onyx in Production

[![LockedOn](doc/images/lockedon.png)](https://lockedon.com)
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img src="doc/images/cognician.png" height="30%" width="30%">
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img src="doc/images/indaba.png" height="40%" width="40%">
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img src="doc/images/yapster.png" height="15%" width="15%">
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img src="doc/images/modnakasta.png">
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img src="doc/images/breeze-125.png">

### Quick Start Guide

Feeling impatient? Hit the ground running ASAP with the [onyx-starter repo](https://github.com/onyx-platform/onyx-starter) and [walkthrough](https://github.com/onyx-platform/onyx-starter/blob/master/WALKTHROUGH.md). You can also boot into preloaded a Leiningen [application template](https://github.com/onyx-platform/onyx-template).

### User Guide 0.14.6

- [User Guide Table of Contents](http://www.onyxplatform.org/docs)
- [API docs](http://www.onyxplatform.org/docs/api/latest)
- [Cheat Sheet](http://www.onyxplatform.org/docs/cheat-sheet/latest)

### Developer's Guide 0.14.6

- [Branch Policy](doc/developers-guide/branch-policy.md)
- [Release Checklist](doc/developers-guide/release-checklist.md)

### API Docs 0.14.6

Code level API documentation [can be found here](http://www.onyxplatform.org/docs/api/0.14.6).

### Official plugin listing

Official plugins are vetted by Michael Drogalis. Ensure in your project that plugin versions directly correspond to the same Onyx version (e.g. `onyx-kafka` version `0.14.6.0-SNAPSHOT` goes with `onyx` version `0.14.6`). Fixes to plugins can be applied using a 4th versioning identifier (e.g. `0.14.6.1-SNAPSHOT`).

- [`onyx-core-async`](doc/user-guide/core-async-plugin.adoc)
- [`onyx-kafka`](https://github.com/onyx-platform/onyx-kafka)
- [`onyx-kafka-0.8`](https://github.com/onyx-platform/onyx-kafka-0.8)
- [`onyx-datomic`](https://github.com/onyx-platform/onyx-datomic)
- [`onyx-redis`](https://github.com/onyx-platform/onyx-redis)
- [`onyx-sql`](https://github.com/onyx-platform/onyx-sql)
- [`onyx-bookkeeper`](https://github.com/onyx-platform/onyx-bookkeeper)
- [`onyx-seq`](src/onyx/plugin/seq.cljc)
- [`onyx-durable-queue`](https://github.com/onyx-platform/onyx-durable-queue)
- [`onyx-elasticsearch`](https://github.com/onyx-platform/onyx-elasticsearch)
- [`onyx-http`](https://github.com/onyx-platform/onyx-http)
- [`onyx-amazon-sqs`](https://github.com/onyx-platform/onyx-amazon-sqs)
- [`onyx-amazon-s3`](https://github.com/onyx-platform/onyx-amazon-s3)

Generate plugin templates through Leiningen with [`onyx-plugin`](https://github.com/onyx-platform/onyx-plugin).

### 3rd Party plugin listing

Unofficial plugins have not been vetted.
- [`onyx-rethink`](https://github.com/cddr/onyx-rethink)

### Need help?

Check out the [Onyx Google Group](https://groups.google.com/forum/#!forum/onyx-user).

### Want the logo?

Feel free to use it anywhere. You can find [a few different versions here](https://github.com/onyx-platform/onyx/tree/0.14.x/doc/images/logo).

### Running the tests

A simple `lein test` will run the full suite for Onyx core.

#### Contributor list

- [Michael Drogalis](https://github.com/MichaelDrogalis)
- [Lucas Bradstreet](https://github.com/lbradstreet)
- [Owen Jones](https://github.com/owengalenjones)
- [Bruce Durling](https://github.com/otfrom)
- [Malcolm Sparks](https://github.com/malcolmsparks)
- [Bryce Blanton](https://github.com/bblanton)
- [David Rupp](https://github.com/davidrupp)
- [sbennett33](https://github.com/sbennett33)
- [Tyler van Hensbergen](https://github.com/tvanhens)
- [David Leatherman](https://github.com/leathekd)
- [Daniel Compton](https://github.com/danielcompton)
- [Jeff Rose](https://github.com/rosejn)
- [Ole Krüger](https://github.com/dignati)
- [Juho Teperi](https://github.com/Deraen)
- [Nicolas Ha](https://github.com/nha)
- [Andrew Meredith](https://github.com/kendru)
- [Bridget Hillyer](https://github.com/bridgethillyer)
- [Ivan Mushketyk](https://github.com/mushketyk)
- [Jochen Rau](https://github.com/jocrau)
- [Tienson Qin](https://github.com/tiensonqin)
- [Roman Volosovskyi](https://github.com/rasom)
- [Vijay Kiran](https://github.com/vijaykiran)
- [Paul Kehrer](https://github.com/reaperhulk)
- [Scott Bennett](https://github.com/sbennett33)
- [Nathan Todd.stone](https://github.com/nathants)
- [Mariusz Jachimowicz](https://github.com/mariusz-jachimowicz-83)
- [Jason Bell](https://github.com/jasebell)


#### Acknowledgements

Some code has been incorporated from the following projects:

- [Riemann](https://github.com/aphyr/riemann)
- [zookeeper-clj](https://github.com/liebke/zookeeper-clj)

### License

Copyright © 2017 Michael Drogalis

Distributed under the Eclipse Public License, the same as Clojure.
