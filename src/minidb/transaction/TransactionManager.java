package minidb.transaction;

import java.util.concurrent.atomic.AtomicLong;

public class TransactionManager {
    private final WALManager wal;
    private final AtomicLong nextTxnId = new AtomicLong(1);

    public TransactionManager(WALManager wal) {
        this.wal = wal;
    }

    public Transaction begin() throws Exception {
        long id = nextTxnId.getAndIncrement();
        wal.append(LogRecord.begin(id));
        return new Transaction(id, wal);
    }

    public void setNextTxnId(long id) {
        nextTxnId.set(id);
    }

    public WALManager getWal() {
        return wal;
    }
}
