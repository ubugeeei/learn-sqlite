# 3. Pages and durable storage

> New words: **page**, **page id**, **file offset**, **header**, **magic bytes**, and **flush**.

SQLite divides a database into fixed-size pages. Its exact header and page
layout are specified in the [database file format][format]. Our first pager uses
the same core idea with a deliberately smaller private header:

```mermaid
flowchart LR
  ID["PageId(2)"] --> P["Pager"]
  P --> O["offset = header + 2 × pageSize"]
  O --> F["Read 4096 bytes from file"]
```

The caller asks for page 2; only the pager knows where page 2 begins in the file. This single-owner
rule becomes important when caching and transactions are added later.

1. Validate the magic bytes and format version when opening a file.
2. Read and write whole, fixed-size pages.
3. Never expose mutable byte buffers outside the pager boundary.
4. Force committed data to stable storage.

This private format lets the implementation teach page management before taking
on byte-for-byte compatibility. The magic value prevents it from silently
opening an actual SQLite database.

```text
private database file
┌────────────────┬────────────────┬────────────────┬────────────────┐
│ 16-byte header │ page 0         │ page 1         │ page 2         │
│ magic/version  │ pageSize bytes │ pageSize bytes │ pageSize bytes │
└────────────────┴────────────────┴────────────────┴────────────────┘
```

[format]: https://www.sqlite.org/fileformat.html
