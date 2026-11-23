package minidb.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;

/**
 * LRU page cache sitting between the B+Tree and the DiskManager.
 *
 * Policy: NO-STEAL / NO-FORCE.
 *  - no-steal: a dirty page is never written back on eviction, so the data
 *    file only ever contains committed state as of the last checkpoint.
 *  - no-force: commit does not write data pages; durability comes from the WAL.
 *
 * Checkpoint (flushAll) is made crash-atomic with a page journal
 * (a "double-write" file, like SQLite's journal / InnoDB doublewrite):
 *   1. force the WAL            (write-ahead rule)
 *   2. write all dirty pages + CRC + commit marker to <file>.dwb, fsync it
 *   3. write the pages in place, fsync the data file
 *   4. delete the journal
 * On open, a complete journal is re-applied (crash during step 3); an
 * incomplete/corrupt journal is discarded (crash during step 2 - the data
 * file is still the previous consistent checkpoint).
 */
public class BufferPool implements AutoCloseable {
    private static final int JOURNAL_MAGIC = 0x4D44424A;
    private static final int COMMIT_MAGIC = 0x434F4D54;

    /** Fault injection used by the test-suite to simulate a crash mid-checkpoint. */
    public enum Fault { NONE, AFTER_JOURNAL, MID_WRITE }
    public volatile Fault fault = Fault.NONE;

    private static class Frame {
        byte[] data;
        boolean dirty;
        Frame(byte[] data, boolean dirty) {
            this.data = data;
            this.dirty = dirty;
        }
    }

    private final DiskManager disk;
    private final String journalPath;
    private final int capacity;
    private final Runnable walFlusher;
    private final LinkedHashMap<Long, Frame> cache = new LinkedHashMap<>(256, 0.75f, true);
    private long hits, misses, checkpoints;

    public BufferPool(String path, int capacityPages, Runnable walFlusher) throws IOException {
        this.journalPath = path + ".dwb";
        this.disk = new DiskManager(path);
        this.capacity = capacityPages;
        this.walFlusher = walFlusher;
        recoverJournal();
    }

    private void recoverJournal() throws IOException {
        File j = new File(journalPath);
        if (!j.exists()) return;
        byte[] all = Files.readAllBytes(j.toPath());
        if (all.length >= 16) {
            ByteBuffer b = ByteBuffer.wrap(all);
            int magic = b.getInt();
            int count = b.getInt();
            long expected = 8L + (long) count * (8 + Page.PAGE_SIZE) + 8;
            if (magic == JOURNAL_MAGIC && count >= 0 && all.length == expected) {
                CRC32 crc = new CRC32();
                crc.update(all, 0, (int) (expected - 8));
                ByteBuffer trailer = ByteBuffer.wrap(all, (int) (expected - 8), 8);
                int storedCrc = trailer.getInt();
                int commit = trailer.getInt();
                if (commit == COMMIT_MAGIC && storedCrc == (int) crc.getValue()) {
                    for (int i = 0; i < count; i++) {
                        long pageId = b.getLong();
                        byte[] d = new byte[Page.PAGE_SIZE];
                        b.get(d);
                        disk.writePage(pageId, new Page(d));
                    }
                    disk.fsync();
                }
            }
        }
        j.delete();
    }

    public synchronized Page readPage(long pageId) throws IOException {
        Frame f = cache.get(pageId);
        if (f != null) {
            hits++;
            return new Page(f.data.clone());
        }
        misses++;
        byte[] d = disk.readPage(pageId).getData();
        cache.put(pageId, new Frame(d, false));
        evict();
        return new Page(d.clone());
    }

    public synchronized void writePage(long pageId, Page page) throws IOException {
        Frame f = cache.get(pageId);
        if (f == null) {
            cache.put(pageId, new Frame(page.getData().clone(), true));
        } else {
            f.data = page.getData().clone();
            f.dirty = true;
        }
        evict();
    }

    public synchronized long allocatePage() {
        return disk.allocatePage();
    }

    public synchronized long pageCount() {
        return disk.getPageCount();
    }

    private void evict() {
        if (cache.size() <= capacity) return;
        Iterator<Map.Entry<Long, Frame>> it = cache.entrySet().iterator();
        while (cache.size() > capacity && it.hasNext()) {
            Map.Entry<Long, Frame> e = it.next();
            if (!e.getValue().dirty) it.remove();
        }
    }

    /** Crash-atomic checkpoint of all dirty pages. */
    public synchronized void flushAll() throws IOException {
        List<Map.Entry<Long, Frame>> dirty = new ArrayList<>();
        for (Map.Entry<Long, Frame> e : cache.entrySet()) {
            if (e.getValue().dirty) dirty.add(e);
        }
        if (dirty.isEmpty()) return;
        dirty.sort(Map.Entry.comparingByKey());

        if (walFlusher != null) walFlusher.run();

        CRC32 crc = new CRC32();
        try (FileOutputStream fos = new FileOutputStream(journalPath);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 16)) {
            byte[] hdr = ByteBuffer.allocate(8).putInt(JOURNAL_MAGIC).putInt(dirty.size()).array();
            bos.write(hdr);
            crc.update(hdr);
            for (Map.Entry<Long, Frame> e : dirty) {
                byte[] id = ByteBuffer.allocate(8).putLong(e.getKey()).array();
                bos.write(id);
                crc.update(id);
                bos.write(e.getValue().data);
                crc.update(e.getValue().data);
            }
            bos.write(ByteBuffer.allocate(8).putInt((int) crc.getValue()).putInt(COMMIT_MAGIC).array());
            bos.flush();
            fos.getFD().sync();
        }
        if (fault == Fault.AFTER_JOURNAL) throw new IOException("injected fault: crash after journal sync");

        int written = 0;
        for (Map.Entry<Long, Frame> e : dirty) {
            disk.writePage(e.getKey(), new Page(e.getValue().data));
            written++;
            if (fault == Fault.MID_WRITE && written == Math.max(1, dirty.size() / 2)) {
                throw new IOException("injected fault: crash mid in-place write");
            }
        }
        disk.fsync();
        File jFile = new File(journalPath);
        if (!jFile.delete()) {
            try (RandomAccessFile raf = new RandomAccessFile(jFile, "rw")) {
                raf.setLength(0);
            } catch (Exception ignored) {
            }
            jFile.delete();
        }
        for (Map.Entry<Long, Frame> e : dirty) e.getValue().dirty = false;
        checkpoints++;
        evict();
    }

    public synchronized String stats() {
        long total = hits + misses;
        return String.format("buffer pool: %d pages cached, hit rate %.1f%% (%d hits / %d misses), %d checkpoints",
                cache.size(), total == 0 ? 0.0 : 100.0 * hits / total, hits, misses, checkpoints);
    }

    @Override
    public synchronized void close() throws IOException {
        flushAll();
        disk.close();
    }

    /** Simulated crash: drop every cached page (dirty ones are lost) and close the file. */
    public synchronized void crash() throws IOException {
        cache.clear();
        disk.close();
    }
}
