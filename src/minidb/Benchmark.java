package minidb;

import minidb.engine.Database;
import minidb.engine.QueryExecutor;
import minidb.index.Index;
import minidb.index.btree.BPlusTree;
import minidb.index.lsm.LSMTree;

import java.nio.file.*;
import java.util.*;

/** B+Tree vs LSM-Tree micro-benchmark on the raw Index interface, plus the cost of durability. */
public class Benchmark {
    interface Work { void run() throws Exception; }

    static double ms(Work w) throws Exception {
        long t0 = System.nanoTime();
        w.run();
        return (System.nanoTime() - t0) / 1e6;
    }

    static String rate(int ops, double ms) {
        return String.format("%,9.0f ms %,12.0f ops/s", ms, ops / (ms / 1000.0));
    }

    static void rm(Path p) {
        try {
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    static Index open(String engine, Path dir) throws Exception {
        return engine.equals("B+Tree")
                ? new BPlusTree(dir.resolve("b.btree").toString())
                : new LSMTree(dir.resolve("lsm").toString(), 5000, null);
    }

    public static void run(int n) throws Exception {
        System.out.println("Benchmark: " + String.format("%,d", n) + " keys, 40-byte values (raw index, no WAL)\n");
        byte[] val = new byte[40];
        List<Long> shuffled = new ArrayList<>();
        for (long i = 0; i < n; i++) shuffled.add(i);
        Collections.shuffle(shuffled, new Random(1));
        Random rnd = new Random(2);

        Map<String, String[]> rows = new LinkedHashMap<>();
        for (String engine : new String[]{"B+Tree", "LSM-Tree"}) {
            int col = engine.equals("B+Tree") ? 0 : 1;
            Path d = Files.createTempDirectory("bench");
            try {
                Index idx = open(engine, d);
                double seq = ms(() -> { for (long i = 0; i < n; i++) idx.put(i, val); });
                put(rows, "insert, sequential keys", col, rate(n, seq));
                idx.close();
                rm(d);

                d = Files.createTempDirectory("bench");
                Index idx2 = open(engine, d);
                double rand = ms(() -> { for (long k : shuffled) idx2.put(k, val); });
                put(rows, "insert, random keys", col, rate(n, rand));
                double fl = ms(idx2::flush);
                put(rows, "flush / checkpoint to disk", col, String.format("%,9.0f ms", fl));

                int reads = n / 2;
                double hit = ms(() -> { for (int i = 0; i < reads; i++) idx2.get(rnd.nextInt(n)); });
                put(rows, "point read, key exists", col, rate(reads, hit));
                double miss = ms(() -> { for (int i = 0; i < reads; i++) idx2.get(n + rnd.nextInt(n)); });
                put(rows, "point read, key absent", col, rate(reads, miss));
                double range = ms(() -> {
                    for (int i = 0; i < 200; i++) {
                        long lo = rnd.nextInt(n - 100);
                        idx2.scanRange(lo, true, lo + 100, false);
                    }
                });
                put(rows, "range scan (200 x 100 keys)", col, rate(200, range));
                double full = ms(() -> idx2.scanAll());
                put(rows, "full scan of all keys", col, String.format("%,9.0f ms", full));
                System.out.println("  [" + engine + "] " + idx2.stats());
                idx2.close();
            } finally {
                rm(d);
            }
        }
        System.out.println();
        System.out.printf("%-34s | %-30s | %-30s%n", "workload", "B+Tree", "LSM-Tree");
        System.out.println("-".repeat(34) + "-+-" + "-".repeat(30) + "-+-" + "-".repeat(30));
        for (Map.Entry<String, String[]> e : rows.entrySet()) {
            System.out.printf("%-34s | %-30s | %-30s%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        System.out.println("\nThe cost of durability (full SQL path, WAL fsync on every commit), 300 inserts:");
        Path d = Files.createTempDirectory("bench");
        try (Database db = new Database(d.toString())) {
            QueryExecutor s = db.newSession();
            s.execute("CREATE TABLE t (id INT, v TEXT) USING BTREE");
            double auto = ms(() -> { for (int i = 0; i < 300; i++) s.execute("INSERT INTO t VALUES (" + i + ", 'x')"); });
            double batch = ms(() -> {
                s.execute("BEGIN");
                for (int i = 300; i < 600; i++) s.execute("INSERT INTO t VALUES (" + i + ", 'x')");
                s.execute("COMMIT");
            });
            System.out.printf("  autocommit (300 fsyncs):      %,8.0f ms%n", auto);
            System.out.printf("  one transaction (1 fsync):    %,8.0f ms   -> %.0fx faster%n", batch, auto / Math.max(batch, 0.001));
        } finally {
            rm(d);
        }
    }

    private static void put(Map<String, String[]> rows, String key, int col, String v) {
        rows.computeIfAbsent(key, k -> new String[2])[col] = v;
    }

    public static void main(String[] args) throws Exception {
        run(args.length > 0 ? Integer.parseInt(args[0]) : 30000);
    }
}
