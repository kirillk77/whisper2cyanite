# whisper2cyanite

whisper2cyanite is a tool for migrating data from
[Whisper](https://github.com/graphite-project/whisper) to
[Cyanite](https://github.com/pyr/cyanite).

[![Build Status](https://travis-ci.org/cybem/whisper2cyanite.svg?branch=master)](https://travis-ci.org/cybem/whisper2cyanite)

## Building

### Dependencies

whisper2cyanite is a [Clojure](http://clojure.org/) application and uses
[Leiningen](http://leiningen.org/) as build tool. Building whisper2cyanite
needs a working Leiningen installation, as well as JDK.

### Building a standalone JAR-file

```bash
lein uberjar
```

Built JAR-file will be placed in the `target/uberjar` directory. You can launch
the tool by running `./whisper2cyanite` command.

### Building a deb-package

Building whisper2cyanite deb-package needs installed `dpkg-dev` and `fakeroot`
packages.

```bash
lein fatdeb
```

Built package will be placed in the `target` directory.

## Usage

### Commands

* `migrate`
  Migrate


* `validate`
  Validate


* `list-files`
  List files.


* `list-paths`
  List paths.


* `info`
  Show database info.


* `fetch`
  Fetch metrics.


* `help`
  Show help.

### Options


## Usage scenarios

### Migrating a database

#### Migrating a whole database

Typical command for migrating a whole Whisper database to Cyanite looks like
this:

```bash
whisper2cyanite --run --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 \
  --cassandra-options "{:compression :lz4}" --errors-file error-files.lst \
  migrate /var/lib/whisper/ 'my_tenant' cass1.example.org,cass2.example.org \
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

#### Retrying to migrate after non-fatal errors occurred

```bash
whisper2cyanite --run --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 \
  --cassandra-options "{:compression :lz4}" --root-dir /var/lib/whisper/ \
  migrate error-files.lst 'my_tenant' cass1.example.org,cass2.example.org \
  http://es.example.org:9200
```

This command means same as above but:
* Set database root to the `/var/lib/whisper/` directory
* Read files to migrate from the `errorfiles.lst` file

#### Migrating paths from a database subtree

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
  /var/lib/whisper/ migrate path-files.lst 'my_tenant' cass1.example.org \
  http://es.example.org:9200
```

#### Migrating metrics for a predetermined period and a single rollup from a database file

Migrating metrics from the `/var/lib/whisper/requests/nginx/access.wsp` file
for period from `1420070400` (01 Jan 2015 00:00:00) until `1421280000` (15 Jan
2015 00:00:00). All rollups except `60` are ignored. Path store operations are
disabled.

```bash
whisper2cyanite --run --rollups 60:5356800 --disable-path-store \
  --root-dir /var/lib/whisper/ --from 1420070400 --to 1421280000 migrate \
  /var/lib/whisper/requests/nginx/access.wsp 'my_tenant' \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

### Validating

Using the command `validate` is very similar to using the command `migrate`.

#### Validating a whole database

Typical command for validating a whole database:

```bash
whisper2cyanite --jobs 8 --cassandra-options "{:compression :lz4}" \
  --errors-file error-files.lst validate /var/lib/whisper/ 'my_tenant' \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

#### Validating paths from a database subtree

Validating paths from the `/var/lib/whisper/requests/nginx` directory (the
`requests.nginx.*` path store subtree):

```bash
whisper2cyanite --jobs 8 --disable-metric-store --root-dir /var/lib/whisper/ \
  validate /var/lib/whisper/requests/nginx 'my_tenant' cass1.example.org \
  http://es.example.org:9200
```

#### Validating metrics of a single path

Validating metrics from the `/var/lib/whisper/requests/nginx/access.wsp` file:

```bash
whisper2cyanite --disable-path-store --root-dir /var/lib/whisper/ migrate \
  /var/lib/whisper/requests/nginx/access.wsp 'my_tenant' \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

## License

whisper2cyanite is covered by [MIT License](http://opensource.org/licenses/MIT)

## Thanks

Thanks to [Pierre-Yves Ritschard](https://github.com/pyr) aka @pyr for his work
on [Cyanite](https://github.com/pyr/cyanite)
