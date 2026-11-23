package minidb.index.lsm;

/**
 * Space-efficient probabilistic set. mightContain() never gives a false
 * negative; with 10 bits/key the false-positive rate is about 1%.
 * Each SSTable keeps one so a lookup for an absent key usually skips
 * the disk read entirely.
 */
public class BloomFilter {
    private final long[] bits;
    private final int numBits;
    private final int hashes;

    public BloomFilter(int expectedKeys, int bitsPerKey) {
        this.numBits = Math.max(64, expectedKeys * bitsPerKey);
        this.bits = new long[(numBits + 63) / 64];
        this.hashes = Math.max(1, (int) Math.round(bitsPerKey * 0.69));
    }

    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }

    public void add(long key) {
        long h1 = mix(key);
        long h2 = mix(h1 ^ 0x9E3779B97F4A7C15L) | 1L;
        for (int i = 0; i < hashes; i++) {
            int idx = (int) Math.floorMod(h1 + i * h2, (long) numBits);
            bits[idx >>> 6] |= 1L << (idx & 63);
        }
    }

    public boolean mightContain(long key) {
        long h1 = mix(key);
        long h2 = mix(h1 ^ 0x9E3779B97F4A7C15L) | 1L;
        for (int i = 0; i < hashes; i++) {
            int idx = (int) Math.floorMod(h1 + i * h2, (long) numBits);
            if ((bits[idx >>> 6] & (1L << (idx & 63))) == 0) return false;
        }
        return true;
    }
}
