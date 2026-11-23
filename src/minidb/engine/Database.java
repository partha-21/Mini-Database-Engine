package minidb.engine;

import minidb.sql.ast.ColumnDef;
import minidb.transaction.RecoveryManager;
import minidb.transaction.TransactionManager;
import minidb.transaction.WALManager;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The database: catalog of tables, WAL, transaction manager and lock.
 *
 * Startup: load catalog -> open tables -> run crash recovery from the WAL ->
 * checkpoint (flush data files, truncate WAL).
 *
 * Isolation: one fair read/write lock for the whole database. Autocommit reads
 * take it shared, writes and explicit transactions take it exclusive for their
 * whole duration (strict two-phase locking at database granularity), which
 * gives SERIALIZABLE isolation and prevents dirty reads, lost updates and
 * phantoms - at the cost of write concurrency.
 */
public class Database implements AutoCloseable {
    public static class Options {
        public int lsmMemtableThreshold = minidb.index.lsm.LSMTree.DEFAULT_MEMTABLE_THRESHOLD;
        public int btreeMaxKeys = minidb.index.btree.BPlusTree.DEFAULT_MAX_KEYS;
        public int btreePoolPages = minidb.index.btree.BPlusTree.DEFAULT_POOL_PAGES;
        public long checkpointWalBytes = 1L << 20;
        public long lockTimeoutMs = 10_000;
    }

    private final String dir;
    private final Options opts;
    private final WALManager wal;
    private final TransactionManager txm;
    private final Catalog catalog;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final RecoveryManager.Report recovery;

    public Database(String dir) throws Exception {
        this(dir, new Options());
    }

    @SuppressWarnings("this-escape") // method references to this::walFlush are only invoked after construction
    public Database(String dir, Options opts) throws Exception {
        this.dir = dir;
        this.opts = opts;
        new File(dir).mkdirs();
        this.wal = new WALManager(dir + "/wal.log");
        this.txm = new TransactionManager(wal);
        this.catalog = new Catalog(dir);
        for (Catalog.Entry e : catalog.load()) {
            tables.put(e.name, new Table(e.name, e.columns, e.engine, dir, opts, this::walFlush));
        }
        this.recovery = RecoveryManager.recover(wal, name -> {
            Table t = tables.get(name);
            return t == null ? null : t.getIndex();
        });
        if (wal.hadCorruptMiddle()) {
            throw new IllegalStateException("Unrecoverable WAL corruption detected mid-log in " + dir + "/wal.log; refusing to checkpoint or truncate WAL to prevent data loss.");
        }
        txm.setNextTxnId(recovery.maxTxnId + 1);
        if (wal.size() > 0) checkpointLocked();
    }

    private void walFlush() {
        try {
            wal.fsync();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public QueryExecutor newSession() {
        return new QueryExecutor(this);
    }

    ReentrantReadWriteLock lock() {
        return lock;
    }

    TransactionManager txnManager() {
        return txm;
    }

    public Options options() {
        return opts;
    }

    public RecoveryManager.Report getLastRecovery() {
        return recovery;
    }

    public WALManager getWal() {
        return wal;
    }

    /** Caller must hold the exclusive lock. */
    public synchronized Table createTable(String name, List<ColumnDef> columns, String engine) throws Exception {
        String n = name.toLowerCase();
        if (!n.matches("[a-z_][a-z0-9_]*")) throw new RuntimeException("Invalid table name: " + name);
        if (tables.containsKey(n)) throw new RuntimeException("Table '" + n + "' already exists");
        String eng = engine.toUpperCase();
        if (!eng.equals("BTREE") && !eng.equals("LSM")) throw new RuntimeException("Unknown engine '" + engine + "' (use BTREE or LSM)");
        if (columns.get(0).type != ColumnDef.ColType.INT) throw new RuntimeException("First column is the primary key and must be INT");
        Set<String> names = new HashSet<>();
        for (ColumnDef c : columns) {
            if (!c.name.toLowerCase().matches("[a-z_][a-z0-9_]*")) {
                throw new RuntimeException("Invalid column name: " + c.name);
            }
            if (!names.add(c.name.toLowerCase())) throw new RuntimeException("Duplicate column name: " + c.name);
        }
        Table t = new Table(n, columns, eng, dir, opts, this::walFlush);
        tables.put(n, t);
        saveCatalog();
        return t;
    }

    private void saveCatalog() throws IOException {
        List<Catalog.Entry> entries = new ArrayList<>();
        for (Table t : tables.values()) entries.add(new Catalog.Entry(t.getName(), t.getEngineName(), t.getColumns()));
        catalog.save(entries);
    }

    public synchronized Table getTable(String name) {
        Table t = tables.get(name.toLowerCase());
        if (t == null) throw new RuntimeException("No such table: " + name);
        return t;
    }

    public synchronized List<Table> allTables() {
        return new ArrayList<>(tables.values());
    }

    /** Flush all data files, then truncate the WAL. Caller must hold the exclusive lock. */
    synchronized void checkpointLocked() throws Exception {
        for (Table t : tables.values()) t.getIndex().flush();
        wal.truncate();
    }

    synchronized void maybeCheckpoint() throws Exception {
        if (wal.size() > opts.checkpointWalBytes) checkpointLocked();
    }

    public void checkpoint() throws Exception {
        lock.writeLock().lock();
        try {
            checkpointLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public synchronized String stats() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("WAL: ").append(wal.size()).append(" bytes\n");
        } catch (IOException e) {
            sb.append("WAL: unavailable\n");
        }
        for (Table t : tables.values()) sb.append(t.getName()).append(": ").append(t.getIndex().stats()).append('\n');
        return sb.toString().trim();
    }

    /** Clean shutdown: checkpoint, close every table and the WAL. */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            checkpointLocked();
            for (Table t : tables.values()) t.close();
            wal.close();
        } catch (Exception e) {
            throw new IllegalStateException("Error while closing database: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Test/demo hook that behaves like a process crash: no checkpoint, buffer pools and
     * memtables are discarded. With loseUnsyncedWal, log bytes that were never fsync'd vanish too.
     */
    public synchronized void simulateCrash(boolean loseUnsyncedWal) throws Exception {
        for (Table t : tables.values()) t.getIndex().simulateCrash();
        wal.simulateCrash(loseUnsyncedWal);
    }
}
