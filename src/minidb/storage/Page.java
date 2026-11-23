package minidb.storage;

import java.nio.ByteBuffer;

/**
 * A fixed-size 4KB page - the atomic unit of disk I/O in the engine.
 * Every structure (B+Tree nodes, SSTable blocks) is laid out inside pages
 * rather than being naively Java-serialized, like SQLite/Postgres/InnoDB.
 */
public class Page {
    public static final int PAGE_SIZE = 4096;

    private final byte[] data;

    public Page() {
        this.data = new byte[PAGE_SIZE];
    }

    public Page(byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("Page must be exactly " + PAGE_SIZE + " bytes");
        }
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    public ByteBuffer buffer() {
        return ByteBuffer.wrap(data);
    }
}
