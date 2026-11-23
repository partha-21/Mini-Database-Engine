package minidb.index.btree;

import minidb.index.Index;
import minidb.storage.BufferPool;
import minidb.storage.Page;

import java.nio.ByteBuffer;
import java.util.*;

public class BPlusTree implements Index {
    public static final int DEFAULT_MAX_KEYS = 32;
    public static final int DEFAULT_POOL_PAGES = 1024;

    private static final byte LEAF = 0, INTERNAL = 1, FREE = 2;
    private static final int HEADER = 1 + 4 + 8;
    private static final int ENTRY_OVERHEAD = 8 + 4;
    private static final long META = 0;

    private final BufferPool pool;
    private int maxKeys;
    private int minKeys;
    private long rootPageId;
    private long freeListHead = -1;

    public BPlusTree(String path) throws Exception {
        this(path, DEFAULT_MAX_KEYS, DEFAULT_POOL_PAGES, null);
    }

    public BPlusTree(String path, int maxKeys, int poolPages, Runnable walFlusher) throws Exception {
        if (maxKeys < 3 || maxKeys > 200) throw new IllegalArgumentException("maxKeys must be in [3,200]");
        this.pool = new BufferPool(path, poolPages, walFlusher);
        boolean isNew = pool.pageCount() == 0;
        if (!isNew) {
            ByteBuffer b = pool.readPage(META).buffer();
            rootPageId = b.getLong();
            freeListHead = b.getLong();
            this.maxKeys = b.getInt();
            if (rootPageId <= 0 || this.maxKeys < 3 || this.maxKeys > 200) {
                isNew = true;
            } else {
                this.minKeys = this.maxKeys / 2;
            }
        }
        if (isNew) {
            this.maxKeys = maxKeys;
            this.minKeys = maxKeys / 2;
            if (pool.pageCount() == 0) pool.allocatePage();
            Node root = new Node(true);
            root.pageId = pool.allocatePage();
            writeNode(root);
            rootPageId = root.pageId;
            writeMetadata();
        }
    }

    @Override
    public String engineName() {
        return "B+Tree";
    }

    public BufferPool getPool() {
        return pool;
    }

    private void writeMetadata() throws Exception {
        Page p = new Page();
        ByteBuffer b = p.buffer();
        b.putLong(rootPageId);
        b.putLong(freeListHead);
        b.putInt(maxKeys);
        pool.writePage(META, p);
    }

    private static class Node {
        boolean isLeaf;
        long pageId;
        long nextLeafPageId = -1;
        List<Long> keys = new ArrayList<>();
        List<Long> children = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();

        Node(boolean isLeaf) {
            this.isLeaf = isLeaf;
        }
    }

    private Node readNode(long pageId) throws Exception {
        ByteBuffer b = pool.readPage(pageId).buffer();
        byte type = b.get();
        if (type == FREE) throw new IllegalStateException("Page " + pageId + " is on the free list");
        int n = b.getInt();
        long next = b.getLong();
        Node node = new Node(type == LEAF);
        node.pageId = pageId;
        node.nextLeafPageId = next;
        if (node.isLeaf) {
            for (int i = 0; i < n; i++) {
                long k = b.getLong();
                byte[] v = new byte[b.getInt()];
                b.get(v);
                node.keys.add(k);
                node.values.add(v);
            }
        } else {
            for (int i = 0; i < n; i++) node.keys.add(b.getLong());
            for (int i = 0; i <= n; i++) node.children.add(b.getLong());
        }
        return node;
    }

    private void writeNode(Node node) throws Exception {
        Page page = new Page();
        ByteBuffer b = page.buffer();
        b.put(node.isLeaf ? LEAF : INTERNAL);
        b.putInt(node.keys.size());
        b.putLong(node.nextLeafPageId);
        if (node.isLeaf) {
            for (int i = 0; i < node.keys.size(); i++) {
                b.putLong(node.keys.get(i));
                byte[] v = node.values.get(i);
                b.putInt(v.length);
                b.put(v);
            }
        } else {
            for (long k : node.keys) b.putLong(k);
            for (long c : node.children) b.putLong(c);
        }
        pool.writePage(node.pageId, page);
    }

    private int leafBytes(Node leaf) {
        int size = HEADER;
        for (byte[] v : leaf.values) size += ENTRY_OVERHEAD + v.length;
        return size;
    }

    private long allocNodePage() throws Exception {
        if (freeListHead != -1) {
            long id = freeListHead;
            ByteBuffer b = pool.readPage(id).buffer();
            b.get();
            b.getInt();
            freeListHead = b.getLong();
            writeMetadata();
            return id;
        }
        return pool.allocatePage();
    }

