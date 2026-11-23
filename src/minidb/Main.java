package minidb;

import minidb.engine.Database;
import minidb.engine.QueryExecutor;
import minidb.index.btree.BPlusTree;
import minidb.index.lsm.LSMTree;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : (System.getenv("PORT") != null ? "server" : "demo");
        switch (mode) {
            case "server": {
                int port = 8080;
                String dir = "minidb-data";
                String envPort = System.getenv("PORT");
                if (envPort != null && !envPort.isBlank()) {
                    try {
                        port = Integer.parseInt(envPort.trim());
                    } catch (NumberFormatException ignored) {}
                }
                if (args.length > 1) {
                    try {
                        port = Integer.parseInt(args[1]);
                        if (args.length > 2)
                            dir = args[2];
                    } catch (NumberFormatException e) {
                        dir = args[1];
                        if (args.length > 2)
                            port = Integer.parseInt(args[2]);
                    }
                }
                Server.start(dir, port);
                break;
            }
            case "repl":
                repl(args.length > 1 ? args[1] : "minidb-data");
                break;
            case "bench":
                Benchmark.main(Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
                break;
            default:
                demo();
        }
    }

    static void section(String t) {
        System.out.println("\n" + "=".repeat(78) + "\n" + t + "\n" + "=".repeat(78));
    }

    static void run(QueryExecutor s, String sql) {
        System.out.println("\nsql> " + sql);
        try {
            System.out.println(s.execute(sql));
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    static void rm(Path p) {
        try {
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    static void demo() throws Exception {
        Path dir = Files.createTempDirectory("minidb-demo");
        Path dir2 = Files.createTempDirectory("minidb-demo-crash");
        try {
            Database db = new Database(dir.toString());
            QueryExecutor s = db.newSession();

            section("1) Two tables, two storage engines (chosen per table)");
            run(s, "CREATE TABLE users (id INT, name TEXT, age INT) USING BTREE");
            run(s, "CREATE TABLE events (id INT, kind TEXT) USING LSM");
            run(s, "SHOW TABLES");

            section("2) INSERT (multi-row is atomic) and SELECT - watch the access path the planner picks");
            run(s, "INSERT INTO users VALUES (1,'Alice',30), (2,'Bob',25), (3,'Carol',40), (4,'Dave',22), (5,'Erin',35), (6,'Frank',28), (7,'Grace',45)");
            run(s, "SELECT * FROM users WHERE id = 5");
            run(s, "SELECT name, age FROM users WHERE id >= 3 AND id < 7");
            run(s, "SELECT name FROM users WHERE age >= 30");
            run(s, "EXPLAIN SELECT * FROM users WHERE id > 2 AND age < 40");

            section("3) UPDATE, DELETE, ORDER BY, LIMIT, COUNT(*)");
            run(s, "UPDATE users SET age = 31 WHERE name = 'Alice'");
            run(s, "DELETE FROM users WHERE id = 4");
            run(s, "SELECT name, age FROM users ORDER BY age DESC LIMIT 3");
            run(s, "SELECT COUNT(*) FROM users WHERE age > 30");

            section("4) Consistency: constraints are enforced");
            run(s, "INSERT INTO users VALUES (1, 'Mallory', 99)");
            run(s, "INSERT INTO users VALUES ('x', 'Bad', 1)");
            run(s, "SELECT * FROM users WHERE age = 'old'");

            section("5) Atomicity: ROLLBACK undoes everything; a failing statement aborts the transaction");
            run(s, "BEGIN");
            run(s, "INSERT INTO users VALUES (8, 'Heidi', 19)");
            run(s, "UPDATE users SET age = 0 WHERE id <= 3");
            run(s, "SELECT id, name, age FROM users WHERE id <= 3");
            run(s, "ROLLBACK");
            run(s, "SELECT id, name, age FROM users WHERE id <= 3");
            run(s, "BEGIN");
            run(s, "INSERT INTO users VALUES (9, 'Ivan', 51)");
            run(s, "INSERT INTO users VALUES (9, 'Ivan again', 52)");
            run(s, "SELECT * FROM users WHERE id = 9");

            section("6) Inside the B+Tree: splits, then delete with borrow/merge rebalancing");
            Path tp = Files.createTempDirectory("tree");
            BPlusTree tree = new BPlusTree(tp.resolve("demo.btree").toString(), 4, 64, null);
            for (long k = 1; k <= 22; k++)
                tree.put(k, new byte[] { 1 });
            System.out.println("after inserting 1..22 (max 4 keys/node):");
            tree.printTree();
            for (long k : new long[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 })
                tree.delete(k);
            System.out.println("\nafter deleting 1..14 (nodes borrow from siblings or merge, tree shrinks):");
            tree.printTree();
            System.out.println("invariants verified: " + tree.checkInvariants() + " keys, all leaves same depth, "
                    + "separators valid, leaf chain intact; free pages for reuse: " + tree.freePageCount());
            tree.close();
            rm(tp);

            section("7) Inside the LSM-Tree: memtable -> SSTable flush -> tiered compaction -> bloom filters");
            s = db.newSession();
            run(s, "BEGIN");
            for (int i = 0; i < 1000; i++)
                s.execute("INSERT INTO events VALUES (" + i + ", 'click')");
            run(s, "COMMIT");
            LSMTree lsm = (LSMTree) db.getTable("events").getIndex();
            System.out.println("\nSSTable files on disk (newest first):");
            lsm.describeTables().forEach(x -> System.out.println("  " + x));
            System.out.println("memtable holds " + lsm.getMemTableSize() + " unflushed entries");
            for (int i = 0; i < 500; i++)
                s.execute("SELECT * FROM events WHERE id = " + (5000 + i));
            System.out.println("after 500 lookups of keys that DON'T exist:");
            System.out.println("  " + lsm.stats());
            run(s, "SELECT COUNT(*) FROM events WHERE id >= 100 AND id < 200");

            section("8) Durability has a price: fsync on every commit");
            long t0 = System.nanoTime();
            for (int i = 2000; i < 2100; i++)
                s.execute("INSERT INTO events VALUES (" + i + ", 'auto')");
            double auto = (System.nanoTime() - t0) / 1e6;
            t0 = System.nanoTime();
            s.execute("BEGIN");
            for (int i = 3000; i < 3100; i++)
                s.execute("INSERT INTO events VALUES (" + i + ", 'batch')");
            s.execute("COMMIT");
            double batch = (System.nanoTime() - t0) / 1e6;
            System.out.printf(
                    "100 autocommit inserts (100 fsyncs): %7.1f ms%n100 inserts in one transaction (1 fsync): %7.1f ms%n",
                    auto, batch);

            section("9) Crash recovery (ARIES-style redo + undo from the write-ahead log)");
            Database c = new Database(dir2.toString());
            QueryExecutor cs = c.newSession();
            cs.execute("CREATE TABLE acct (id INT, owner TEXT, bal INT) USING LSM");
            cs.execute("INSERT INTO acct VALUES (1,'alice',100),(2,'bob',200)");
            cs.execute("UPDATE acct SET bal = 150 WHERE id = 1");
            cs.execute("BEGIN");
            for (int i = 10; i < 260; i++) cs.execute("INSERT INTO acct VALUES (" + i + ", 'ghost', 0)");
            cs.execute("UPDATE acct SET bal = 999999 WHERE id = 2");
            System.out.println("State just before crash (WAL " + c.getWal().size() + " bytes, nothing checkpointed):");
            System.out.println(cs.execute("SELECT COUNT(*) FROM acct"));
            System.out.println("\n*** CRASH: memtables / buffer pools dropped, no COMMIT for the open transaction ***");
            c.simulateCrash(true);
            try (RandomAccessFile f = new RandomAccessFile(dir2.resolve("wal.log").toFile(), "rw")) {
                f.seek(f.length());
                f.write(new byte[]{0, 0, 0, 90, 1, 2, 3});
            }
            c = new Database(dir2.toString());
            System.out.println("Recovery: " + c.getLastRecovery());
            QueryExecutor rs = c.newSession();
            System.out.println(rs.execute("SELECT * FROM acct"));
            System.out.println(
                    "-> committed work present (alice=150). The 250 uncommitted ghost rows had already been flushed");
            System.out.println(
                    "   to an SSTable on disk, but recovery found no COMMIT and UNDID them (bob is back to 200).");
            c.close();

            section("10) Isolation: concurrent money transfers, serializable by strict 2-phase locking");
            s = db.newSession();
            s.execute("CREATE TABLE bank (id INT, bal INT) USING BTREE");
            s.execute("BEGIN");
            for (int i = 0; i < 8; i++)
                s.execute("INSERT INTO bank VALUES (" + i + ", 1000)");
            s.execute("COMMIT");
            ExecutorService pool = Executors.newFixedThreadPool(6);
            List<Future<Integer>> fs = new ArrayList<>();
            final Database live = db;
            for (int w = 0; w < 4; w++) {
                final int seed = w;
                fs.add(pool.submit(() -> {
                    Random r = new Random(seed);
                    int done = 0;
                    try (QueryExecutor x = live.newSession()) {
                        for (int i = 0; i < 25; i++) {
                            int a = r.nextInt(8), b = (a + 1 + r.nextInt(7)) % 8, amt = 1 + r.nextInt(100);
                            x.execute("BEGIN");
                            long ba = (Long) x.execute("SELECT bal FROM bank WHERE id = " + a).rows.get(0)[0];
                            long bb = (Long) x.execute("SELECT bal FROM bank WHERE id = " + b).rows.get(0)[0];
                            x.execute("UPDATE bank SET bal = " + (ba - amt) + " WHERE id = " + a);
                            x.execute("UPDATE bank SET bal = " + (bb + amt) + " WHERE id = " + b);
                            x.execute("COMMIT");
                            done++;
                        }
                    }
                    return done;
                }));
            }
            Future<Integer> auditor = pool.submit(() -> {
                int bad = 0;
                try (QueryExecutor x = live.newSession()) {
                    for (int i = 0; i < 40; i++) {
                        long sum = 0;
                        for (Object[] row : x.execute("SELECT id, bal FROM bank").rows)
                            sum += (Long) row[1];
                        if (sum != 8000)
                            bad++;
                    }
                }
                return bad;
            });
            int transfers = 0;
            for (Future<Integer> f : fs)
                transfers += f.get();
            System.out.println(transfers + " transfers committed by 4 threads while an auditor read 40 snapshots.");
            System.out.println("Auditor saw an inconsistent total " + auditor.get() + " times (expected 0).");
            pool.shutdown();
            long total = 0;
            for (Object[] row : s.execute("SELECT id, bal FROM bank").rows)
                total += (Long) row[1];
            System.out.println("Final total across 8 accounts: " + total + " (started at 8000 - money is conserved).");

            section("11) Checkpoint + engine statistics");
            run(s, "CHECKPOINT");
            run(s, "SHOW STATS");

            db.close();
            System.out.println("\nDone. Try:  java -cp out minidb.Main repl   |   java -cp out minidb.Main bench");
        } finally {
            rm(dir);
            rm(dir2);
        }
    }

    static void repl(String dir) throws Exception {
        Database db = new Database(dir);
        QueryExecutor s = db.newSession();
        System.out.println("Mini Database Engine REPL - data directory: " + Paths.get(dir).toAbsolutePath());
        if (db.getLastRecovery().records > 0)
            System.out.println("Recovered from crash: " + db.getLastRecovery());
        System.out.println("Statements: CREATE TABLE | INSERT | SELECT | UPDATE | DELETE | BEGIN/COMMIT/ROLLBACK |");
        System.out.println("            EXPLAIN | CHECKPOINT | SHOW TABLES | SHOW STATS      ('exit' to quit)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print(s.inTransaction() ? "\nsql(txn)> " : "\nsql> ");
            String line = in.readLine();
            if (line == null || line.trim().equalsIgnoreCase("exit"))
                break;
            if (line.isBlank())
                continue;
            try {
                System.out.println(s.execute(line));
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
        s.close();
        db.close();
    }
}
