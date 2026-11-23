package minidb.transaction;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One write-ahead-log entry.
 *
 * PUT / DELETE carry the BEFORE image (for undo) and AFTER image (for redo).
 * CLR_PUT / CLR_DELETE are "compensation log records": the redo-only records
 * written while a rollback undoes a change. Logging the undo is what lets
 * recovery "repeat history" correctly when a rolled-back key is later
 * rewritten by a committed transaction.
 */
public class LogRecord {
    public enum Type { BEGIN, PUT, DELETE, COMMIT, ROLLBACK, CLR_PUT, CLR_DELETE }

    public final Type type;
    public final long txnId;
    public final String tableName;
    public final long key;
    public final byte[] beforeValue;
    public final byte[] afterValue;

    public LogRecord(Type type, long txnId, String tableName, long key, byte[] beforeValue, byte[] afterValue) {
        this.type = type;
        this.txnId = txnId;
        this.tableName = tableName;
        this.key = key;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
    }

    public static LogRecord begin(long txnId) {
        return new LogRecord(Type.BEGIN, txnId, null, 0, null, null);
    }

    public static LogRecord commit(long txnId) {
        return new LogRecord(Type.COMMIT, txnId, null, 0, null, null);
    }

    public static LogRecord rollback(long txnId) {
        return new LogRecord(Type.ROLLBACK, txnId, null, 0, null, null);
    }

    public boolean isDataRecord() {
        return type == Type.PUT || type == Type.DELETE || type == Type.CLR_PUT || type == Type.CLR_DELETE;
    }

    public byte[] toBytes() {
        byte[] table = tableName == null ? new byte[0] : tableName.getBytes(StandardCharsets.UTF_8);
        int size = 1 + 8 + 4 + table.length + 8
                + 4 + (beforeValue == null ? 0 : beforeValue.length)
                + 4 + (afterValue == null ? 0 : afterValue.length);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put((byte) type.ordinal());
        b.putLong(txnId);
        b.putInt(table.length);
        b.put(table);
        b.putLong(key);
        b.putInt(beforeValue == null ? -1 : beforeValue.length);
        if (beforeValue != null) b.put(beforeValue);
        b.putInt(afterValue == null ? -1 : afterValue.length);
        if (afterValue != null) b.put(afterValue);
        return b.array();
    }

    public static LogRecord fromBytes(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data);
        Type type = Type.values()[b.get()];
        long txn = b.getLong();
        int tl = b.getInt();
        byte[] t = new byte[tl];
        b.get(t);
        long key = b.getLong();
        int bl = b.getInt();
        byte[] before = null;
        if (bl >= 0) {
            before = new byte[bl];
            b.get(before);
        }
        int al = b.getInt();
        byte[] after = null;
        if (al >= 0) {
            after = new byte[al];
            b.get(after);
        }
        return new LogRecord(type, txn, tl == 0 ? null : new String(t, StandardCharsets.UTF_8), key, before, after);
    }

    @Override
    public String toString() {
        return type + "(txn=" + txnId + (tableName != null ? ", " + tableName + "[" + key + "]" : "") + ")";
    }
}
