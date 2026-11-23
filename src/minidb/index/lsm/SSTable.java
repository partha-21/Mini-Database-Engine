package minidb.index.lsm;

import minidb.storage.DiskManager;
import minidb.storage.Page;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Immutable sorted run stored as fixed-size pages.
 *
 * Page: int numEntries, then (long key, int len, byte[] value) records;
 * len == -1 marks a tombstone. An entry never spans two pages.
 *
 * Crash safety: a new table is written to "<name>.tmp", fsync'd, then
 * atomically renamed - so a crash can never leave a half-written SSTable
 * visible under its real name.
 *
 * In memory we keep the key -> (page, offset) index and a bloom filter.
 */
public class SSTable {
    private final DiskManager disk;
    private final String path;
    private final long[] keys;
    private final long[] pageIds;
    private final int[] offsets;
    private final BloomFilter bloom;

    public final long minSeq, maxSeq;
    public final int level;
    public long bloomRejects, diskLookups;

    private SSTable(String path, DiskManager disk, long[] keys, long[] pageIds, int[] offsets,
                    long minSeq, long maxSeq, int level) {
        this.path = path;
        this.disk = disk;
        this.keys = keys;
        this.pageIds = pageIds;
        this.offsets = offsets;
        this.minSeq = minSeq;
        this.maxSeq = maxSeq;
        this.level = level;
        this.bloom = new BloomFilter(Math.max(1, keys.length), 10);
        for (long k : keys) bloom.add(k);
    }

    public String getPath() {
        return path;
    }

    public int size() {
        return keys.length;
    }

    public static SSTable flush(String finalPath, TreeMap<Long, byte[]> entries,
                                long minSeq, long maxSeq, int level) throws Exception {
        String tmp = finalPath + ".tmp";
        new File(tmp).delete();
        long[] keys = new long[entries.size()];
        long[] pageIds = new long[entries.size()];
        int[] offsets = new int[entries.size()];

        try (DiskManager w = new DiskManager(tmp)) {
            int idx = 0;
            long pid = w.allocatePage();
            Page page = new Page();
            ByteBuffer buf = page.buffer();
            buf.putInt(0);
            int count = 0;
            for (Map.Entry<Long, byte[]> e : entries.entrySet()) {
                byte[] v = e.getValue();
                int size = 8 + 4 + (v == null ? 0 : v.length);
                if (buf.position() + size > Page.PAGE_SIZE) {
                    buf.putInt(0, count);
                    w.writePage(pid, page);
                    pid = w.allocatePage();
                    page = new Page();
                    buf = page.buffer();
                    buf.putInt(0);
                    count = 0;
                }
                keys[idx] = e.getKey();
                pageIds[idx] = pid;
                offsets[idx] = buf.position();
                idx++;
                buf.putLong(e.getKey());
                if (v == null) {
                    buf.putInt(-1);
                } else {
                    buf.putInt(v.length);
                    buf.put(v);
                }
                count++;
            }
            buf.putInt(0, count);
            w.writePage(pid, page);
            w.fsync();
        }
        Files.move(new File(tmp).toPath(), new File(finalPath).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new SSTable(finalPath, new DiskManager(finalPath), keys, pageIds, offsets, minSeq, maxSeq, level);
    }

    public static SSTable open(String path, long minSeq, long maxSeq, int level) throws Exception {
        DiskManager disk = new DiskManager(path);
        List<Long> k = new ArrayList<>();
        List<Long> p = new ArrayList<>();
        List<Integer> o = new ArrayList<>();
        long pages = disk.getPageCount();
        for (long pg = 0; pg < pages; pg++) {
            ByteBuffer b = disk.readPage(pg).buffer();
            int n = b.getInt();
            for (int i = 0; i < n; i++) {
                o.add(b.position());
                k.add(b.getLong());
                p.add(pg);
                int len = b.getInt();
                if (len > 0) b.position(b.position() + len);
            }
        }
        return new SSTable(path, disk,
                k.stream().mapToLong(Long::longValue).toArray(),
                p.stream().mapToLong(Long::longValue).toArray(),
                o.stream().mapToInt(Integer::intValue).toArray(),
                minSeq, maxSeq, level);
    }

    /** Distinguishes "not in this table" from "deleted here" so reads can stop early. */
    public static class Result {
        public final boolean present, tombstone;
        public final byte[] value;

        Result(boolean present, boolean tombstone, byte[] value) {
            this.present = present;
            this.tombstone = tombstone;
            this.value = value;
        }
    }

    private static final Result NOT_FOUND = new Result(false, false, null);
    private static final Result TOMBSTONE = new Result(true, true, null);

    public Result get(long key) throws Exception {
        if (!bloom.mightContain(key)) {
            bloomRejects++;
            return NOT_FOUND;
        }
        int pos = Arrays.binarySearch(keys, key);
        if (pos < 0) return NOT_FOUND;
        diskLookups++;
        return readAt(pos, null);
    }

    private Result readAt(int pos, Map<Long, ByteBuffer> pageCache) throws Exception {
        ByteBuffer b = pageCache == null ? null : pageCache.get(pageIds[pos]);
        if (b == null) {
            b = disk.readPage(pageIds[pos]).buffer();
            if (pageCache != null) pageCache.put(pageIds[pos], b);
        }
        ByteBuffer dup = b.duplicate();
        dup.position(offsets[pos]);
        dup.getLong();
        int len = dup.getInt();
        if (len < 0) return TOMBSTONE;
        byte[] v = new byte[len];
        dup.get(v);
        return new Result(true, false, v);
    }

    /** Every entry (tombstones as null values), read page by page - used by compaction. */
    public List<Map.Entry<Long, byte[]>> entriesRaw() throws Exception {
        return rangeRaw(null, true, null, true);
    }

    public List<Map.Entry<Long, byte[]>> rangeRaw(Long lo, boolean loInc, Long hi, boolean hiInc) throws Exception {
        int start = 0;
        if (lo != null) {
            start = Arrays.binarySearch(keys, lo);
            if (start < 0) start = -start - 1;
            else if (!loInc) start++;
        }
        List<Map.Entry<Long, byte[]>> out = new ArrayList<>();
        Map<Long, ByteBuffer> pageCache = new HashMap<>();
        for (int i = start; i < keys.length; i++) {
            if (hi != null && (keys[i] > hi || (keys[i] == hi && !hiInc))) break;
            out.add(new AbstractMap.SimpleEntry<>(keys[i], readAt(i, pageCache).value));
        }
        return out;
    }

    public void close() throws Exception {
        disk.close();
    }

    public void deleteFile() throws Exception {
        disk.close();
        new File(path).delete();
    }
}
