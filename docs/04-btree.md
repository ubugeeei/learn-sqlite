# 4. B-trees

SQLite stores tables and indexes in B-trees; see [B-tree pages][btree]. A leaf
contains ordered key/value cells. When it fills, split around the median and
insert a separator into its parent. Interior nodes contain separators and child
page numbers.

The implementation starts with a table B-tree keyed by a monotonically assigned
64-bit row id. Property-oriented tests insert keys in awkward orders, reopen the
file, and assert that scans remain sorted.

[btree]: https://www.sqlite.org/fileformat.html#b_tree_pages

