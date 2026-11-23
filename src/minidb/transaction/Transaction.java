package minidb.transaction;

import minidb.index.Index;

import java.util.ArrayList;
import java.util.List;

/**
 * One atomic unit of work. Writes follow the WAL protocol: log the change
 * (with before/after images) FIRST, then apply it. rollback() undoes the
 * changes in reverse order and logs each undo as a compensation record.
 */
public class Transaction {
    private final long txnId;
    private final WALManager wal;
    private boolean active = true;

    private static class Undo {
        final Index index;
        final String table;
        final long key;
        final byte[] before;

        Undo(Index index, String table, long key, byte[] before) {
            this.index = index;
            this.table = table;
            this.key = key;
            this.before = before;
        }
    }

    private final List<Undo> undoLog = new ArrayList<>();

    Transaction(long txnId, WALManager wal) {
        this.txnId = txnId;
        this.wal = wal;
    }

    public long getId() {
        return txnId;
    }

    public boolean isActive() {
        return active;
    }

    public int writeCount() {
        return undoLog.size();
    }

    public void put(Index index, String table, long key, byte[] value) throws Exception {
        requireActive();
        byte[] before = index.get(key);
        wal.append(new LogRecord(LogRecord.Type.PUT, txnId, table, key, before, value));
        index.put(key, value);
        undoLog.add(new Undo(index, table, key, before));
    }

    public void delete(Index index, String table, long key) throws Exception {
        requireActive();
        byte[] before = index.get(key);
        wal.append(new LogRecord(LogRecord.Type.DELETE, txnId, table, key, before, null));
        index.delete(key);
        undoLog.add(new Undo(index, table, key, before));
    }

    /** Durable once this returns: the COMMIT record is fsync'd. */
    public void commit() throws Exception {
        requireActive();
        wal.append(LogRecord.commit(txnId));
        wal.fsync();
        undoLog.clear();
        active = false;
    }

    public void rollback() throws Exception {
        requireActive();
        for (int i = undoLog.size() - 1; i >= 0; i--) {
            Undo u = undoLog.get(i);
            if (u.before == null) {
                wal.append(new LogRecord(LogRecord.Type.CLR_DELETE, txnId, u.table, u.key, null, null));
                u.index.delete(u.key);
            } else {
                wal.append(new LogRecord(LogRecord.Type.CLR_PUT, txnId, u.table, u.key, null, u.before));
                u.index.put(u.key, u.before);
            }
        }
        wal.append(LogRecord.rollback(txnId));
        wal.fsync();
        undoLog.clear();
        active = false;
    }

    private void requireActive() {
        if (!active) throw new IllegalStateException("Transaction " + txnId + " is no longer active");
    }
}
