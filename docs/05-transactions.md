# 5. Transactions and recovery

Atomicity means a crash exposes either the state before a transaction or the
state after it, never a mixture. SQLite's rollback-journal algorithm is
described in [Atomic Commit In SQLite][atomic].

Our first single-process transaction writes original pages to a sidecar journal,
forces the journal, updates the database, forces it, then removes the journal.
Opening a database with a valid journal restores those original pages before
serving reads. Later milestones can add file locks and WAL mode.

[atomic]: https://www.sqlite.org/atomiccommit.html