    private void freePage(long id) throws Exception {
        Page p = new Page();
        ByteBuffer b = p.buffer();
        b.put(FREE);
        b.putInt(0);
        b.putLong(freeListHead);
        pool.writePage(id, p);
        freeListHead = id;
        writeMetadata();
    }

    public synchronized int freePageCount() throws Exception {
        int n = 0;
        long id = freeListHead;
        Set<Long> visited = new HashSet<>();
        while (id != -1) {
            if (!visited.add(id)) {
                throw new IllegalStateException("Corrupt free page list: cycle detected at page " + id);
            }
            ByteBuffer b = pool.readPage(id).buffer();
            b.get();
            b.getInt();
            id = b.getLong();
            n++;
        }
        return n;
    }

    public synchronized long pageCount() {
        return pool.pageCount();
    }

    @Override
    public synchronized byte[] get(long key) throws Exception {
        Node node = findLeaf(key);
        int pos = Collections.binarySearch(node.keys, key);
        return pos >= 0 ? node.values.get(pos) : null;
    }

    @Override
    public synchronized void put(long key, byte[] value) throws Exception {
        if (value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Value too large (" + value.length + " > " + MAX_VALUE_BYTES + ")");
        }
        InsertResult r = insert(rootPageId, key, value);
        if (r != null) {
            Node newRoot = new Node(false);
            newRoot.pageId = allocNodePage();
            newRoot.keys.add(r.promotedKey);
            newRoot.children.add(rootPageId);
            newRoot.children.add(r.newRightPageId);
            writeNode(newRoot);
            rootPageId = newRoot.pageId;
            writeMetadata();
        }
    }

    @Override
    public synchronized void delete(long key) throws Exception {
        Node root = readNode(rootPageId);
        deleteRec(root, key);
        if (!root.isLeaf && root.keys.isEmpty()) {
            long old = rootPageId;
            rootPageId = root.children.get(0);
            freePage(old);
            writeMetadata();
        }
    }

    @Override
    public synchronized List<Map.Entry<Long, byte[]>> scanRange(Long lo, boolean loInc, Long hi, boolean hiInc) throws Exception {
        List<Map.Entry<Long, byte[]>> out = new ArrayList<>();
        Node node = lo == null ? leftmostLeaf() : findLeaf(lo);
        while (true) {
            for (int i = 0; i < node.keys.size(); i++) {
                long k = node.keys.get(i);
                if (lo != null && (k < lo || (k == lo && !loInc))) continue;
                if (hi != null && (k > hi || (k == hi && !hiInc))) return out;
                out.add(new AbstractMap.SimpleEntry<>(k, node.values.get(i)));
            }
            if (node.nextLeafPageId == -1) return out;
            node = readNode(node.nextLeafPageId);
        }
    }

    @Override
    public synchronized void flush() throws Exception {
        pool.flushAll();
    }

    @Override
    public synchronized void close() throws Exception {
        pool.close();
    }

    @Override
    public synchronized void simulateCrash() throws Exception {
        pool.crash();
    }

    @Override
    public synchronized String stats() {
        try {
            return String.format("B+Tree: height=%d, pages=%d (free=%d), maxKeys/node=%d; %s",
                    height(), pool.pageCount(), freePageCount(), maxKeys, pool.stats());
        } catch (Exception e) {
            return "B+Tree stats unavailable: " + e;
        }
    }

    private int findChildIndex(Node internal, long key) {
        int i = 0;
        while (i < internal.keys.size() && key >= internal.keys.get(i)) i++;
        return i;
    }

    private Node findLeaf(long key) throws Exception {
        Node node = readNode(rootPageId);
        while (!node.isLeaf) node = readNode(node.children.get(findChildIndex(node, key)));
        return node;
    }

    private Node leftmostLeaf() throws Exception {
        Node node = readNode(rootPageId);
        while (!node.isLeaf) node = readNode(node.children.get(0));
        return node;
    }

    public synchronized int height() throws Exception {
        int h = 1;
        Node node = readNode(rootPageId);
        while (!node.isLeaf) {
            node = readNode(node.children.get(0));
            h++;
        }
        return h;
    }

    private static class InsertResult {
        long promotedKey;
        long newRightPageId;
    }

    private int leafSplitPoint(Node leaf) {
        int n = leaf.keys.size();
        if (leafBytes(leaf) <= Page.PAGE_SIZE) return n / 2;
        int total = 0;
        for (byte[] v : leaf.values) total += ENTRY_OVERHEAD + v.length;
        int acc = 0, mid = n / 2;
        for (int i = 0; i < n; i++) {
            acc += ENTRY_OVERHEAD + leaf.values.get(i).length;
            if (acc * 2 >= total) {
                mid = i + 1;
                break;
            }
        }
        return Math.max(1, Math.min(n - 1, mid));
    }

