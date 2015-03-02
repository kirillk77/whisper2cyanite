# whisper2cyanite

[Whisper](https://github.com/graphite-project/whisper) to
[Cyanite](https://github.com/pyr/cyanite) data migration tool

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

## Usage scenarios

### Migrating a database

#### Migrating a whole database

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

```bash
whisper2cyanite list-files /var/lib/whisper/requests/nginx > path-files.lst
```

```bash
whisper2cyanite --run --jobs 8 --disable-metric-store --root-dir \
  /var/lib/whisper/ migrate path-files.lst 'my_tenant' cass1.example.org \
  http://es.example.org:9200
```

#### Migrating metrics for a predetermined period and a single rollup from a
database file

```bash
whisper2cyanite --run --jobs 8 --rollups 60:5356800 --disable-path-store \
  --root-dir /var/lib/whisper/ --from 1420070400 --to 1421280000 migrate \
  /var/lib/whisper/requests/nginx/access.wsp 'my_tenant' \
  cass1.example.org,cass2.example.org http://es.example.org:9200
```

### Validating

#### Validating a whole database

```bash
whisper2cyanite --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 \
  --cassandra-options "{:compression :lz4}" --errors-file error-files.lst \
  validate /var/lib/whisper/ 'my_tenant' cass1.example.org,cass2.example.org \
  http://es.example.org:9200
```

## License

whisper2cyanite is covered by [MIT License](http://opensource.org/licenses/MIT)

## Thanks

Thanks to [Pierre-Yves Ritschard](https://github.com/pyr) AKA @pyr for his work
on [Cyanite](https://github.com/pyr/cyanite)
