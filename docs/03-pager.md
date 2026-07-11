# 3. Pages and durable storage

SQLite divides a database into fixed-size pages. Its exact header and page
layout are specified in the [database file format][format]. Our first pager uses
the same core idea with a deliberately smaller private header:

1. Validate the magic bytes and format version when opening a file.
2. Read and write whole, fixed-size pages.
3. Never expose mutable byte buffers outside the pager boundary.
4. Force committed data to stable storage.

This private format lets the implementation teach page management before taking
on byte-for-byte compatibility. The magic value prevents it from silently
opening an actual SQLite database.

[format]: https://www.sqlite.org/fileformat.html