    private InsertResult insert(long pageId, long key, byte[] value) throws Exception {
        Node node = readNode(pageId);
        if (node.isLeaf) {
            int pos = Collections.binarySearch(node.keys, key);
            if (pos >= 0) {
                node.values.set(pos, value);
            } else {
                int at = -pos - 1;
                node.keys.add(at, key);
                node.values.add(at, value);
            }
            if (node.keys.size() <= maxKeys && leafBytes(node) <= Page.PAGE_SIZE) {
                writeNode(node);
                return null;
            }
            int mid = leafSplitPoint(node);
            Node right = new Node(true);
            right.pageId = allocNodePage();
            right.keys.addAll(node.keys.subList(mid, node.keys.size()));
            right.values.addAll(node.values.subList(mid, node.values.size()));
            right.nextLeafPageId = node.nextLeafPageId;
            node.keys = new ArrayList<>(node.keys.subList(0, mid));
            node.values = new ArrayList<>(node.values.subList(0, mid));
            node.nextLeafPageId = right.pageId;
            writeNode(node);
            writeNode(right);
            InsertResult res = new InsertResult();
            res.promotedKey = right.keys.get(0);
            res.newRightPageId = right.pageId;
            return res;
        }

        int idx = findChildIndex(node, key);
        InsertResult child = insert(node.children.get(idx), key, value);
        if (child == null) return null;
        node.keys.add(idx, child.promotedKey);
        node.children.add(idx + 1, child.newRightPageId);
        if (node.keys.size() <= maxKeys) {
            writeNode(node);
            return null;
        }
        int mid = node.keys.size() / 2;
        long middle = node.keys.get(mid);
        Node right = new Node(false);
        right.pageId = allocNodePage();
        right.keys.addAll(node.keys.subList(mid + 1, node.keys.size()));
        right.children.addAll(node.children.subList(mid + 1, node.children.size()));
        node.keys = new ArrayList<>(node.keys.subList(0, mid));
        node.children = new ArrayList<>(node.children.subList(0, mid + 1));
        writeNode(node);
        writeNode(right);
        InsertResult res = new InsertResult();
        res.promotedKey = middle;
        res.newRightPageId = right.pageId;
        return res;
    }

    private boolean deleteRec(Node node, long key) throws Exception {
        if (node.isLeaf) {
            int pos = Collections.binarySearch(node.keys, key);
            if (pos < 0) return false;
            node.keys.remove(pos);
            node.values.remove(pos);
            writeNode(node);
            return node.keys.size() < minKeys;
        }
        int idx = findChildIndex(node, key);
        Node child = readNode(node.children.get(idx));
        if (!deleteRec(child, key)) return false;
        Node left = idx > 0 ? readNode(node.children.get(idx - 1)) : null;
        Node right = idx < node.children.size() - 1 ? readNode(node.children.get(idx + 1)) : null;
        if (child.isLeaf) rebalanceLeaf(node, idx, child, left, right);
        else rebalanceInternal(node, idx, child, left, right);
        return node.keys.size() < minKeys;
    }

    private void rebalanceLeaf(Node parent, int idx, Node child, Node left, Node right) throws Exception {
        if (left != null && left.keys.size() > minKeys) {
            int last = left.keys.size() - 1;
            if (leafBytes(child) + ENTRY_OVERHEAD + left.values.get(last).length <= Page.PAGE_SIZE) {
                child.keys.add(0, left.keys.remove(last));
                child.values.add(0, left.values.remove(last));
                parent.keys.set(idx - 1, child.keys.get(0));
                writeNode(left);
                writeNode(child);
                writeNode(parent);
                return;
            }
        }
        if (right != null && right.keys.size() > minKeys) {
            if (leafBytes(child) + ENTRY_OVERHEAD + right.values.get(0).length <= Page.PAGE_SIZE) {
                child.keys.add(right.keys.remove(0));
                child.values.add(right.values.remove(0));
                parent.keys.set(idx, right.keys.get(0));
                writeNode(right);
                writeNode(child);
                writeNode(parent);
                return;
            }
        }
        if (left != null && left.keys.size() + child.keys.size() <= maxKeys
                && leafBytes(left) + leafBytes(child) - HEADER <= Page.PAGE_SIZE) {
            left.keys.addAll(child.keys);
            left.values.addAll(child.values);
            left.nextLeafPageId = child.nextLeafPageId;
            parent.keys.remove(idx - 1);
            parent.children.remove(idx);
            writeNode(left);
            writeNode(parent);
            freePage(child.pageId);
            return;
        }
        if (right != null && right.keys.size() + child.keys.size() <= maxKeys
                && leafBytes(right) + leafBytes(child) - HEADER <= Page.PAGE_SIZE) {
            child.keys.addAll(right.keys);
            child.values.addAll(right.values);
            child.nextLeafPageId = right.nextLeafPageId;
            parent.keys.remove(idx);
            parent.children.remove(idx + 1);
            writeNode(child);
            writeNode(parent);
            freePage(right.pageId);
        }
    }

