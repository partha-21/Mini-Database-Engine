package minidb.engine;

import minidb.index.Index;
import minidb.index.btree.BPlusTree;
import minidb.index.lsm.LSMTree;
import minidb.sql.ast.ColumnDef;
import minidb.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A table = schema + one pluggable index engine. Column 0 is the INT primary
 * key and doubles as the index key. Enforces: types, row size, unique key.
 */
public class Table {
    private final String name;
    private final List<ColumnDef> columns;
    private final String engine;
    private final Index index;

    public Table(String name, List<ColumnDef> columns, String engine, String dataDir,
                 Database.Options opts, Runnable walFlusher) throws Exception {
        this.name = name;
        this.columns = columns;
        this.engine = engine;
        if (engine.equals("LSM")) {
            this.index = new LSMTree(dataDir + "/" + name + ".lsm", opts.lsmMemtableThreshold, walFlusher);
        } else {
            this.index = new BPlusTree(dataDir + "/" + name + ".btree", opts.btreeMaxKeys, opts.btreePoolPages, walFlusher);
        }
    }

    public String getName() {
        return name;
    }

    public List<ColumnDef> getColumns() {
        return columns;
    }

    public String getEngineName() {
        return engine;
    }

    public Index getIndex() {
        return index;
    }

    public String pkName() {
        return columns.get(0).name;
    }

    public int columnIndex(String col) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(col)) return i;
        }
        throw new RuntimeException("No such column '" + col + "' in table " + name);
    }

    /** Validates a literal against a column's type and normalizes it (INT -> Long, TEXT -> String). */
    public Object coerceValue(int colIdx, Object v) {
        ColumnDef c = columns.get(colIdx);
        if (c.type == ColumnDef.ColType.INT) {
            if (v instanceof Long) return v;
            throw new RuntimeException("Type mismatch: column '" + c.name + "' is INT but got '" + v + "'");
        }
        if (v instanceof String) return v;
        throw new RuntimeException("Type mismatch: column '" + c.name + "' is TEXT but got " + v + " (quote text values)");
    }

    private byte[] encodeChecked(List<Object> values) {
        byte[] enc = Row.encode(columns, values);
        if (enc.length > Index.MAX_VALUE_BYTES) {
            throw new RuntimeException("Row too large: " + enc.length + " bytes (max " + Index.MAX_VALUE_BYTES + ")");
        }
        return enc;
    }

    public void insert(Transaction tx, List<Object> values) throws Exception {
        if (values.size() != columns.size()) {
            throw new RuntimeException("Table " + name + " has " + columns.size() + " columns but " + values.size() + " values were given");
        }
        List<Object> v = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) v.add(coerceValue(i, values.get(i)));
        long key = (Long) v.get(0);
        byte[] enc = encodeChecked(v);
        if (index.get(key) != null) {
            throw new RuntimeException("Duplicate primary key " + key + " in table " + name);
        }
        tx.put(index, name, key, enc);
    }

    /** Replace an existing row (same primary key). */
    public void replace(Transaction tx, Object[] newRow) throws Exception {
        List<Object> v = new ArrayList<>(List.of(newRow));
        tx.put(index, name, (Long) newRow[0], encodeChecked(v));
    }

    public void deleteByKey(Transaction tx, long key) throws Exception {
        tx.delete(index, name, key);
    }

    public Object[] getByKey(long key) throws Exception {
        byte[] d = index.get(key);
        return d == null ? null : Row.decode(columns, d);
    }

    public List<Object[]> scanRange(Long lo, boolean loInc, Long hi, boolean hiInc) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<Long, byte[]> e : index.scanRange(lo, loInc, hi, hiInc)) rows.add(Row.decode(columns, e.getValue()));
        return rows;
    }

    public void close() throws Exception {
        index.close();
    }
}
