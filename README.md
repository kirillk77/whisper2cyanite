# whisper2cyanite
Whisper to Cyanite data migration tool

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
whisper2cyanite --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 \
--cassandra-options "{:compression :lz4}" --errors-file errorfiles.lst \
migrate /var/lib/graphite/storage/whisper/ 'mytenant' \
cassandra1.example.org,cassandra2.example.org http://es.example.org:9200
```

This command means:
* Use 8 jobs
* Define two rollups: 1m rollup with 62d retention time and 15m rollup with
  720d retention time
* Set minimum TTL to 18000s (5h)
* Set Cassandra options: use compression algorithm LZ4
* Dump a list of files during processing which errors occurred to the
  `errorfiles.lst`
* Migrate the Whisper database placed in the
  `/var/lib/graphite/storage/whisper/`. Database root will be set to this
  directory automatically.
* Set the tenant to `mytenant`
* Use two Cassandra nodes: `cassandra1.example.org` and
  `cassandra2.example.org`
* Use the Elasticsearch node on `http://es.example.org:9200`

### Retrying to migrate after non-fatal errors occurred

```bash
whisper2cyanite --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 --cassandra-options "{:compression :lz4}" --root-dir /var/lib/graphite/storage/whisper/ migrate errorfiles.lst 'mytenant' cassandra1.example.org,cassandra2.example.org http://es.example.org:9200
```

This command means same as above but:
* Set database root to the `/var/lib/graphite/storage/whisper/` directory
* Read files to migrate from the `errorfiles.lst` file

### Migrating paths only

```bash
whisper2cyanite --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 --cassandra-options "{:compression :lz4}" --root-dir /var/lib/graphite/storage/whisper/ migrate errorfiles.lst 'mytenant' cassandra1.example.org,cassandra2.example.org http://es.example.org:9200
```


```bash
whisper2cyanite --jobs 8 --rollups 60:5356800,900:6220800 --min-ttl 18000 --cassandra-options "{:compression :lz4}" --root-dir /var/lib/graphite/storage/whisper/ migrate errorfiles.lst 'mytenant' cassandra1.example.org,cassandra2.example.org http://es.example.org:9200
```

## License

whisper2cyanite is covered by [MIT License](http://opensource.org/licenses/MIT)

## Thanks

Thanks to [Pierre-Yves Ritschard](https://github.com/pyr) AKA @pyr for his work
on [Cyanite](https://github.com/pyr/cyanite)
