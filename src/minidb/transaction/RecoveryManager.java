package minidb.transaction;

import minidb.index.Index;

import java.util.*;
import java.util.function.Function;

/**
 * Crash recovery in the style of ARIES, simplified for a single-writer log:
 *
 *   1. ANALYSIS: scan the log; a transaction with a COMMIT or ROLLBACK record
 *      is complete, anything else is a "loser" (it was in flight at the crash).
 *   2. REDO: repeat history - re-apply every PUT/DELETE/CLR in log order.
 *      Redo is idempotent, so it doesn't matter which changes had already
 *      reached the data files (this is what makes no-force commits safe).
 *   3. UNDO: roll back each loser by applying before-images in reverse order
 *      (needed when e.g. an LSM flush persisted uncommitted data).
 */
public class RecoveryManager {
    public static class Report {
        public int records, committed, rolledBack, losers, redone, undone;
        public boolean tornTail, corruptMiddle;
        public long maxTxnId;

        @Override
        public String toString() {
            return String.format("scanned %d log records%s%s | committed=%d rolledBack=%d inFlight(losers)=%d | redone=%d undone=%d",
                    records, tornTail ? " (torn tail ignored)" : "", corruptMiddle ? " (mid-log corruption detected)" : "", committed, rolledBack, losers, redone, undone);
        }
    }

    public static Report recover(WALManager wal, Function<String, Index> resolver) throws Exception {
        Report rep = new Report();
        List<LogRecord> log = wal.readAll();
        rep.records = log.size();
        rep.tornTail = wal.wasTornTail();
        rep.corruptMiddle = wal.hadCorruptMiddle();

        Set<Long> seen = new HashSet<>(), committed = new HashSet<>(), rolledBack = new HashSet<>();
        for (LogRecord r : log) {
            seen.add(r.txnId);
            rep.maxTxnId = Math.max(rep.maxTxnId, r.txnId);
            if (r.type == LogRecord.Type.COMMIT) committed.add(r.txnId);
            if (r.type == LogRecord.Type.ROLLBACK) rolledBack.add(r.txnId);
        }
        Set<Long> losers = new HashSet<>(seen);
        losers.removeAll(committed);
        losers.removeAll(rolledBack);
        rep.committed = committed.size();
        rep.rolledBack = rolledBack.size();
        rep.losers = losers.size();

        for (LogRecord r : log) {
            if (!r.isDataRecord()) continue;
            Index idx = resolver.apply(r.tableName);
            if (idx == null) continue;
            if (r.type == LogRecord.Type.PUT || r.type == LogRecord.Type.CLR_PUT) idx.put(r.key, r.afterValue);
            else idx.delete(r.key);
            rep.redone++;
        }
        for (int i = log.size() - 1; i >= 0; i--) {
            LogRecord r = log.get(i);
            if (!losers.contains(r.txnId)) continue;
            if (r.type != LogRecord.Type.PUT && r.type != LogRecord.Type.DELETE) continue;
            Index idx = resolver.apply(r.tableName);
            if (idx == null) continue;
            if (r.beforeValue == null) idx.delete(r.key);
            else idx.put(r.key, r.beforeValue);
            rep.undone++;
        }
        return rep;
    }
}
