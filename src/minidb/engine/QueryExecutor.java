package minidb.engine;

import minidb.sql.Parser;
import minidb.sql.ast.*;
import minidb.transaction.Transaction;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public class QueryExecutor implements AutoCloseable {
    private final Database db;
    private Transaction activeTx;

    public QueryExecutor(Database db) {
        this.db = db;
    }

    public boolean inTransaction() {
        return activeTx != null;
    }

    private interface Op<T> {
        T run() throws Exception;
    }

    private interface WriteOp {
        String run(Transaction tx) throws Exception;
    }

    public QueryResult execute(String sql) throws Exception {
        Statement s = new Parser(sql).parse();
        if (s instanceof TxnStatement)
            return txnControl((TxnStatement) s);
        if (s instanceof CreateTableStatement)
            return ddlCreate((CreateTableStatement) s);
        if (s instanceof CheckpointStatement)
            return checkpoint();
        if (s instanceof ShowStatement)
            return readLocked(() -> show((ShowStatement) s));
        if (s instanceof ExplainStatement)
            return readLocked(() -> explain(((ExplainStatement) s).inner));
        if (s instanceof SelectStatement)
            return readLocked(() -> select((SelectStatement) s));
        if (s instanceof InsertStatement)
            return write(tx -> insert((InsertStatement) s, tx));
        if (s instanceof UpdateStatement)
            return write(tx -> update((UpdateStatement) s, tx));
        if (s instanceof DeleteStatement)
            return write(tx -> delete((DeleteStatement) s, tx));
        throw new RuntimeException("Unsupported statement");
    }

    private void acquire(Lock l) throws Exception {
        if (!l.tryLock(db.options().lockTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Lock timeout after " + db.options().lockTimeoutMs
                    + "ms: another transaction is holding the database lock");
        }
    }

    private QueryResult readLocked(Op<QueryResult> op) throws Exception {
        if (activeTx != null)
            return op.run();
        acquire(db.lock().readLock());
        try {
            return op.run();
        } finally {
            db.lock().readLock().unlock();
        }
    }

    private QueryResult write(WriteOp op) throws Exception {
        if (activeTx != null) {
            try {
                return QueryResult.message(op.run(activeTx) + " (pending commit).");
            } catch (Exception e) {
                long id = activeTx.getId();
                abortExplicit();
                throw new RuntimeException("Transaction " + id + " aborted and rolled back: " + e.getMessage(), e);
            }
        }
        acquire(db.lock().writeLock());
        try {
            Transaction tx = db.txnManager().begin();
            try {
                String msg = op.run(tx);
                tx.commit();
                db.maybeCheckpoint();
                return QueryResult.message(msg + " (auto-committed).");
            } catch (Exception e) {
                if (tx.isActive())
                    tx.rollback();
                throw e;
            }
        } finally {
            db.lock().writeLock().unlock();
        }
    }

    private void abortExplicit() throws Exception {
        try {
            if (activeTx != null && activeTx.isActive())
                activeTx.rollback();
        } finally {
            activeTx = null;
            db.lock().writeLock().unlock();
        }
    }

    private QueryResult txnControl(TxnStatement s) throws Exception {
        switch (s.kind) {
            case BEGIN: {
                if (activeTx != null)
                    throw new RuntimeException("A transaction is already active");
                acquire(db.lock().writeLock());
                try {
                    activeTx = db.txnManager().begin();
                } catch (Exception e) {
                    db.lock().writeLock().unlock();
                    throw e;
                }
                return QueryResult.message("Transaction " + activeTx.getId() + " started (exclusive lock held).");
            }
            case COMMIT: {
                if (activeTx == null)
                    throw new RuntimeException("No active transaction");
                long id = activeTx.getId();
                int writes = activeTx.writeCount();
                try {
                    activeTx.commit();
                    db.maybeCheckpoint();
                } catch (Exception e) {
                    abortExplicit();
                    throw e;
                }
                activeTx = null;
                db.lock().writeLock().unlock();
                return QueryResult.message("Transaction " + id + " committed (" + writes + " write(s) durable).");
            }
            default: {
                if (activeTx == null)
                    throw new RuntimeException("No active transaction");
                long id = activeTx.getId();
                int writes = activeTx.writeCount();
                abortExplicit();
                return QueryResult.message("Transaction " + id + " rolled back (" + writes + " write(s) undone).");
            }
        }
    }

    private QueryResult ddlCreate(CreateTableStatement s) throws Exception {
        if (activeTx != null)
            throw new RuntimeException("CREATE TABLE is not allowed inside a transaction");
        acquire(db.lock().writeLock());
        try {
            Table t = db.createTable(s.tableName, s.columns, s.indexEngine);
            return QueryResult.message("Table '" + t.getName() + "' created (engine=" + t.getEngineName() + ").");
        } finally {
            db.lock().writeLock().unlock();
        }
    }

    private QueryResult checkpoint() throws Exception {
        if (activeTx != null)
            throw new RuntimeException("CHECKPOINT is not allowed inside a transaction");
        acquire(db.lock().writeLock());
        try {
            long before = db.getWal().size();
            db.checkpointLocked();
            return QueryResult.message(
                    "Checkpoint complete: data files flushed, WAL " + before + " -> " + db.getWal().size() + " bytes.");
        } finally {
            db.lock().writeLock().unlock();
        }
    }

    @Override
    public void close() {
        if (activeTx == null)
            return;
        try {
            abortExplicit();
        } catch (Exception e) {
            throw new IllegalStateException("Error while closing session: " + e.getMessage(), e);
        }
    }

    private static class Plan {
        Long point;
        Long lo, hi;
        boolean loInc = true, hiInc = true;
        boolean contradictory = false;
        List<Condition> residual = new ArrayList<>();

        String describe(Table t) {
            String via = " via " + (t.getEngineName().equals("LSM") ? "LSM-Tree" : "B+Tree");
            String base;
            if (point != null) {
                base = "POINT LOOKUP " + t.pkName() + " = " + point + via;
            } else if (lo != null || hi != null) {
                StringBuilder sb = new StringBuilder("INDEX RANGE SCAN ");
                if (lo != null)
                    sb.append(t.pkName()).append(loInc ? " >= " : " > ").append(lo);
                if (lo != null && hi != null)
                    sb.append(" AND ");
                if (hi != null)
                    sb.append(t.pkName()).append(hiInc ? " <= " : " < ").append(hi);
                base = sb + via;
            } else {
                base = "FULL SCAN" + via;
            }
            if (!residual.isEmpty()) {
                StringBuilder f = new StringBuilder(" + FILTER ");
                for (int i = 0; i < residual.size(); i++) {
                    f.append(i > 0 ? " AND " : "").append(residual.get(i).column).append(' ')
                            .append(opSymbol(residual.get(i).op))
                            .append(' ').append(residual.get(i).value);
                }
                base += f;
            }
            return base;
        }
    }

    private static String opSymbol(Condition.Op op) {
        switch (op) {
            case EQ:
                return "=";
            case NEQ:
                return "<>";
            case LT:
                return "<";
            case LTE:
                return "<=";
            case GT:
                return ">";
            default:
                return ">=";
        }
    }

    private void validateConditions(Table t, List<Condition> where) {
        for (Condition c : where) {
            int idx = t.columnIndex(c.column);
            boolean isInt = t.getColumns().get(idx).type == ColumnDef.ColType.INT;
            if (isInt && !(c.value instanceof Long)) {
                throw new RuntimeException(
                        "Type mismatch in WHERE: column '" + c.column + "' is INT but literal is '" + c.value + "'");
            }
            if (!isInt && !(c.value instanceof String)) {
                throw new RuntimeException("Type mismatch in WHERE: column '" + c.column + "' is TEXT but literal is "
                        + c.value + " (quote text values)");
            }
        }
    }

    private Plan plan(Table t, List<Condition> where) {
        Plan p = new Plan();
        for (Condition c : where) {
            boolean onPk = c.column.equalsIgnoreCase(t.pkName());
            if (!onPk) {
                p.residual.add(c);
                continue;
            }
            long v = (Long) c.value;
            switch (c.op) {
                case EQ:
                    if (p.contradictory) {
                        p.residual.add(c);
                        break;
                    }
                    if (p.point == null) {
                        if (p.lo != null && ((p.loInc && v < p.lo) || (!p.loInc && v <= p.lo))) {
                            p.contradictory = true;
                            p.lo = 1L;
                            p.hi = 0L;
                            p.loInc = p.hiInc = true;
                            p.point = null;
                        } else if (p.hi != null && ((p.hiInc && v > p.hi) || (!p.hiInc && v >= p.hi))) {
                            p.contradictory = true;
                            p.lo = 1L;
                            p.hi = 0L;
                            p.loInc = p.hiInc = true;
                            p.point = null;
                        } else {
                            p.point = v;
                        }
                    } else if (p.point != v) {
                        p.contradictory = true;
                        p.lo = 1L;
                        p.hi = 0L;
                        p.loInc = p.hiInc = true;
                        p.point = null;
                    }
                    break;
                case GT:
                case GTE: {
                    if (p.contradictory) {
                        p.residual.add(c);
                        break;
                    }
                    boolean inc = c.op == Condition.Op.GTE;
                    if (p.point != null) {
                        if ((inc && p.point < v) || (!inc && p.point <= v)) {
                            p.contradictory = true;
                            p.lo = 1L;
                            p.hi = 0L;
                            p.loInc = p.hiInc = true;
                            p.point = null;
                        }
                        break;
                    }
                    if (p.lo == null || v > p.lo) {
                        p.lo = v;
                        p.loInc = inc;
                    } else if (v == p.lo)
                        p.loInc = p.loInc && inc;
                    if (p.hi != null && (p.lo > p.hi || (p.lo.equals(p.hi) && !(p.loInc && p.hiInc)))) {
                        p.contradictory = true;
                        p.lo = 1L;
                        p.hi = 0L;
                        p.loInc = p.hiInc = true;
                    }
                    break;
                }
                case LT:
                case LTE: {
                    if (p.contradictory) {
                        p.residual.add(c);
                        break;
                    }
                    boolean inc = c.op == Condition.Op.LTE;
                    if (p.point != null) {
                        if ((inc && p.point > v) || (!inc && p.point >= v)) {
                            p.contradictory = true;
                            p.lo = 1L;
                            p.hi = 0L;
                            p.loInc = p.hiInc = true;
                            p.point = null;
                        }
                        break;
                    }
                    if (p.hi == null || v < p.hi) {
                        p.hi = v;
                        p.hiInc = inc;
                    } else if (v == p.hi)
                        p.hiInc = p.hiInc && inc;
                    if (p.lo != null && (p.lo > p.hi || (p.lo.equals(p.hi) && !(p.loInc && p.hiInc)))) {
                        p.contradictory = true;
                        p.lo = 1L;
                        p.hi = 0L;
                        p.loInc = p.hiInc = true;
                    }
                    break;
                }
                default:
                    p.residual.add(c);
            }
        }
        return p;
    }

    private List<Object[]> fetch(Table t, Plan p, List<Condition> where) throws Exception {
        List<Object[]> out = new ArrayList<>();
        if (p.point != null) {
            Object[] r = t.getByKey(p.point);
            if (r != null && matches(t, r, where))
                out.add(r);
        } else {
            for (Object[] r : t.scanRange(p.lo, p.loInc, p.hi, p.hiInc)) {
                if (matches(t, r, where))
                    out.add(r);
            }
        }
        return out;
    }

    private boolean matches(Table t, Object[] row, List<Condition> where) {
        for (Condition c : where) {
            Object actual = row[t.columnIndex(c.column)];
            int cmp = actual instanceof Long ? Long.compare((Long) actual, (Long) c.value)
                    : ((String) actual).compareTo((String) c.value);
            boolean ok;
            switch (c.op) {
                case EQ:
                    ok = cmp == 0;
                    break;
                case NEQ:
                    ok = cmp != 0;
                    break;
                case LT:
                    ok = cmp < 0;
                    break;
                case LTE:
                    ok = cmp <= 0;
                    break;
                case GT:
                    ok = cmp > 0;
                    break;
                default:
                    ok = cmp >= 0;
            }
            if (!ok)
                return false;
        }
        return true;
    }

    private QueryResult select(SelectStatement s) throws Exception {
        Table t = db.getTable(s.tableName);
        validateConditions(t, s.where);
        Plan p = plan(t, s.where);
        List<Object[]> rows = fetch(t, p, s.where);
        String planText = p.describe(t);

        if (s.countStar) {
            return QueryResult.rows(List.of("count"), List.<Object[]>of(new Object[] { (long) rows.size() }), planText);
        }
        if (s.orderBy != null) {
            int oi = t.columnIndex(s.orderBy);
            Comparator<Object[]> cmp = (a, b) -> a[oi] instanceof Long
                    ? Long.compare((Long) a[oi], (Long) b[oi])
                    : ((String) a[oi]).compareTo((String) b[oi]);
            rows.sort(s.orderDesc ? cmp.reversed() : cmp);
        }
        if (s.limit >= 0 && rows.size() > s.limit)
            rows = new ArrayList<>(rows.subList(0, (int) s.limit));

        List<Integer> idxs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (s.columns.size() == 1 && s.columns.get(0).equals("*")) {
            for (int i = 0; i < t.getColumns().size(); i++) {
                idxs.add(i);
                names.add(t.getColumns().get(i).name);
            }
        } else {
            for (String c : s.columns) {
                int i = t.columnIndex(c);
                idxs.add(i);
                names.add(t.getColumns().get(i).name);
            }
        }
        List<Object[]> projected = new ArrayList<>();
        for (Object[] r : rows) {
            Object[] pr = new Object[idxs.size()];
            for (int i = 0; i < pr.length; i++)
                pr[i] = r[idxs.get(i)];
            projected.add(pr);
        }
        return QueryResult.rows(names, projected, planText);
    }

    private String insert(InsertStatement s, Transaction tx) throws Exception {
        Table t = db.getTable(s.tableName);
        for (List<Object> row : s.rows)
            t.insert(tx, row);
        return s.rows.size() + " row(s) inserted into '" + t.getName() + "'";
    }

    private String update(UpdateStatement s, Transaction tx) throws Exception {
        Table t = db.getTable(s.tableName);
        validateConditions(t, s.where);
        Map<Integer, Object> sets = new LinkedHashMap<>();
        for (Assignment a : s.assignments) {
            int idx = t.columnIndex(a.column);
            if (idx == 0)
                throw new RuntimeException("Cannot update the primary key column '" + a.column + "'");
            sets.put(idx, t.coerceValue(idx, a.value));
        }
        int n = 0;
        for (Object[] row : fetch(t, plan(t, s.where), s.where)) {
            Object[] nr = row.clone();
            for (Map.Entry<Integer, Object> e : sets.entrySet())
                nr[e.getKey()] = e.getValue();
            t.replace(tx, nr);
            n++;
        }
        return n + " row(s) updated in '" + t.getName() + "'";
    }

    private String delete(DeleteStatement s, Transaction tx) throws Exception {
        Table t = db.getTable(s.tableName);
        validateConditions(t, s.where);
        int n = 0;
        for (Object[] row : fetch(t, plan(t, s.where), s.where)) {
            t.deleteByKey(tx, (Long) row[0]);
            n++;
        }
        return n + " row(s) deleted from '" + t.getName() + "'";
    }

    private QueryResult explain(Statement inner) {
        String table = inner instanceof SelectStatement ? ((SelectStatement) inner).tableName
                : inner instanceof UpdateStatement ? ((UpdateStatement) inner).tableName
                        : ((DeleteStatement) inner).tableName;
        List<Condition> where = inner instanceof SelectStatement ? ((SelectStatement) inner).where
                : inner instanceof UpdateStatement ? ((UpdateStatement) inner).where
                        : ((DeleteStatement) inner).where;
        Table t = db.getTable(table);
        validateConditions(t, where);
        return QueryResult.message("EXPLAIN: " + plan(t, where).describe(t));
    }

    private QueryResult show(ShowStatement s) {
        if (s.what.equals("STATS"))
            return QueryResult.message(db.stats());
        List<Object[]> rows = new ArrayList<>();
        for (Table t : db.allTables()) {
            StringBuilder cols = new StringBuilder();
            for (ColumnDef c : t.getColumns())
                cols.append(cols.length() > 0 ? ", " : "").append(c.name).append(' ').append(c.type);
            rows.add(new Object[] { t.getName(), t.getEngineName(), cols.toString() });
        }
        return QueryResult.rows(List.of("table", "engine", "columns"), rows, null);
    }
}
