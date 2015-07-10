# whisper2cyanite

[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://raw.githubusercontent.com/cybem/whisper2cyanite/master/LICENSE)
[![Build Status](https://travis-ci.org/cybem/whisper2cyanite.svg?branch=master)](https://travis-ci.org/cybem/whisper2cyanite)
[![Dependencies Status](http://jarkeeper.com/cybem/whisper2cyanite/status.svg)](http://jarkeeper.com/cybem/whisper2cyanite)

whisper2cyanite is a tool for migrating data from
[Whisper](https://github.com/graphite-project/whisper) to
[Cyanite](https://github.com/pyr/cyanite).

## Table of Contents

* [Building](#building)
  * [Dependencies](#dependencies)
  * [Building a Standalone JAR-file](#building-a-standalone-jar-file)
  * [Building a Deb-package](#building-a-deb-package)
* [Usage](#usage)
  * [Quick Help](#quick-help)
  * [Commands](#commands)
  * [Arguments](#arguments)
  * [Options](#options)
* [Usage Scenarios](#usage-scenarios)
  * [Inspecting](#inspecting)
    * [Listing Paths from a Whisper Database](#listing-paths-from-a-whisper-database)
    * [Estimating Cassandra Data Size from a Whisper Database](#estimating-cassandra-data-size-from-a-whisper-database)
    * [Getting Information About a Whisper Database File](#getting-information-about-a-whisper-database-file)
    * [Fetching Timeseries from a Whisper Database File for a Predetermined Period](#fetching-timeseries-from-a-whisper-database-file-for-a-predetermined-period)
  * [Migrating](#migrating)
    * [Migrating a Whole Database](#migrating-a-whole-database)
    * [Retrying to Migrate After Non-fatal Errors Occurred](#retrying-to-migrate-after-non-fatal-errors-occurred)
    * [Migrating Paths Taken from a Database Subtree](#migrating-paths-taken-from-a-database-subtree)
    * [Migrating Metrics for a Predetermined Period and a Single Rollup from a Database File](#migrating-metrics-for-a-predetermined-period-and-a-single-rollup-from-a-database-file)
  * [Validating](#validating)
    * [Validating a Whole Database](#validating-a-whole-database)
    * [Validating Paths Taken from a Database Subtree](#validating-paths-taken-from-a-database-subtree)
    * [Validating Metrics of a Single Path](#validating-metrics-of-a-single-path)
* [License](#license)
* [Thanks](#thanks)

## Building

### Dependencies

whisper2cyanite is a [Clojure](http://clojure.org/) application and uses
[Leiningen](http://leiningen.org/) as build tool. Building whisper2cyanite
needs a working Leiningen installation, as well as JDK.

### Building a Standalone JAR-file

```bash
lein uberjar
```

Built JAR-file will be placed in the `target/uberjar` directory. You can launch
the tool by running `./whisper2cyanite` command.

### Building a Deb-package

Building whisper2cyanite deb-package needs installed `dpkg-dev` and `fakeroot`
packages.

```bash
lein fatdeb
```

Built package will be placed in the `target` directory.

## Usage

### Quick Help

    whisper2cyanite [options] migrate <directory | whisper_file | filelist_file> <tenant> <cassandra_host,...> <elasticsearch_url>
    whisper2cyanite [options] validate <directory | whisper_file | filelist_file> <tenant> <cassandra_host,...> <elasticsearch_url>
    whisper2cyanite [options] calc-size <directory | whisper_file | filelist_file> <tenant>
    whisper2cyanite list-files <directory>
    whisper2cyanite [options] list-paths <directory>
    whisper2cyanite info <whisper_file>
    whisper2cyanite [options] fetch <whisper_file> <rollup>
    whisper2cyanite help

See [commands](#commands), [arguments](#arguments) and [options](#options) for
more details.

### Commands

* [migrate](#migrate)
* [validate](#validate)
* [calc-size](#calc-size)
* [list-files](#list-files)
* [list-paths](#list-paths)
* [info](#info)
* [fetch](#fetch)
* [help](#help)

#### `migrate`

Migrate a Whisper database to a metric store, a path store or both.

Arguments: [`directory`](#directory) or [`whisper_file`](#whisper_file) or
[`filelist_file`](#filelist_file), [`tenant`](#tenant),
[`cassandra_host(s)`](#cassandra_hosts),
[`elasticsearch_url`](#elasticsearch_url).

Available options: [`cassandra-batch-rate`](#cassandra-batch-rate),
[`cassandra-batch-size`](#cassandra-batch-size),
[`cassandra-channel-size`](#cassandra-channel-size),
[`cassandra-keyspace`](#cassandra-keyspace),
[`cassandra-options`](#cassandra-options), [`disable-log`](#disable-log),
[`disable-metric-store`](#disable-metric-store),
[`disable-path-store`](#disable-path-store),
[`disable-progress`](#disable-progress),
[`elasticsearch-channel-size`](#elasticsearch-channel-size),
[`elasticsearch-index`](#elasticsearch-index), [`errors-file`](#errors-file),
[`from`](#from), [`jobs`](#jobs), [`log-file`](#log-file),
[`log-level`](#log-level), [`min-ttl`](#min-ttl), [`rollups`](#rollups),
[`root-dir`](#root-dir), [`run`](#run), [`to`](#to),
[`stop-on-error`](#stop-on-error).

#### `validate`

Validate a metric store, a path store or both, comparing points and paths from
a Whisper database and Cyanite storages.

Arguments: [`directory`](#directory) or [`whisper_file`](#whisper_file) or
[`filelist_file`](#filelist_file), [`tenant`](#tenant),
[`cassandra_host(s)`](#cassandra_hosts),
[`elasticsearch_url`](#elasticsearch_url).

Available options: [`cassandra-keyspace`](#cassandra-keyspace),
[`cassandra-options`](#cassandra-options), [`disable-log`](#disable-log),
[`disable-metric-store`](#disable-metric-store),
[`disable-path-store`](#disable-path-store),
[`disable-progress`](#disable-progress),
[`elasticsearch-index`](#elasticsearch-index), [`errors-file`](#errors-file),
[`from`](#from), [`jobs`](#jobs), [`log-file`](#log-file),
[`log-level`](#log-level), [`min-ttl`](#min-ttl), [`rollups`](#rollups),
[`root-dir`](#root-dir), [`to`](#to), [`stop-on-error`](#stop-on-error).

#### `calc-size`

Calculate Cassandra data size from a Whisper database.

Arguments: [`directory`](#directory) or [`whisper_file`](#whisper_file) or
[`filelist_file`](#filelist_file), [`tenant`](#tenant).

Available options: [`disable-progress`](#disable-progress), [`jobs`](#jobs),
[`rollups`](#rollups), [`root-dir`](#root-dir).

#### `list-files`

List Whisper database files taken from a `source`.

Arguments: [`directory`](#directory) or [`whisper_file`](#whisper_file) or
[`filelist_file`](#filelist_file).

#### `list-paths`

List paths taken from a `source`. The `root-dir` option can be passed to have
correct paths being computed from filenames.

Arguments: [`directory`](#directory) or [`whisper_file`](#whisper_file) or
[`filelist_file`](#filelist_file).

Available options: [`root-dir`](#root-dir).

#### `info`

Arguments: [`whisper_file`](#whisper_file).

Show Whisper database file information.

#### `fetch`

Fetch timeseries from a Whisper database file.

Arguments: [`whisper_file`](#whisper_file).

Available options: [`from`](#from),
[`to`](#to).

#### `help`

Show help.

### Arguments

* [directory](#directory)
* [whisper_file](#whisper_file)
* [filelist_file](#filelist_file)
* [tenant](#tenant)
* [cassandra_host(s)](#cassandra_hosts)
* [elasticsearch_url](#elasticsearch_url)
* [rollup](#rollup)

#### `directory`

A directory where Whisper database files are located.

#### `whisper_file`

A Whisper database file (`.wsp`).

#### `filelist_file`

A plain text file with a list of Whisper database files (`.wsp`). One file per
line.

#### `tenant`

A tenant name.

#### `cassandra_host(s)`

A comma-separated list of Cassandra hosts.

Example: `cass1.example.org,cass2.example.org`

#### `elasticsearch_url`

An Elasticsearch REST service URL.

Example: `http://es.example.org:9200`

#### `rollup`

A rollup (seconds per data point).

Example: `60`

### Options

Options in alphabet order:

* [cassandra-batch-rate](#cassandra-batch-rate)
* [cassandra-batch-size](#cassandra-batch-size)
* [cassandra-channel-size](#cassandra-channel-size)
* [cassandra-keyspace](#cassandra-keyspace)
* [cassandra-options](#cassandra-options)
* [disable-metric-store](#disable-metric-store)
* [disable-path-store](#disable-path-store)
* [disable-progress](#disable-progress)
* [elasticsearch-channel-size](#elasticsearch-channel-size)
* [elasticsearch-index](#elasticsearch-index)
* [errors-file](#errors-file)
* [from](#from)
* [help](#help)
* [jobs](#jobs)
* [log-file](#log-file)
* [log-level](#log-level)
* [min-ttl](#min-ttl)
* [rollups](#rollups)
* [root-dir](#root-dir)
* [run](#run)
* [stop-on-error](#stop-on-error)
* [to](#to)

#### `cassandra-batch-rate`

`--cassandra-batch-rate` `RATE`

Set the Cassandra batch rate (batches per second, 1-100).

Throttling is not used by default.

#### `cassandra-batch-size`

`--cassandra-batch-size` `SIZE`

Set the Cassandra batch size in points.

Default: `1000`

#### `cassandra-channel-size`

`--cassandra-channel-size` `SIZE`

Set the Cassandra channel size in points.

Default: `500000`

#### `cassandra-keyspace`

`--cassandra-keyspace` `KEYSPACE`

Set the Cassandra keyspace.

Default: `metric`

#### `cassandra-options`

`-O`, `--cassandra-options` `OPTIONS`

Set Cassandra options. See
[Alia documentation](https://mpenet.github.io/alia/qbits.alia.html#var-cluster)
for more details.

Example: `"{:compression :lz4}"`

#### `disable-metric-store`

`--disable-metric-store`

Disable writing to the metric store.

#### `disable-path-store`

`--disable-path-store`

Disable writing to the path store.

#### `disable-progress`

`-P`, `--disable-progress`

Disable the progress bar.

#### `elasticsearch-channel-size`

`--elasticsearch-channel-size` `SIZE`

Set the Elasticsearch channel size.

Default: `10000`

#### `elasticsearch-index`

`--elasticsearch-index` `INDEX`

Set the Elasticsearch index.

Default: `cyanite_paths`

#### `errors-file`

`-e`, `--errors-file` `FILE`

Dump a list of files during processing which the errors occurred.

#### `from`

`-f`, `--from` `FROM`

Set from time in the
[Unix (POSIX, epoch) time](https://en.wikipedia.org/wiki/Unix_time) format.

Example: `1420070400`

#### `jobs`

`-j`, `--jobs` `JOBS`

Set the number of jobs to run simultaneously.

#### `log-file`

`-l`, `--log-file` `FILE`

Set the log file.

Default: `whisper2cyanite.log`

#### `log-level`

`-L`, `--log-level` `LEVEL`

Set the Log level.

Available log levels: `all`, `trace`, `debug`, `info`, `warn`, `error`,
`fatal`, `off`.

Default: `info`

#### `min-ttl`

`-T`, `--min-ttl` `TTL`

Set the minimal TTL. Points having calculated TTL values below the minimal TTL
will not be migrated. It is useful to reduce the number of migrated points.

Default: `3600`

#### `rollups`

`-R`, `--rollups` `ROLLUPS`

Define rollups.

Format: `<seconds_per_point[:retention],...>`

Example: `60,300:31536000`

#### `root-dir`

`-D`, `--root-dir` `DIRECTORY`

Set the root directory.

#### `run`

`-r`, `--run`

Force a normal run. **Dry run using on default.**

#### `to`

`-t`, `--to` `TO`

Set until time in the
[Unix (POSIX, epoch) time](https://en.wikipedia.org/wiki/Unix_time) format.

Example: `1421280000`

#### `stop-on-error`

`-S`, `--stop-on-error`

Stop on the first non-fatal error.

## Usage Scenarios

### Inspecting

#### Listing Paths from a Whisper Database

Listing paths taken from the `/var/lib/whisper/` directory in alphabet order:

```bash
whisper2cyanite list-paths /var/lib/whisper/
```

See command [`list-paths`](#list-paths) for more details.

#### Estimating Cassandra Data Size from a Whisper Database

Estimating Cassandra data size from a Whisper database located in the
`/var/lib/whisper/` directory:

```bash
whisper2cyanite calc-size --jobs 8 /var/lib/whisper/ my_tenant
```

Using 8 jobs to speed up the process. Tenant is named as `my_tenant`.

See command [`calc-size`](#calc-size) for more details.

#### Getting Information About a Whisper Database File

Getting information about the `/var/lib/whisper/requests/nginx/access.wsp`
Whisper database file

```bash
whisper2cyanite info /var/lib/whisper/requests/nginx/access.wsp
```

Sample output:

```
Aggregation method: average
Max retention:      31536000
X files factor:     0.5
Archives:
  Archive 0:
    Seconds per point: 60
    Retention:         5356800
    Points:            89280
    Offset:            40
    Size:              1071360
  Archive 1:
    Seconds per point: 900
    Retention:         31536000
    Points:            35040
    Offset:            1071400
    Size:              420480
```

See command [`info`](#info) for more details.

#### Fetching Timeseries from a Whisper Database File for a Predetermined Period

Fetch timeseries between `1428407040` (07 Apr 2015 11:44:00 GMT) and
`1433757900` (08 Jun 2015 10:05:00 GMT) from rollup `60` of the
`/var/lib/whisper/requests/nginx/access.wsp` Whisper database file:

```bash
whisper2cyanite --from 1428407040 --to 1433757900 fetch \
  /var/lib/whisper/requests/nginx/access.wsp 60
```

See command [`fetch`](#fetch) for more details.

### Migrating

#### Migrating a Whole Database

Typical command for migrating a whole Whisper database to Cyanite looks like
this:

```bash
whisper2cyanite --run --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 \
  --cassandra-options "{:compression :lz4}" --errors-file error-files.lst \
  migrate /var/lib/whisper/ my_tenant cass1.example.org,cass2.example.org \
  http://es.example.org:9200
```

This command means:
* Run in the normal mode, not in the dry mode which using on default
* Use 8 jobs
* Define two rollups: 1m rollup with 62d retention time and 15m rollup with
  720d retention time
* Set minimum TTL to 18000s (5h)
* Set Cassandra options: use compression algorithm LZ4
* Dump a list of files during processing which errors occurred to the
  `error-files.lst`
* Migrate the Whisper database placed in the `/var/lib/whisper/`. Database root
  will be set to this directory automatically.
* Set the tenant to `my_tenant`
* Use two Cassandra nodes: `cass1.example.org` and `cass2.example.org`
* Use the Elasticsearch node on `http://es.example.org:9200`

See command [`migrate`](#migrate) for more details.

#### Retrying to Migrate After Non-fatal Errors Occurred

```bash
whisper2cyanite --run --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 \
  --cassandra-options "{:compression :lz4}" --root-dir /var/lib/whisper/ \
  migrate error-files.lst my_tenant cass1.example.org,cass2.example.org \
  http://es.example.org:9200
```

This command means same as above but:
* Set database root to the `/var/lib/whisper/` directory
* Read files to migrate from the `errorfiles.lst` file

See command [`migrate`](#migrate) for more details.

#### Migrating Paths Taken from a Database Subtree

Dumping names of Whisper database files to the `path-files.lst` file:

```bash
whisper2cyanite list-files /var/lib/whisper/requests/nginx > path-files.lst
```

Reading the `path-files.lst` file and migrating paths taken from
there. According to previous command, only paths from the
`/var/lib/whisper/requests/nginx` directory (the `requests.nginx.*` path store
subtree) will be migrated. Metric store operations are disabled.

```bash
whisper2cyanite --run --jobs 8 --disable-metric-store --root-dir \
  /var/lib/whisper/ migrate path-files.lst my_tenant cass1.example.org \
  http://es.example.org:9200
```

See commands [`list-files`](#list-files) and [`migrate`](#migrate) for more
details.

#### Migrating Metrics for a Predetermined Period and a Single Rollup from a
     Database File

Migrating metrics from the `/var/lib/whisper/requests/nginx/access.wsp` file
for period from `1420070400` (01 Jan 2015 00:00:00) until `1421280000` (15 Jan
2015 00:00:00). All rollups except `60` are ignored. Path store operations are
disabled.

```bash
whisper2cyanite --run --rollups 60:5356800 --disable-path-store \
  --root-dir /var/lib/whisper/ --from 1420070400 --to 1421280000 migrate \
  /var/lib/whisper/requests/nginx/access.wsp my_tenant \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

See command [`migrate`](#migrate) for more details.

### Validating

Using the command `validate` is very similar to using the command `migrate`.

#### Validating a Whole Database

Typical command for validating a whole database:

```bash
whisper2cyanite --jobs 8 --cassandra-options "{:compression :lz4}" \
  --errors-file error-files.lst validate /var/lib/whisper/ my_tenant \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

See command [`validate`](#validate) for more details.

#### Validating Paths Taken from a Database Subtree

Validating paths from the `/var/lib/whisper/requests/nginx` directory (the
`requests.nginx.*` path store subtree):

```bash
whisper2cyanite --jobs 8 --disable-metric-store --root-dir /var/lib/whisper/ \
  validate /var/lib/whisper/requests/nginx my_tenant cass1.example.org \
  http://es.example.org:9200
```

See command [`validate`](#validate) for more details.

#### Validating Metrics of a Single Path

Validating metrics from the `/var/lib/whisper/requests/nginx/access.wsp` file:

```bash
whisper2cyanite --disable-path-store --root-dir /var/lib/whisper/ validate \
  /var/lib/whisper/requests/nginx/access.wsp my_tenant \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

See command [`validate`](#validate) for more details.

## License

whisper2cyanite is covered by [MIT License](https://raw.githubusercontent.com/cybem/whisper2cyanite/master/LICENSE)

## Thanks

Thanks to [Pierre-Yves Ritschard](https://github.com/pyr) aka @pyr for his work
on [Cyanite](https://github.com/pyr/cyanite)
