package minidb.index.lsm;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory sorted write buffer. A null value is a tombstone (logical delete):
 * it must be flushed like any other write so it can shadow older SSTables.
 */
public class MemTable {
    private final TreeMap<Long, byte[]> map = new TreeMap<>();

    public void put(long key, byte[] value) {
        map.put(key, value);
    }

    public void delete(long key) {
        map.put(key, null);
    }

    public boolean containsKey(long key) {
        return map.containsKey(key);
    }

    public byte[] get(long key) {
        return map.get(key);
    }

    public int size() {
        return map.size();
    }

    public TreeMap<Long, byte[]> snapshot() {
        return map;
    }

    public NavigableMap<Long, byte[]> range(Long lo, boolean loInc, Long hi, boolean hiInc) {
        if (lo != null && hi != null && (lo > hi || (lo.equals(hi) && !(loInc && hiInc)))) {
            return new TreeMap<>(); // empty/contradictory range (TreeMap would throw)
        }
        NavigableMap<Long, byte[]> m = map;
        if (lo != null) m = m.tailMap(lo, loInc);
        if (hi != null) m = m.headMap(hi, hiInc);
        return m;
    }
}
