# learn-sqlite

A small, readable SQL database written in Scala 3. The project reconstructs
SQLite's core ideas rather than embedding or wrapping SQLite. It is both an
executable implementation and a practical book for people who want to build a
database from first principles.

## Run

```sh
scala-cli test .
scala-cli run . -- repl ./example.db
```

The evolving implementation guide starts at [`docs/README.md`](docs/README.md).

## Compatibility

The SQL surface deliberately starts small and grows chapter by chapter. The
implementation is inspired by SQLite's documented file format and language,
but files produced by this project are not yet SQLite-compatible. See
[`docs/compatibility.md`](docs/compatibility.md) for the exact support matrix.

