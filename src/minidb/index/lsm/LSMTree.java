package minidb.index.lsm;

import minidb.index.Index;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LSMTree implements Index {
    public static final int DEFAULT_MEMTABLE_THRESHOLD = 200;
    private static final int TIER_FANOUT = 4;
    private static final Pattern NAME = Pattern.compile("sstable_(\\d+)-(\\d+)_L(\\d+)\\.db");

    private final String dir;
    private final int memThreshold;
    private final Runnable walFlusher;
    private MemTable memTable = new MemTable();
    private final List<SSTable> tables = new ArrayList<>();
    private long nextSeq = 0;
    private long flushes, compactions;

    public LSMTree(String dir) throws Exception {
        this(dir, DEFAULT_MEMTABLE_THRESHOLD, null);
    }

    public LSMTree(String dir, int memtableThreshold, Runnable walFlusher) throws Exception {
        this.dir = dir;
        this.memThreshold = memtableThreshold;
        this.walFlusher = walFlusher;
        File d = new File(dir);
        d.mkdirs();
        File[] files = d.listFiles();
        if (files == null) files = new File[0];

        List<long[]> metas = new ArrayList<>();
        List<File> valid = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) {
                f.delete();
                continue;
            }
            Matcher m = NAME.matcher(f.getName());
            if (m.matches()) {
                metas.add(new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))});
                valid.add(f);
            }
        }
        for (int i = 0; i < valid.size(); i++) {
            boolean covered = false;
            for (int j = 0; j < valid.size() && !covered; j++) {
                if (i == j) continue;
                long[] a = metas.get(i), b = metas.get(j);
                boolean inside = b[0] <= a[0] && a[1] <= b[1];
                boolean identical = a[0] == b[0] && a[1] == b[1];
                covered = inside && (!identical || b[2] > a[2]);
            }
            if (covered) {
                valid.get(i).delete();
            } else {
                long[] a = metas.get(i);
                tables.add(SSTable.open(valid.get(i).getPath(), a[0], a[1], (int) a[2]));
                nextSeq = Math.max(nextSeq, a[1] + 1);
            }
        }
        tables.sort((x, y) -> Long.compare(y.maxSeq, x.maxSeq));
    }

    @Override
    public String engineName() {
        return "LSM-Tree";
    }

    private String path(long min, long max, int level) {
        return String.format("%s/sstable_%08d-%08d_L%d.db", dir, min, max, level);
    }

    @Override
    public synchronized void put(long key, byte[] value) throws Exception {
        if (value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Value too large (" + value.length + " > " + MAX_VALUE_BYTES + ")");
        }
        memTable.put(key, value);
        if (memTable.size() >= memThreshold) flushMemTable();
    }

    @Override
    public synchronized void delete(long key) throws Exception {
        memTable.delete(key);
        if (memTable.size() >= memThreshold) flushMemTable();
    }

    @Override
    public synchronized byte[] get(long key) throws Exception {
        if (memTable.containsKey(key)) return memTable.get(key);
        for (SSTable t : tables) {
            SSTable.Result r = t.get(key);
            if (r.present) return r.tombstone ? null : r.value;
        }
        return null;
    }

    @Override
    public synchronized List<Map.Entry<Long, byte[]>> scanRange(Long lo, boolean loInc, Long hi, boolean hiInc) throws Exception {
        TreeMap<Long, byte[]> merged = new TreeMap<>();
        for (int i = tables.size() - 1; i >= 0; i--) {
            for (Map.Entry<Long, byte[]> e : tables.get(i).rangeRaw(lo, loInc, hi, hiInc)) merged.put(e.getKey(), e.getValue());
        }
        merged.putAll(memTable.range(lo, loInc, hi, hiInc));
        List<Map.Entry<Long, byte[]>> out = new ArrayList<>();
        for (Map.Entry<Long, byte[]> e : merged.entrySet()) {
            if (e.getValue() != null) out.add(e);
        }
        return out;
    }

    @Override
    public synchronized void flush() throws Exception {
        flushMemTable();
    }

    private void flushMemTable() throws Exception {
        if (memTable.size() == 0) return;
        if (walFlusher != null) walFlusher.run();
        long seq = nextSeq++;
        SSTable t = SSTable.flush(path(seq, seq, 0), memTable.snapshot(), seq, seq, 0);
        tables.add(0, t);
        memTable = new MemTable();
        flushes++;
        compactIfNeeded();
    }

    private void compactIfNeeded() throws Exception {
        while (true) {
            Map<Integer, List<SSTable>> byLevel = new TreeMap<>();
            for (SSTable t : tables) byLevel.computeIfAbsent(t.level, k -> new ArrayList<>()).add(t);
            List<SSTable> group = null;
            for (List<SSTable> g : byLevel.values()) {
                if (g.size() >= TIER_FANOUT) {
                    group = g;
                    break;
                }
            }
            if (group == null) return;
            compact(group);
        }
    }

    private void compact(List<SSTable> group) throws Exception {
        boolean includesOldest = group.get(group.size() - 1) == tables.get(tables.size() - 1);
        TreeMap<Long, byte[]> merged = new TreeMap<>();
        for (int i = group.size() - 1; i >= 0; i--) {
            for (Map.Entry<Long, byte[]> e : group.get(i).entriesRaw()) merged.put(e.getKey(), e.getValue());
        }
        if (includesOldest) merged.values().removeIf(Objects::isNull);
        long min = group.get(group.size() - 1).minSeq, max = group.get(0).maxSeq;
        int level = group.get(0).level + 1;
        int insertAt = tables.indexOf(group.get(0));
        SSTable out = SSTable.flush(path(min, max, level), merged, min, max, level);
        tables.removeAll(group);
        for (SSTable old : group) old.deleteFile();
        if (merged.isEmpty()) {
            out.deleteFile();
        } else {
            tables.add(insertAt, out);
        }
        compactions++;
    }

    public synchronized int getSSTableCount() {
        return tables.size();
    }

    public synchronized int getMemTableSize() {
        return memTable.size();
    }

    public synchronized long getBloomRejects() {
        long n = 0;
        for (SSTable t : tables) n += t.bloomRejects;
        return n;
    }

    public synchronized long getCompactions() {
        return compactions;
    }

    public synchronized List<String> describeTables() {
        List<String> out = new ArrayList<>();
        for (SSTable t : tables) {
            out.add(new File(t.getPath()).getName() + "  (" + t.size() + " entries, level " + t.level + ")");
        }
        return out;
    }

    @Override
    public synchronized String stats() {
        Map<Integer, Integer> perLevel = new TreeMap<>();
        for (SSTable t : tables) perLevel.merge(t.level, 1, Integer::sum);
        long lookups = 0;
        for (SSTable t : tables) lookups += t.diskLookups;
        return String.format("LSM-Tree: memtable=%d entries, sstables=%d %s, flushes=%d, compactions=%d, bloom-skipped reads=%d, disk reads=%d",
                memTable.size(), tables.size(), perLevel, flushes, compactions, getBloomRejects(), lookups);
    }

    @Override
    public synchronized void close() throws Exception {
        flushMemTable();
        for (SSTable t : tables) t.close();
    }

    @Override
    public synchronized void simulateCrash() throws Exception {
        for (SSTable t : tables) t.close();
        memTable = new MemTable();
    }
}
