package minidb.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * Treats one file as an array of fixed-size pages addressed by pageId.
 * Page ids are handed out by allocatePage() without touching the disk;
 * the file only grows when a page is actually written.
 */
public class DiskManager implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final String path;
    private long nextPageId;

    public DiskManager(String filePath) throws IOException {
        this.path = filePath;
        this.file = new RandomAccessFile(filePath, "rw");
        this.channel = file.getChannel();
        this.nextPageId = (file.length() + Page.PAGE_SIZE - 1) / Page.PAGE_SIZE;
    }

    public String getPath() {
        return path;
    }

    public synchronized boolean isNew() {
        return nextPageId == 0;
    }

    public synchronized long allocatePage() {
        return nextPageId++;
    }

    public synchronized Page readPage(long pageId) throws IOException {
        long offset = pageId * Page.PAGE_SIZE;
        if (offset + Page.PAGE_SIZE > file.length()) {
            return new Page();
        }
        byte[] buf = new byte[Page.PAGE_SIZE];
        file.seek(offset);
        file.readFully(buf);
        return new Page(buf);
    }

    public synchronized void writePage(long pageId, Page page) throws IOException {
        file.seek(pageId * Page.PAGE_SIZE);
        file.write(page.getData());
        if (pageId >= nextPageId) nextPageId = pageId + 1;
    }

    /** Force written pages to physical media. */
    public synchronized void fsync() throws IOException {
        channel.force(true);
    }

    public synchronized long getPageCount() {
        return nextPageId;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
        file.close();
    }
}
