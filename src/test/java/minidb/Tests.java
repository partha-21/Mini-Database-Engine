package minidb;

import minidb.engine.*;
import minidb.index.btree.BPlusTree;
import minidb.index.lsm.BloomFilter;
import minidb.index.lsm.LSMTree;
import minidb.sql.Lexer;
import minidb.sql.Parser;
import minidb.sql.Token;
import minidb.sql.ast.*;
import minidb.storage.BufferPool;
import minidb.transaction.LogRecord;
import minidb.transaction.RecoveryManager;
import minidb.transaction.WALManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free test runner: java -cp out:out-test minidb.Tests */
public class Tests {
    static int passed = 0, failed = 0;
    static final List<String> failures = new ArrayList<>();

    interface T { void run() throws Exception; }

    static void test(String name, T t) {
        long t0 = System.currentTimeMillis();
        try {
            t.run();
            passed++;
            System.out.printf("  PASS  %-62s %5d ms%n", name, System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            failed++;
            failures.add(name);
            System.out.printf("  FAIL  %s%n        -> %s%n", name, e);
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) System.out.println("           at " + st[i]);
        }
    }

    static void check(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
    }

    static void eq(Object a, Object b, String msg) {
        if (!Objects.equals(a, b)) throw new AssertionError(msg + " (expected <" + b + "> but was <" + a + ">)");
    }

    static Path tmp() throws Exception {
        return Files.createTempDirectory("minidb-test");
    }

