package minidb.index;

import java.util.List;
import java.util.Map;

/**
 * Pluggable storage/index engine. A Table doesn't care whether it is backed
 * by a B+Tree or an LSM-Tree - like choosing InnoDB vs RocksDB in real systems.
 */
public interface Index {
    /** Largest value (encoded row) we accept. Keeps every page split provably possible. */
    int MAX_VALUE_BYTES = 1000;

    void put(long key, byte[] value) throws Exception;

    byte[] get(long key) throws Exception;

    void delete(long key) throws Exception;

    /** Live entries with lo <(=) key <(=) hi in ascending order; a null bound means unbounded. */
    List<Map.Entry<Long, byte[]>> scanRange(Long lo, boolean loInclusive, Long hi, boolean hiInclusive) throws Exception;

    default List<Map.Entry<Long, byte[]>> scanAll() throws Exception {
        return scanRange(null, true, null, true);
    }

    /** Make everything durable on the data files (used by checkpoints). */
    void flush() throws Exception;

    /** Test hook: drop all volatile state and close files WITHOUT flushing. */
    void simulateCrash() throws Exception;

    /** Flushes and releases files. */
    void close() throws Exception;

    String engineName();

    String stats();
}