    private void rebalanceInternal(Node parent, int idx, Node child, Node left, Node right) throws Exception {
        if (left != null && left.keys.size() > minKeys) {
            child.keys.add(0, parent.keys.get(idx - 1));
            child.children.add(0, left.children.remove(left.children.size() - 1));
            parent.keys.set(idx - 1, left.keys.remove(left.keys.size() - 1));
            writeNode(left);
            writeNode(child);
            writeNode(parent);
        } else if (right != null && right.keys.size() > minKeys) {
            child.keys.add(parent.keys.get(idx));
            child.children.add(right.children.remove(0));
            parent.keys.set(idx, right.keys.remove(0));
            writeNode(right);
            writeNode(child);
            writeNode(parent);
        } else if (left != null) {
            left.keys.add(parent.keys.remove(idx - 1));
            left.keys.addAll(child.keys);
            left.children.addAll(child.children);
            parent.children.remove(idx);
            writeNode(left);
            writeNode(parent);
            freePage(child.pageId);
        } else if (right != null) {
            child.keys.add(parent.keys.remove(idx));
            child.keys.addAll(right.keys);
            child.children.addAll(right.children);
            parent.children.remove(idx + 1);
            writeNode(child);
            writeNode(parent);
            freePage(right.pageId);
        }
    }

    public synchronized long checkInvariants() throws Exception {
        List<Long> leafOrder = new ArrayList<>();
        List<Long> allKeys = new ArrayList<>();
        int[] leafDepth = {-1};
        checkNode(rootPageId, null, null, 0, leafDepth, leafOrder, allKeys);

        long id = leftmostLeaf().pageId;
        for (long expected : leafOrder) {
            if (id != expected) throw new IllegalStateException("Leaf chain mismatch: expected page " + expected + " got " + id);
            id = readNode(id).nextLeafPageId;
        }
        if (id != -1) throw new IllegalStateException("Leaf chain continues past the last leaf");
        for (int i = 1; i < allKeys.size(); i++) {
            if (allKeys.get(i - 1) >= allKeys.get(i)) throw new IllegalStateException("Keys not globally sorted");
        }
        return allKeys.size();
    }

    private void checkNode(long pageId, Long lo, Long hi, int depth, int[] leafDepth,
                           List<Long> leafOrder, List<Long> allKeys) throws Exception {
        Node n = readNode(pageId);
        for (int i = 0; i < n.keys.size(); i++) {
            long k = n.keys.get(i);
            if (i > 0 && n.keys.get(i - 1) >= k) throw new IllegalStateException("Unsorted keys in page " + pageId);
            if (lo != null && k < lo) throw new IllegalStateException("Key " + k + " below bound in page " + pageId);
            if (hi != null && k >= hi) throw new IllegalStateException("Key " + k + " above bound in page " + pageId);
        }
        if (n.keys.size() > maxKeys) throw new IllegalStateException("Overfull node " + pageId);
        if (n.isLeaf) {
            if (leafDepth[0] == -1) leafDepth[0] = depth;
            else if (leafDepth[0] != depth) throw new IllegalStateException("Leaves at different depths");
            if (leafBytes(n) > Page.PAGE_SIZE) throw new IllegalStateException("Leaf exceeds page size");
            leafOrder.add(pageId);
            allKeys.addAll(n.keys);
        } else {
            if (n.children.size() != n.keys.size() + 1) throw new IllegalStateException("Bad child count in page " + pageId);
            for (int i = 0; i < n.children.size(); i++) {
                Long clo = i == 0 ? lo : n.keys.get(i - 1);
                Long chi = i == n.keys.size() ? hi : n.keys.get(i);
                checkNode(n.children.get(i), clo, chi, depth + 1, leafDepth, leafOrder, allKeys);
            }
        }
    }

    public synchronized void printTree() throws Exception {
        System.out.println("B+Tree (root=page " + rootPageId + ", height=" + height() + ", maxKeys=" + maxKeys + ")");
        printNode(rootPageId, 0);
    }

    private void printNode(long pageId, int depth) throws Exception {
        Node n = readNode(pageId);
        String indent = "  ".repeat(depth);
        if (n.isLeaf) {
            System.out.println(indent + "Leaf[p" + pageId + "] " + n.keys);
        } else {
            System.out.println(indent + "Internal[p" + pageId + "] " + n.keys);
            for (long c : n.children) printNode(c, depth + 1);
        }
    }
}
