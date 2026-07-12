# 7. Varints and SQLite record serialization

We have logical `Value`s and a B+tree that accepts byte payloads. The missing
bridge is a record codec. This chapter builds SQLite's actual record encoding,
including its unusual nine-byte variable-length integer.

Read [Record Format][record] and [Varint][varint] first. Keep the specification
open: the serial type table is compact enough to implement directly, but small
off-by-one mistakes produce valid-looking corrupt files.

## 7.1 Why records have headers

A row contains values of different widths. Fixed slots waste space, while a
payload without types cannot be decoded. SQLite divides a record into:

```text
┌──────────────────────── header size (varint)
│  ┌───────────────────── serial type for column 0
│  │  ┌────────────────── serial type for column 1
│  │  │      ┌─────────── payload for column 0
│  │  │      │    ┌────── payload for column 1
▼  ▼  ▼      ▼    ▼
[03 01 13] [2a] [48 69]
```

The header size includes its own varint. This is self-referential: making the
header larger can make the header-size varint larger. Compute a fixed point:

```scala
var size = serialBytes + 1
var next = serialBytes + Varint.size(size)
while next != size do
  size = next
  next = serialBytes + Varint.size(size)
```

The loop converges immediately or after crossing a varint boundary.

## 7.2 Implement the varint first

For bytes one through eight, the high bit means “another byte follows” and the
remaining seven bits contain data. The ninth byte contains eight data bits. So
the maximum width is 8 × 7 + 8 = 64 bits.

```text
value       bytes
0           00
127         7f
128         81 00
16383       ff 7f
2^56 - 1    ff ff ff ff ff ff ff 7f
2^56        80 c0 80 80 80 80 80 80 00
```

Do not use a common LEB128 library: LEB128 is little-endian by groups, while
SQLite varints are big-endian. The ninth-byte rule is also SQLite-specific.

The implementation stores unsigned bit patterns in a Scala `Long`. A negative
`Long` therefore represents a value whose unsigned bit 63 is set. Unsigned
arithmetic is unnecessary because encoding uses unsigned shifts (`>>>`).

Run just the boundary tests:

```sh
scala-cli test . --test-only learnsqlite.storage.VarintSuite
```

Exercises:

1. Write the expected bytes for 16,384 before looking at the test output.
2. Explain why `Long.MaxValue` and `-1L` both require nine bytes.
3. Truncate each encoded boundary value one byte at a time and assert failure.

## 7.3 Serial type codes

The serial type determines both logical class and payload width:

| Code | Meaning | Payload bytes |
|---:|---|---:|
| 0 | NULL | 0 |
| 1 | signed integer | 1 |
| 2 | signed integer | 2 |
| 3 | signed integer | 3 |
| 4 | signed integer | 4 |
| 5 | signed integer | 6 |
| 6 | signed integer | 8 |
| 7 | IEEE 754 binary64 | 8 |
| 8 | integer 0 | 0 |
| 9 | integer 1 | 0 |
| 10–11 | reserved | — |
| even N ≥ 12 | BLOB | `(N - 12) / 2` |
| odd N ≥ 13 | text | `(N - 13) / 2` |

Widths three and six are awkward on the JVM. Decode them byte by byte with sign
extension instead of pretending `ByteBuffer` has `getInt24` or `getInt48`.

Zero and one deserve special serial types because they are common in boolean
columns and cost no payload bytes. The test asserts that `[0, 1]` occupies only
three bytes: header size, two serial types, no body.

## 7.4 Signed integers

Choose the smallest width whose two's-complement range contains the value. The
boundaries are inclusive:

```text
1 byte:  -128 .. 127
2 bytes: -32768 .. 32767
3 bytes: -8388608 .. 8388607
4 bytes: Int.MinValue .. Int.MaxValue
6 bytes: -2^47 .. 2^47 - 1
8 bytes: everything else
```

Big-endian output follows the file format. On decode, initialize the accumulator
to `-1L` when the first byte's sign bit is set, otherwise `0L`, then shift and
append each byte. That preserves sign extension for nonstandard widths.

## 7.5 Text and blobs

Text is UTF-8 in this milestone. SQLite records themselves do not choose an
encoding; the database header does. When exact file compatibility arrives, the
header's encoding field will select UTF-8, UTF-16LE, or UTF-16BE.

Use a strict decoder. `new String(bytes, UTF_8)` replaces malformed sequences,
which silently converts corruption into data. `CharsetDecoder.decode` reports
malformed input instead.

BLOBs are defensively copied both while entering `Value` and when encoded. Test
this explicitly: mutate the caller's array after insertion and verify stored
bytes do not change.

## 7.6 Treat bytes as hostile

A decoder should reject, never guess:

- truncated varints;
- a header size smaller than the bytes already consumed;
- a header beyond the record boundary;
- a serial type varint crossing the header boundary;
- reserved serial types 10 and 11;
- payload lengths larger than the remaining bytes;
- invalid UTF-8;
- trailing payload bytes not described by the header.

This discipline becomes essential once page corruption, partial writes, and
fuzz-generated inputs enter the test suite.

## 7.7 Connect the codec to the tree

The codec remains pure:

```scala
val bytes = RecordCodec.encode(row.values)
tree.insert(rowId, bytes)

val row = tree.get(rowId).flatMap:
  case Some(bytes) => RecordCodec.decode(bytes)
  case None        => Right(Vector.empty)
```

Do not move encoding into `TableBTree`. The tree must also store catalog and
index records, and should not depend on relational `Schema` rules.

## Checkpoint

You now have SQLite-compatible record payloads inside a deliberately simpler
page format. The next chapter will create a catalog record, persist each table's
root page, and replace the in-memory table map behind `Database` with a storage
interface. At that point the REPL will survive process restart.

[record]: https://www.sqlite.org/fileformat.html#record_format
[varint]: https://www.sqlite.org/fileformat.html#varint