    static void rm(Path p) {
        try {
            if (!Files.exists(p)) return;
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    static Database.Options smallOpts() {
        Database.Options o = new Database.Options();
        o.lsmMemtableThreshold = 7;
        o.btreeMaxKeys = 4;
        o.checkpointWalBytes = 1L << 40; // never auto-checkpoint unless a test wants it
        return o;
    }

    static QueryResult q(QueryExecutor s, String sql) throws Exception {
        return s.execute(sql);
    }

    static void fails(QueryExecutor s, String sql, String contains) {
        try {
            s.execute(sql);
        } catch (Exception e) {
            check(e.getMessage() != null && e.getMessage().contains(contains),
                    "error for [" + sql + "] should contain '" + contains + "' but was: " + e.getMessage());
            return;
        }
        throw new AssertionError("expected failure for: " + sql);
    }

    static byte[] bytes(Random r, int len) {
        byte[] b = new byte[len];
        r.nextBytes(b);
        return b;
    }

    static List<Long> ids(QueryResult r) {
        List<Long> out = new ArrayList<>();
        for (Object[] row : r.rows) out.add((Long) row[0]);
        return out;
    }

    // ==================================================================
    // SQL parsing
    // ==================================================================
    static void sqlTests() {
        test("lexer: keywords, numbers, strings, operators", () -> {
            List<Token> t = new Lexer("select * FROM t WHERE a >= -5 AND b <> 'x''y' AND c != 3.5;").tokenize();
            eq(t.get(0).type, Token.Type.SELECT, "select kw");
            check(t.stream().anyMatch(x -> x.type == Token.Type.GTE), "GTE");
            check(t.stream().filter(x -> x.type == Token.Type.NEQ).count() == 2, "two NEQ forms");
            check(t.stream().anyMatch(x -> x.type == Token.Type.STRING && x.text.equals("x'y")), "escaped quote");
            check(t.stream().anyMatch(x -> x.type == Token.Type.NUMBER && x.text.equals("-5")), "negative number");
        });
        test("parser: every statement type builds the right AST", () -> {
            CreateTableStatement c = (CreateTableStatement) new Parser("CREATE TABLE u (id INT, name VARCHAR(20)) USING LSM").parse();
            eq(c.tableName, "u", "table"); eq(c.indexEngine, "LSM", "engine"); eq(c.columns.size(), 2, "cols");
            eq(c.columns.get(1).type, ColumnDef.ColType.TEXT, "varchar->text");
            InsertStatement i = (InsertStatement) new Parser("INSERT INTO u VALUES (1,'a'),(2,'b');").parse();
            eq(i.rows.size(), 2, "multi-row insert");
            SelectStatement s = (SelectStatement) new Parser("SELECT a,b FROM u WHERE id>=1 AND id<9 ORDER BY a DESC LIMIT 5").parse();
            eq(s.columns, List.of("a", "b"), "cols"); eq(s.where.size(), 2, "where"); eq(s.orderBy, "a", "order");
            check(s.orderDesc, "desc"); eq(s.limit, 5L, "limit");
            check(((SelectStatement) new Parser("SELECT COUNT(*) FROM u").parse()).countStar, "count");
            UpdateStatement u = (UpdateStatement) new Parser("UPDATE u SET name='z', id2=4 WHERE id=1").parse();
            eq(u.assignments.size(), 2, "assignments");
            check(new Parser("DELETE FROM u WHERE id=1").parse() instanceof DeleteStatement, "delete");
            check(new Parser("BEGIN").parse() instanceof TxnStatement, "begin");
            check(new Parser("EXPLAIN SELECT * FROM u").parse() instanceof ExplainStatement, "explain");
            check(new Parser("CHECKPOINT").parse() instanceof CheckpointStatement, "checkpoint");
            eq(((ShowStatement) new Parser("show tables").parse()).what, "TABLES", "show");
        });
        test("parser: malformed SQL is rejected with errors", () -> {
            for (String bad : new String[]{"SELEC * FROM t", "SELECT * FROM", "INSERT INTO t VALUES (1,",
                    "SELECT * FROM t WHERE", "SELECT * FROM t WHERE a ~ 1", "SELECT * FROM t extra",
                    "INSERT INTO t VALUES ('abc)", "CREATE TABLE t (a FLOAT)", "SELECT * FROM t LIMIT x", ""}) {
                try {
                    new Parser(bad).parse();
                    throw new AssertionError("should have failed: [" + bad + "]");
                } catch (AssertionError e) {
                    throw e;
                } catch (RuntimeException expected) {
                }
            }
        });
        test("lexer: SQL single-line and block comments are ignored", () -> {
            List<Token> t = new Lexer("CREATE TABLE users (id INT, name TEXT) USING BTREE -- single line comment\nSELECT * /* block comment */ FROM users;").tokenize();
            check(t.stream().anyMatch(x -> x.type == Token.Type.CREATE), "has create");
            check(t.stream().anyMatch(x -> x.type == Token.Type.SELECT), "has select");
        });
        test("planner: contradictory equality conditions remain contradictory", () -> {
            Path dir = tmp();
            try {
                Database db = new Database(dir.toString(), smallOpts());
                QueryExecutor s = db.newSession();
                q(s, "CREATE TABLE t (id INT, v TEXT)");
                QueryResult r = q(s, "EXPLAIN SELECT * FROM t WHERE id = 1 AND id = 2 AND id = 3");
                check(r.message.contains("INDEX RANGE SCAN") && r.message.contains("id >= 1 AND id <= 0"), "planner maintains impossible range: " + r.message);
                eq(q(s, "SELECT * FROM t WHERE id = 1 AND id = 2 AND id = 3").rows.size(), 0, "returns 0 rows");
                db.close();
            } finally {
                rm(dir);
            }
        });
        test("catalog: invalid column name with delimiters is rejected", () -> {
            Path dir = tmp();
            try {
                Database db = new Database(dir.toString(), smallOpts());
                try {
                    db.createTable("t", List.of(new ColumnDef("id", ColumnDef.ColType.INT), new ColumnDef("bad:col", ColumnDef.ColType.TEXT)), "BTREE");
                    throw new AssertionError("expected failure for invalid column name");
                } catch (RuntimeException e) {
                    check(e.getMessage().contains("Invalid column name"), "expected Invalid column name, got: " + e.getMessage());
                }
                db.close();
            } finally {
                rm(dir);
            }
        });
        test("recovery: mid-log corruption is flagged to prevent silent truncation", () -> {
            Path dir = tmp();
            try {
                String logPath = dir.resolve("wal.log").toString();
                WALManager w = new WALManager(logPath);
                for (int i = 1; i <= 6; i++) {
                    w.append(new LogRecord(LogRecord.Type.PUT, i, "t", i, null, new byte[]{(byte) i}));
                }
                w.fsync();
                w.close();
                // Corrupt byte 30 in the middle of the log file
                try (RandomAccessFile f = new RandomAccessFile(logPath, "rw")) {
                    f.seek(30);
                    f.write(new byte[]{0x7F, 0x7F, 0x7F, 0x7F});
                }
                WALManager w2 = new WALManager(logPath);
                w2.readAll();
                check(w2.hadCorruptMiddle(), "hadCorruptMiddle flag must be set for mid-log corruption");
                w2.close();
            } finally {
                rm(dir);
            }
        });
        test("server: multi-statement script splitter and error suggestions", () -> {
            List<String> stmts = Server.splitStatements("CREATE TABLE x (id INT); INSERT INTO x VALUES (1); SELECT * FROM x;");
            eq(stmts.size(), 3, "splits 3 statements");
            String sugg = Server.getSuggestion("CREATE TABLE a (b FLOAT)", "Unknown column type 'FLOAT'");
            check(sugg.contains("INT") && sugg.contains("TEXT"), "has type suggestions: " + sugg);
        });
    }

    // ==================================================================
    // B+Tree
    // ==================================================================
    static void btreeRandom(int maxKeys, int ops, int keyRange, int maxLen) throws Exception {
        Path dir = tmp();
        try {
            String path = dir.resolve("t.btree").toString();
            BPlusTree tree = new BPlusTree(path, maxKeys, 64, null);
            TreeMap<Long, byte[]> oracle = new TreeMap<>();
            Random rnd = new Random(1234 + maxKeys);
            for (int i = 0; i < ops; i++) {
                long k = rnd.nextInt(keyRange);
                int dice = rnd.nextInt(100);
                if (dice < 55) {
                    byte[] v = bytes(rnd, 1 + rnd.nextInt(maxLen));
                    tree.put(k, v);
                    oracle.put(k, v);
                } else if (dice < 85) {
                    tree.delete(k);
                    oracle.remove(k);
                } else {
                    check(Arrays.equals(tree.get(k), oracle.get(k)), "get mismatch for key " + k + " at op " + i);
                }
                if (i % 997 == 0) eq(tree.checkInvariants(), (long) oracle.size(), "invariants/size at op " + i);
            }
            eq(tree.checkInvariants(), (long) oracle.size(), "final size");
            List<Map.Entry<Long, byte[]>> all = tree.scanAll();
            eq(all.size(), oracle.size(), "scanAll size");
            Iterator<Map.Entry<Long, byte[]>> it = oracle.entrySet().iterator();
            for (Map.Entry<Long, byte[]> e : all) {
                Map.Entry<Long, byte[]> o = it.next();
                eq(e.getKey(), o.getKey(), "scan key order");
                check(Arrays.equals(e.getValue(), o.getValue()), "scan value for " + o.getKey());
            }
            tree.close(); // persistence: reopen and compare
            tree = new BPlusTree(path, maxKeys, 64, null);
            eq(tree.checkInvariants(), (long) oracle.size(), "size after reopen");
            for (Map.Entry<Long, byte[]> o : oracle.entrySet()) {
                check(Arrays.equals(tree.get(o.getKey()), o.getValue()), "persisted value " + o.getKey());
            }
            tree.close();
        } finally {
            rm(dir);
        }
    }

    static void btreeTests() {
        for (int mk : new int[]{3, 4, 5, 8}) {
            test("btree: 20k random ops vs oracle, maxKeys=" + mk, () -> btreeRandom(mk, 20000, 1500, 50));
        }
        test("btree: large values (byte-based splits), maxKeys=32", () -> btreeRandom(32, 6000, 400, 1000));
        test("btree: delete everything shrinks tree; freed pages are reused", () -> {
            Path dir = tmp();
            try {
                BPlusTree t = new BPlusTree(dir.resolve("f.btree").toString(), 4, 64, null);
                for (long i = 0; i < 3000; i++) t.put(i, new byte[]{(byte) i});
                int h = t.height();
                long pagesBefore = t.pageCount();
                check(h >= 4, "tree should be several levels tall, was " + h);
                for (long i = 0; i < 3000; i++) t.delete(i);
                eq(t.checkInvariants(), 0L, "empty after deleting all");
                eq(t.height(), 1, "height collapses back to 1");
                check(t.freePageCount() > 100, "pages should be on the free list, got " + t.freePageCount());
                for (long i = 0; i < 3000; i++) t.put(i, new byte[]{1});
                check(t.pageCount() <= pagesBefore + 2, "file must not grow: " + t.pageCount() + " vs " + pagesBefore);
                eq(t.checkInvariants(), 3000L, "reinserted");
                t.close();
            } finally {
                rm(dir);
            }
        });
        test("btree: range scans (all inclusive/exclusive combos) vs oracle", () -> {
            Path dir = tmp();
            try {
                BPlusTree t = new BPlusTree(dir.resolve("r.btree").toString(), 4, 64, null);
                TreeMap<Long, byte[]> o = new TreeMap<>();
                Random r = new Random(7);
                for (int i = 0; i < 1500; i++) {
                    long k = r.nextInt(3000) - 500;
                    t.put(k, new byte[]{1});
                    o.put(k, new byte[]{1});
                }
                for (int i = 0; i < 400; i++) {
                    Long lo = r.nextInt(5) == 0 ? null : (long) (r.nextInt(3600) - 600);
                    Long hi = r.nextInt(5) == 0 ? null : (long) (r.nextInt(3600) - 600);
                    boolean li = r.nextBoolean(), hiI = r.nextBoolean();
                    NavigableMap<Long, byte[]> m = o;
                    if (lo != null) m = m.tailMap(lo, li);
                    boolean empty = hi != null && lo != null && hi < lo;
                    if (hi != null && !empty) m = m.headMap(hi, hiI);
                    List<Long> expect = empty ? List.of() : new ArrayList<>(m.keySet());
                    List<Long> got = new ArrayList<>();
                    for (Map.Entry<Long, byte[]> e : t.scanRange(lo, li, hi, hiI)) got.add(e.getKey());
                    eq(got, expect, "range " + lo + (li ? " [" : " (") + hi + (hiI ? "]" : ")"));
                }
                t.close();
            } finally {
                rm(dir);
            }
        });
        test("btree: oversize value rejected", () -> {
            Path dir = tmp();
            try {
                BPlusTree t = new BPlusTree(dir.resolve("o.btree").toString());
                try {
                    t.put(1, new byte[1001]);
                    throw new AssertionError("should reject");
                } catch (IllegalArgumentException expected) {
                }
                t.close();
            } finally {
                rm(dir);
            }
        });
    }

    // ==================================================================
    // Buffer pool: crash-atomic checkpoints
    // ==================================================================
    static void btreeCheckpointFault(BufferPool.Fault fault, boolean tornJournal) throws Exception {
        Path dir = tmp();
        try {
            String path = dir.resolve("c.btree").toString();
            BPlusTree t = new BPlusTree(path, 4, 64, null);
            for (long i = 0; i < 500; i++) t.put(i, new byte[]{(byte) i, 1});
            t.flush(); // checkpoint #1 succeeds
            for (long i = 500; i < 1000; i++) t.put(i, new byte[]{(byte) i, 2});
            t.getPool().fault = fault;
            try {
                t.flush();
                throw new AssertionError("fault should have fired");
            } catch (java.io.IOException expected) {
            }
            t.simulateCrash();
            File journal = new File(path + ".dwb");
            check(journal.exists(), "journal must exist after the injected crash");
            if (tornJournal) {
                try (RandomAccessFile f = new RandomAccessFile(journal, "rw")) {
                    f.setLength(f.length() / 2);
                }
            }
            BPlusTree r = new BPlusTree(path, 4, 64, null);
            long expected = tornJournal ? 500 : 1000;
            eq(r.checkInvariants(), expected, "tree must be fully consistent after crash recovery");
            for (long i = 0; i < expected; i++) check(r.get(i) != null, "key " + i + " lost");
            if (tornJournal) check(r.get(700) == null, "torn journal => previous checkpoint state");
            check(!journal.exists(), "journal removed after recovery");
            r.close();
        } finally {
            rm(dir);
        }
    }

    static void bufferPoolTests() {
        test("checkpoint crash after journal sync => journal replayed", () -> btreeCheckpointFault(BufferPool.Fault.AFTER_JOURNAL, false));
        test("checkpoint crash mid in-place write => journal repairs torn pages", () -> btreeCheckpointFault(BufferPool.Fault.MID_WRITE, false));
        test("checkpoint crash with torn journal => old checkpoint stays intact", () -> btreeCheckpointFault(BufferPool.Fault.AFTER_JOURNAL, true));
        test("buffer pool: LRU eviction of clean pages, dirty pages pinned", () -> {
            Path dir = tmp();
            try {
                BPlusTree t = new BPlusTree(dir.resolve("e.btree").toString(), 4, 8, null); // tiny pool
                for (long i = 0; i < 400; i++) t.put(i, new byte[]{1});
                eq(t.checkInvariants(), 400L, "dirty pages must never be lost to eviction");
                t.flush();
                for (long i = 0; i < 400; i++) check(t.get(i) != null, "after flush+eviction key " + i);
                t.close();
            } finally {
                rm(dir);
            }
        });
    }

    // ==================================================================
    // LSM
    // ==================================================================
    static void lsmTests() {
        test("bloom filter: no false negatives, low false-positive rate", () -> {
            BloomFilter b = new BloomFilter(10000, 10);
            for (long i = 0; i < 10000; i++) b.add(i * 7);
            for (long i = 0; i < 10000; i++) check(b.mightContain(i * 7), "false negative " + i);
            int fp = 0;
            for (long i = 0; i < 100000; i++) if (b.mightContain(1_000_000 + i * 7 + 1)) fp++;
            check(fp < 3000, "false positive rate too high: " + fp + "/100000");
        });
        test("lsm: 15k random ops vs oracle (flush + tiered compaction), reopen", () -> {
            Path dir = tmp();
            try {
                String d = dir.resolve("l").toString();
                LSMTree t = new LSMTree(d, 5, null);
                TreeMap<Long, byte[]> o = new TreeMap<>();
                Random rnd = new Random(99);
                for (int i = 0; i < 15000; i++) {
                    long k = rnd.nextInt(800);
                    int dice = rnd.nextInt(100);
                    if (dice < 55) {
                        byte[] v = bytes(rnd, 1 + rnd.nextInt(40));
                        t.put(k, v);
                        o.put(k, v);
                    } else if (dice < 80) {
                        t.delete(k);
                        o.remove(k);
                    } else {
                        check(Arrays.equals(t.get(k), o.get(k)), "get mismatch key " + k + " op " + i);
                    }
                }
                check(t.getCompactions() > 0, "compaction must have run");
                check(t.getSSTableCount() < 12, "compaction must bound table count, had " + t.getSSTableCount());
                check(t.getBloomRejects() > 0, "bloom filter must have skipped some reads");
                compareLsm(t, o);
                for (int i = 0; i < 100; i++) {
                    long lo = rnd.nextInt(900) - 50, hi = lo + rnd.nextInt(300);
                    List<Long> exp = new ArrayList<>(o.subMap(lo, true, hi, false).keySet()), got = new ArrayList<>();
                    for (Map.Entry<Long, byte[]> e : t.scanRange(lo, true, hi, false)) got.add(e.getKey());
                    eq(got, exp, "lsm range [" + lo + "," + hi + ")");
                }
                t.close();
                t = new LSMTree(d, 5, null);
                compareLsm(t, o);
                t.close();
            } finally {
                rm(dir);
            }
        });
        test("lsm: interrupted compaction leftovers can't resurrect deleted keys", () -> {
            Path dir = tmp();
            try {
                String d = dir.resolve("l").toString();
                LSMTree t = new LSMTree(d, 2, null);
                t.put(1, new byte[]{9});
                t.put(2, new byte[]{9});
                t.flush(); // table seq0 holds key 1
                File seq0 = new File(d).listFiles((x, n) -> n.contains("00000000-00000000"))[0];
                File backup = dir.resolve("seq0.bak").toFile();
                Files.copy(seq0.toPath(), backup.toPath());
                t.delete(1);
                t.put(3, new byte[]{1});
                t.flush(); // seq1: tombstone for 1
                t.put(4, new byte[]{1}); t.put(5, new byte[]{1});
                t.flush();
                t.put(6, new byte[]{1}); t.put(7, new byte[]{1}); // fourth flush triggers compaction (tombstone dropped)
                t.flush();
                check(t.getCompactions() >= 1, "compaction ran");
                check(t.get(1) == null, "deleted key stays deleted");
                t.close();
                // simulate crash after compaction output was renamed but before old inputs were deleted
                Files.copy(backup.toPath(), new File(d, seq0.getName()).toPath());
                Files.write(Paths.get(d, "sstable_00000099-00000099_L0.db.tmp"), new byte[]{1, 2, 3});
                LSMTree r = new LSMTree(d, 2, null);
                check(r.get(1) == null, "key 1 must NOT be resurrected from the stale table");
                check(r.get(2) != null && r.get(7) != null, "live keys intact");
                check(!new File(d, "sstable_00000099-00000099_L0.db.tmp").exists(), ".tmp leftovers deleted");
                r.close();
            } finally {
                rm(dir);
            }
        });
    }

    static void compareLsm(LSMTree t, TreeMap<Long, byte[]> o) throws Exception {
        List<Map.Entry<Long, byte[]>> all = t.scanAll();
        eq(all.size(), o.size(), "lsm scanAll size");
        Iterator<Map.Entry<Long, byte[]>> it = o.entrySet().iterator();
        for (Map.Entry<Long, byte[]> e : all) {
            Map.Entry<Long, byte[]> x = it.next();
            eq(e.getKey(), x.getKey(), "lsm key order");
            check(Arrays.equals(e.getValue(), x.getValue()), "lsm value " + x.getKey());
        }
        for (long k = 0; k < 800; k++) check(Arrays.equals(t.get(k), o.get(k)), "lsm get " + k);
    }

    // ==================================================================
    // WAL
    // ==================================================================
    static void walTests() {
        test("wal: torn tail and corrupted frame are detected, intact prefix survives", () -> {
            Path dir = tmp();
            try {
                String p = dir.resolve("wal.log").toString();
                WALManager w = new WALManager(p);
                for (int i = 1; i <= 5; i++) w.append(new LogRecord(LogRecord.Type.PUT, i, "t", i, null, new byte[]{(byte) i}));
                w.fsync();
                w.close();
                eq(new WALManager(p).readAll().size(), 5, "clean log");
                try (RandomAccessFile f = new RandomAccessFile(p, "rw")) {
                    f.seek(f.length());
                    f.write(new byte[]{0, 0, 0, 40, 1, 2, 3}); // half-written frame
                }
                WALManager w2 = new WALManager(p);
                eq(w2.readAll().size(), 5, "torn tail ignored");
                check(w2.wasTornTail(), "torn flag set");
                w2.close();
                try (RandomAccessFile f = new RandomAccessFile(p, "rw")) {
                    f.setLength(f.length() - 7);
                    f.seek(f.length() - 3);
                    f.write(0x7F); // corrupt a payload byte of the last record
                }
                WALManager w3 = new WALManager(p);
                eq(w3.readAll().size(), 4, "corrupt last frame dropped by CRC");
                w3.close();
            } finally {
                rm(dir);
            }
        });
    }

    // ==================================================================
    // SQL end to end
    // ==================================================================
    static void sqlEndToEnd(String engine) throws Exception {
        Path dir = tmp();
        try (Database db = new Database(dir.toString(), smallOpts())) {
            QueryExecutor s = db.newSession();
            q(s, "CREATE TABLE t (id INT, name TEXT, age INT) USING " + engine);
            TreeMap<Long, Object[]> model = new TreeMap<>();
            q(s, "BEGIN");
            for (long i = 1; i <= 120; i++) {
                long id = i * 3;
                q(s, "INSERT INTO t VALUES (" + id + ", 'n" + id + "', " + (i % 50) + ")");
                model.put(id, new Object[]{id, "n" + id, i % 50});
            }
            q(s, "COMMIT");

            QueryResult r = q(s, "SELECT * FROM t WHERE id = 30");
            eq(r.rows.size(), 1, "point hit"); check(r.plan.startsWith("POINT LOOKUP"), "point plan: " + r.plan);
            eq(q(s, "SELECT * FROM t WHERE id = 31").rows.size(), 0, "point miss");

            r = q(s, "SELECT id FROM t WHERE id > 30 AND id <= 60");
            List<Long> exp = new ArrayList<>(model.subMap(30L, false, 60L, true).keySet());
            eq(ids(r), exp, "range result");
            check(r.plan.contains("INDEX RANGE SCAN id > 30 AND id <= 60"), "range plan: " + r.plan);

            long wantAge = 7;
            exp.clear();
            for (Object[] m : model.values()) if ((Long) m[2] == wantAge) exp.add((Long) m[0]);
            r = q(s, "SELECT id FROM t WHERE age = 7");
            eq(ids(r), exp, "full scan filter"); check(r.plan.startsWith("FULL SCAN"), "scan plan");

            r = q(s, "SELECT id FROM t WHERE id >= 100 AND age < 10");
            exp.clear();
            for (Object[] m : model.tailMap(100L, true).values()) if ((Long) m[2] < 10) exp.add((Long) m[0]);
            eq(ids(r), exp, "range + residual filter");
            check(r.plan.contains("INDEX RANGE SCAN") && r.plan.contains("FILTER age < 10"), "mixed plan: " + r.plan);

            eq(q(s, "SELECT COUNT(*) FROM t WHERE id >= 100").rows.get(0)[0], (long) model.tailMap(100L, true).size(), "count");
            eq(ids(q(s, "SELECT id FROM t ORDER BY id DESC LIMIT 3")), List.of(360L, 357L, 354L), "order desc limit");
            List<String> names = new ArrayList<>();
            for (Object[] m : model.values()) names.add((String) m[1]);
            Collections.sort(names);
            r = q(s, "SELECT name FROM t ORDER BY name LIMIT 2");
            eq(r.rows.get(0)[0], names.get(0), "order by text"); eq(r.rows.get(1)[0], names.get(1), "order by text 2");
            eq(q(s, "SELECT id FROM t WHERE id > 50 AND id < 40").rows.size(), 0, "empty range");
            eq(q(s, "SELECT id FROM t WHERE id = 3 AND id = 6").rows.size(), 0, "contradictory equality");
            eq(q(s, "SELECT id FROM t WHERE id <> 3").rows.size(), 119, "<> falls back to filter");
            check(q(s, "EXPLAIN SELECT * FROM t WHERE id = 9").message.contains("POINT LOOKUP"), "explain");
            eq(q(s, "SELECT name FROM t WHERE name = 'it''s'").rows.size(), 0, "quote literal");

            QueryResult u = q(s, "UPDATE t SET age = 99 WHERE id >= 300");
            check(u.message.contains("21 row(s) updated"), u.message);
            eq(q(s, "SELECT COUNT(*) FROM t WHERE age = 99").rows.get(0)[0], 21L, "updated rows visible");
            QueryResult d = q(s, "DELETE FROM t WHERE age = 99");
            check(d.message.contains("21 row(s) deleted"), d.message);
            eq(q(s, "SELECT COUNT(*) FROM t").rows.get(0)[0], 99L, "rows after delete");

            fails(s, "INSERT INTO t VALUES (3, 'dup', 1)", "Duplicate primary key");
            fails(s, "INSERT INTO t VALUES ('x', 'a', 1)", "Type mismatch");
            fails(s, "INSERT INTO t VALUES (1000, 5, 1)", "Type mismatch");
            fails(s, "INSERT INTO t VALUES (1000, 'a')", "columns");
            fails(s, "SELECT * FROM t WHERE age = 'old'", "Type mismatch");
            fails(s, "SELECT nope FROM t", "No such column");
            fails(s, "SELECT * FROM missing", "No such table");
            fails(s, "UPDATE t SET id = 5 WHERE id = 3", "primary key");
            fails(s, "INSERT INTO t VALUES (2000, '" + "x".repeat(1100) + "', 1)", "Row too large");
            fails(s, "CREATE TABLE t (id INT)", "already exists");
            fails(s, "CREATE TABLE bad (name TEXT)", "must be INT");
            fails(s, "CREATE TABLE bad2 (id INT) USING HASH", "Unknown engine");
            if (engine.equals("BTREE")) {
                ((BPlusTree) db.getTable("t").getIndex()).checkInvariants();
            }
        } finally {
            rm(dir);
        }
    }

    static void sqlTxnTests() {
        for (String engine : new String[]{"BTREE", "LSM"}) {
            test("sql: full statement coverage [" + engine + "]", () -> sqlEndToEnd(engine));
            test("txn: rollback restores inserts, updates and deletes [" + engine + "]", () -> {
                Path dir = tmp();
                try (Database db = new Database(dir.toString(), smallOpts())) {
                    QueryExecutor s = db.newSession();
                    q(s, "CREATE TABLE a (id INT, v TEXT) USING " + engine);
                    for (int i = 1; i <= 30; i++) q(s, "INSERT INTO a VALUES (" + i + ", 'v" + i + "')");
                    q(s, "BEGIN");
                    q(s, "INSERT INTO a VALUES (100, 'new')");
                    q(s, "UPDATE a SET v = 'changed' WHERE id <= 10");
                    q(s, "DELETE FROM a WHERE id > 20 AND id <= 25");
                    eq(q(s, "SELECT COUNT(*) FROM a").rows.get(0)[0], 26L, "inside txn sees own writes");
                    check(q(s, "ROLLBACK").message.contains("rolled back"), "rollback msg");
                    eq(q(s, "SELECT COUNT(*) FROM a").rows.get(0)[0], 30L, "count restored");
                    eq(q(s, "SELECT v FROM a WHERE id = 5").rows.get(0)[0], "v5", "update undone");
                    eq(q(s, "SELECT * FROM a WHERE id = 100").rows.size(), 0, "insert undone");
                    eq(q(s, "SELECT * FROM a WHERE id = 23").rows.size(), 1, "delete undone");
                } finally {
                    rm(dir);
                }
            });
            test("txn: failed statement aborts whole transaction; multi-row insert is atomic [" + engine + "]", () -> {
                Path dir = tmp();
                try (Database db = new Database(dir.toString(), smallOpts())) {
                    QueryExecutor s = db.newSession();
                    q(s, "CREATE TABLE a (id INT, v TEXT) USING " + engine);
                    fails(s, "INSERT INTO a VALUES (1,'a'),(2,'b'),(1,'dup')", "Duplicate");
                    eq(q(s, "SELECT COUNT(*) FROM a").rows.get(0)[0], 0L, "autocommit multi-row insert is all-or-nothing");
                    q(s, "BEGIN");
                    q(s, "INSERT INTO a VALUES (1,'a')");
                    fails(s, "INSERT INTO a VALUES (1,'again')", "aborted and rolled back");
                    check(!s.inTransaction(), "session left the transaction");
                    eq(q(s, "SELECT COUNT(*) FROM a").rows.get(0)[0], 0L, "first insert rolled back too");
                    q(s, "BEGIN");
                    q(s, "INSERT INTO a VALUES (5,'x')");
                    q(s, "COMMIT");
                    eq(q(s, "SELECT COUNT(*) FROM a").rows.get(0)[0], 1L, "committed");
                    fails(s, "COMMIT", "No active transaction");
                } finally {
                    rm(dir);
                }
            });
        }
        test("sql: misc statements, session rules and result formatting", () -> {
            Path dir = tmp();
            try (Database db = new Database(dir.toString(), smallOpts())) {
                QueryExecutor s = db.newSession();
                q(s, "CREATE TABLE Mixed (id INT, name TEXT) USING LSM");
                q(s, "INSERT INTO MIXED VALUES (1,'a'),(2,'b'),(3,'c')");
                eq(q(s, "SELECT * FROM mixed WHERE ID = 2").rows.size(), 1, "table and column names are case-insensitive");
                eq(q(s, "SELECT * FROM mixed LIMIT 0").rows.size(), 0, "LIMIT 0");
                eq(q(s, "SELECT * FROM mixed LIMIT 2").rows.size(), 2, "LIMIT 2");
                check(q(s, "EXPLAIN UPDATE mixed SET name = 'z' WHERE id >= 2").message.contains("INDEX RANGE SCAN"), "explain update");
                check(q(s, "EXPLAIN DELETE FROM mixed WHERE name = 'a'").message.contains("FULL SCAN"), "explain delete");
                check(q(s, "SHOW STATS").message.contains("mixed: LSM-Tree"), "show stats lists engines");
                eq(q(s, "UPDATE mixed SET name = 'q' WHERE id = 99").message.contains("0 row(s) updated"), true, "update no match");
                String shown = q(s, "SELECT id, name FROM mixed ORDER BY id").toString();
                check(shown.contains("id | name") && shown.contains("(3 rows)"), "formatted table: " + shown);
                eq(q(s, "SELECT id FROM mixed WHERE id = 1").toString().contains("(1 row)"), true, "singular row label");
                fails(s, "SHOW BOGUS", "SHOW supports");
                fails(s, "EXPLAIN INSERT INTO mixed VALUES (9,'x')", "EXPLAIN supports");
                fails(s, "ROLLBACK", "No active transaction");
                q(s, "BEGIN");
                check(s.inTransaction(), "in txn");
                fails(s, "BEGIN", "already active");
                fails(s, "CREATE TABLE other (id INT)", "not allowed inside a transaction");
                fails(s, "CHECKPOINT", "not allowed inside a transaction");
                q(s, "ROLLBACK");
                s.close();
                s.close(); // idempotent
                eq(db.getTable("mixed").getColumns().size(), 2, "catalog intact");
            } finally {
                rm(dir);
            }
        });
        test("btree via SQL: ~900-byte rows exercise byte-based leaf splits", () -> {
            Path dir = tmp();
            try (Database db = new Database(dir.toString())) {
                QueryExecutor s = db.newSession();
                q(s, "CREATE TABLE big (id INT, body TEXT) USING BTREE");
                q(s, "BEGIN");
                Random r = new Random(5);
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < 150; i++) order.add(i);
                Collections.shuffle(order, r);
                for (int i : order) q(s, "INSERT INTO big VALUES (" + i + ", '" + "y".repeat(400 + r.nextInt(500)) + "')");
                q(s, "COMMIT");
                eq(q(s, "SELECT COUNT(*) FROM big").rows.get(0)[0], 150L, "all rows stored");
                eq(((BPlusTree) db.getTable("big").getIndex()).checkInvariants(), 150L, "invariants with large rows");
                q(s, "DELETE FROM big WHERE id < 100");
                eq(((BPlusTree) db.getTable("big").getIndex()).checkInvariants(), 50L, "invariants after big deletes");
            } finally {
                rm(dir);
            }
        });
    }

    // ==================================================================
    // Crash recovery
    // ==================================================================
    static void recoveryTests() {
        for (String engine : new String[]{"BTREE", "LSM"}) {
            test("recovery: committed work survives a crash with no checkpoint [" + engine + "]", () -> {
                Path dir = tmp();
                try {
                    Database db = new Database(dir.toString(), smallOpts());
                    QueryExecutor s = db.newSession();
                    q(s, "CREATE TABLE t (id INT, v TEXT) USING " + engine);
                    for (int i = 1; i <= 50; i++) q(s, "INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
                    q(s, "DELETE FROM t WHERE id > 40");
                    q(s, "UPDATE t SET v = 'upd' WHERE id <= 5");
                    db.simulateCrash(true);
                    db = new Database(dir.toString(), smallOpts());
                    check(db.getLastRecovery().redone > 0, "redo must have run: " + db.getLastRecovery());
                    s = db.newSession();
                    eq(q(s, "SELECT COUNT(*) FROM t").rows.get(0)[0], 40L, "row count after recovery");
                    eq(q(s, "SELECT v FROM t WHERE id = 3").rows.get(0)[0], "upd", "update recovered");
                    eq(q(s, "SELECT * FROM t WHERE id = 45").rows.size(), 0, "delete recovered");
                    eq(db.getWal().size(), 0L, "WAL truncated after recovery checkpoint");
                    db.close();
                } finally {
                    rm(dir);
                }
            });
            test("recovery: in-flight transaction is undone, even if its data reached disk [" + engine + "]", () -> {
                Path dir = tmp();
                try {
                    Database db = new Database(dir.toString(), smallOpts());
                    QueryExecutor s = db.newSession();
                    q(s, "CREATE TABLE t (id INT, v TEXT) USING " + engine);
                    for (int i = 1; i <= 10; i++) q(s, "INSERT INTO t VALUES (" + i + ", 'keep')");
                    q(s, "BEGIN");
                    // > memtable threshold, so on LSM the uncommitted writes get flushed into an SSTable
                    for (int i = 100; i < 140; i++) q(s, "INSERT INTO t VALUES (" + i + ", 'ghost')");
                    q(s, "UPDATE t SET v = 'tampered' WHERE id <= 5");
                    q(s, "DELETE FROM t WHERE id = 9");
                    // LSM: the flush forced the WAL to disk, so the loser's records survive power loss.
                    // B+Tree (no-steal): nothing forced them, so keep the log tail to exercise the undo path.
                    db.simulateCrash(engine.equals("LSM"));
                    db = new Database(dir.toString(), smallOpts());
                    RecoveryManager.Report rep = db.getLastRecovery();
                    check(rep.losers >= 1, "uncommitted txn identified as loser: " + rep);
                    s = db.newSession();
                    eq(q(s, "SELECT COUNT(*) FROM t").rows.get(0)[0], 10L, "ghost rows must not exist");
                    eq(q(s, "SELECT v FROM t WHERE id = 3").rows.get(0)[0], "keep", "uncommitted update undone");
                    eq(q(s, "SELECT * FROM t WHERE id = 9").rows.size(), 1, "uncommitted delete undone");
                    db.close();
                } finally {
                    rm(dir);
                }
            });
            test("recovery: compensation records keep rollback + later commit correct [" + engine + "]", () -> {
                Path dir = tmp();
                try {
                    Database db = new Database(dir.toString(), smallOpts());
                    QueryExecutor s = db.newSession();
                    q(s, "CREATE TABLE t (id INT, v TEXT) USING " + engine);
                    q(s, "INSERT INTO t VALUES (5, 'orig')");
                    q(s, "BEGIN");
                    q(s, "UPDATE t SET v = 'temp' WHERE id = 5");
                    q(s, "ROLLBACK");
                    q(s, "UPDATE t SET v = 'final' WHERE id = 5"); // committed AFTER the rolled-back write
                    db.simulateCrash(true);
                    db = new Database(dir.toString(), smallOpts());
                    eq(db.newSession().execute("SELECT v FROM t WHERE id = 5").rows.get(0)[0], "final",
                            "undoing the rolled-back txn must not clobber the later commit");
                    db.close();
                } finally {
                    rm(dir);
                }
            });
            test("recovery: torn WAL tail after crash is ignored [" + engine + "]", () -> {
                Path dir = tmp();
                try {
                    Database db = new Database(dir.toString(), smallOpts());
                    QueryExecutor s = db.newSession();
                    q(s, "CREATE TABLE t (id INT, v TEXT) USING " + engine);
                    for (int i = 1; i <= 12; i++) q(s, "INSERT INTO t VALUES (" + i + ", 'v')");
                    db.simulateCrash(false);
                    try (RandomAccessFile f = new RandomAccessFile(dir.resolve("wal.log").toFile(), "rw")) {
                        f.seek(f.length());
                        f.write(new byte[]{0, 0, 1, 0, 9, 9, 9, 9, 1, 2, 3});
                    }
                    db = new Database(dir.toString(), smallOpts());
                    check(db.getLastRecovery().tornTail, "torn tail detected");
                    eq(db.newSession().execute("SELECT COUNT(*) FROM t").rows.get(0)[0], 12L, "intact records recovered");
                    db.close();
                } finally {
                    rm(dir);
                }
            });
            test("recovery: repeated crashes, checkpoints and restarts stay consistent [" + engine + "]", () -> {
                Path dir = tmp();
                try {
                    Random rnd = new Random(2024);
                    TreeMap<Long, String> model = new TreeMap<>();
                    Database db = new Database(dir.toString(), smallOpts());
                    db.newSession().execute("CREATE TABLE t (id INT, v TEXT) USING " + engine);
                    for (int round = 0; round < 12; round++) {
                        QueryExecutor s = db.newSession();
                        for (int i = 0; i < 40; i++) {
                            long k = rnd.nextInt(60);
                            int op = rnd.nextInt(3);
                            if (op == 0 && !model.containsKey(k)) {
                                q(s, "INSERT INTO t VALUES (" + k + ", 'r" + round + "')");
                                model.put(k, "r" + round);
                            } else if (op == 1) {
                                q(s, "UPDATE t SET v = 'u" + round + "' WHERE id = " + k);
                                if (model.containsKey(k)) model.put(k, "u" + round);
                            } else if (op == 2) {
                                q(s, "DELETE FROM t WHERE id = " + k);
                                model.remove(k);
                            }
                        }
                        if (round % 3 == 0) q(s, "CHECKPOINT");
                        // leave an in-flight transaction that must vanish
                        q(s, "BEGIN");
                        q(s, "INSERT INTO t VALUES (" + (1000 + round) + ", 'inflight')");
                        if (round % 2 == 0) {
                            db.simulateCrash(true);
                            db = new Database(dir.toString(), smallOpts());
                        } else {
                            s.close(); // rollback + release lock
                            db.close();
                            db = new Database(dir.toString(), smallOpts());
                        }
                        QueryResult r = db.newSession().execute("SELECT id, v FROM t");
                        TreeMap<Long, String> actual = new TreeMap<>();
                        for (Object[] row : r.rows) actual.put((Long) row[0], (String) row[1]);
                        eq(actual, model, "state after round " + round);
                    }
                    db.close();
                } finally {
                    rm(dir);
                }
            });
        }
        test("checkpoint: SQL CHECKPOINT truncates WAL; data survives crash from data files alone", () -> {
            Path dir = tmp();
            try {
                Database db = new Database(dir.toString(), smallOpts());
                QueryExecutor s = db.newSession();
                q(s, "CREATE TABLE b (id INT, v TEXT) USING BTREE");
                q(s, "CREATE TABLE l (id INT, v TEXT) USING LSM");
                for (int i = 1; i <= 30; i++) {
                    q(s, "INSERT INTO b VALUES (" + i + ", 'x')");
                    q(s, "INSERT INTO l VALUES (" + i + ", 'x')");
                }
                check(db.getWal().size() > 0, "WAL has content");
                check(q(s, "CHECKPOINT").message.contains("-> 0 bytes"), "checkpoint message");
                eq(db.getWal().size(), 0L, "WAL empty");
                db.simulateCrash(true);
                db = new Database(dir.toString(), smallOpts());
                eq(db.getLastRecovery().records, 0, "nothing left to replay");
                s = db.newSession();
                eq(q(s, "SELECT COUNT(*) FROM b").rows.get(0)[0], 30L, "btree data in file");
                eq(q(s, "SELECT COUNT(*) FROM l").rows.get(0)[0], 30L, "lsm data in sstables");
                db.close();
            } finally {
                rm(dir);
            }
        });
        test("checkpoint: automatic checkpoint bounds WAL growth", () -> {
            Path dir = tmp();
            try {
                Database.Options o = smallOpts();
                o.checkpointWalBytes = 3000;
                Database db = new Database(dir.toString(), o);
                QueryExecutor s = db.newSession();
                q(s, "CREATE TABLE t (id INT, v TEXT) USING BTREE");
                long max = 0;
                for (int i = 0; i < 300; i++) {
                    q(s, "INSERT INTO t VALUES (" + i + ", 'value-" + i + "')");
                    max = Math.max(max, db.getWal().size());
                }
                check(max < 6000, "WAL must stay bounded, peaked at " + max);
                db.close();
            } finally {
                rm(dir);
            }
        });
        test("catalog: tables and data persist across clean restarts", () -> {
            Path dir = tmp();
            try {
                Database db = new Database(dir.toString(), smallOpts());
                QueryExecutor s = db.newSession();
                q(s, "CREATE TABLE People (id INT, name TEXT) USING LSM");
                q(s, "CREATE TABLE other (id INT, n INT) USING BTREE");
                q(s, "INSERT INTO people VALUES (1, 'Ann'), (2, 'Bo')");
                db.close();
                db = new Database(dir.toString(), smallOpts());
                s = db.newSession();
                eq(q(s, "SHOW TABLES").rows.size(), 2, "both tables restored");
                eq(q(s, "SELECT name FROM people WHERE id = 2").rows.get(0)[0], "Bo", "data restored");
                q(s, "INSERT INTO other VALUES (1, 10)");
                db.close();
            } finally {
                rm(dir);
            }
        });
    }

    // ==================================================================
    // Concurrency / isolation
    // ==================================================================
    static void concurrencyTests() {
        for (String engine : new String[]{"BTREE", "LSM"}) {
            test("isolation: concurrent transfers keep invariant, readers never see partial txns [" + engine + "]", () -> {
                Path dir = tmp();
                try {
                    Database.Options o = smallOpts();
                    o.lockTimeoutMs = 60_000;
                    o.checkpointWalBytes = 4000; // exercise checkpoints under load
                    Database db = new Database(dir.toString(), o);
                    QueryExecutor admin = db.newSession();
                    q(admin, "CREATE TABLE acct (id INT, bal INT) USING " + engine);
                    for (int i = 0; i < 10; i++) q(admin, "INSERT INTO acct VALUES (" + i + ", 1000)");
                    final Database live = db;
                    ExecutorService pool = Executors.newFixedThreadPool(8);
                    AtomicInteger readerChecks = new AtomicInteger(), commits = new AtomicInteger();
                    List<Future<?>> fs = new ArrayList<>();
                    for (int w = 0; w < 6; w++) {
                        final int seed = w;
                        fs.add(pool.submit(() -> {
                            Random r = new Random(seed);
                            try (QueryExecutor s = live.newSession()) {
                                for (int i = 0; i < 40; i++) {
                                    int a = r.nextInt(10), b = (a + 1 + r.nextInt(9)) % 10, amt = 1 + r.nextInt(50);
                                    s.execute("BEGIN");
                                    long ba = (Long) s.execute("SELECT bal FROM acct WHERE id = " + a).rows.get(0)[0];
                                    long bb = (Long) s.execute("SELECT bal FROM acct WHERE id = " + b).rows.get(0)[0];
                                    s.execute("UPDATE acct SET bal = " + (ba - amt) + " WHERE id = " + a);
                                    if (r.nextInt(10) == 0) {
                                        s.execute("ROLLBACK"); // some transfers abort midway
                                    } else {
                                        s.execute("UPDATE acct SET bal = " + (bb + amt) + " WHERE id = " + b);
                                        s.execute("COMMIT");
                                        commits.incrementAndGet();
                                    }
                                }
                            }
                            return null;
                        }));
                    }
                    for (int rd = 0; rd < 2; rd++) {
                        fs.add(pool.submit(() -> {
                            try (QueryExecutor s = live.newSession()) {
                                for (int i = 0; i < 60; i++) {
                                    long sum = 0;
                                    for (Object[] row : s.execute("SELECT id, bal FROM acct").rows) sum += (Long) row[1];
                                    if (sum != 10000) throw new AssertionError("reader saw inconsistent total " + sum);
                                    readerChecks.incrementAndGet();
                                }
                            }
                            return null;
                        }));
                    }
                    for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
                    pool.shutdown();
                    long total = 0;
                    for (Object[] row : admin.execute("SELECT id, bal FROM acct").rows) total += (Long) row[1];
                    eq(total, 10000L, "money is conserved");
                    check(commits.get() > 100 && readerChecks.get() == 120, "work actually happened");
                    db.simulateCrash(true); // and the invariant survives a crash too
                    db = new Database(dir.toString(), o);
                    total = 0;
                    for (Object[] row : db.newSession().execute("SELECT id, bal FROM acct").rows) total += (Long) row[1];
                    eq(total, 10000L, "money conserved after crash recovery");
                    db.close();
                } finally {
                    rm(dir);
                }
            });
        }
        test("isolation: lock timeout, and closing a session releases its lock", () -> {
            Path dir = tmp();
            try {
                Database.Options o = smallOpts();
                o.lockTimeoutMs = 300;
                try (Database db = new Database(dir.toString(), o)) {
                    QueryExecutor a = db.newSession();
                    q(a, "CREATE TABLE t (id INT, v TEXT)");
                    q(a, "BEGIN");
                    q(a, "INSERT INTO t VALUES (1, 'uncommitted')");
                    ExecutorService ex = Executors.newSingleThreadExecutor();
                    String err = ex.submit(() -> {
                        try {
                            db.newSession().execute("SELECT * FROM t");
                            return "no error";
                        } catch (Exception e) {
                            return e.getMessage();
                        }
                    }).get();
                    check(err.contains("Lock timeout"), "other session must wait then time out, got: " + err);
                    a.close(); // rolls back and releases
                    int rows = ex.submit(() -> db.newSession().execute("SELECT * FROM t").rows.size()).get();
                    eq(rows, 0, "uncommitted insert was rolled back and is not visible");
                    ex.shutdown();
                }
            } finally {
                rm(dir);
            }
        });
    }

    public static void main(String[] args) {
        System.out.println("\nMini Database Engine - test suite\n");
        System.out.println("SQL front end");
        sqlTests();
        System.out.println("\nB+Tree");
        btreeTests();
        System.out.println("\nBuffer pool / atomic checkpoints");
        bufferPoolTests();
        System.out.println("\nLSM-Tree");
        lsmTests();
        System.out.println("\nWrite-ahead log");
        walTests();
        System.out.println("\nSQL execution + transactions");
        sqlTxnTests();
        System.out.println("\nCrash recovery");
        recoveryTests();
        System.out.println("\nConcurrency / isolation");
        concurrencyTests();
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.out.println("FAILED: " + failures);
            System.exit(1);
        }
    }
}
