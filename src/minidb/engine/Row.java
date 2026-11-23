package minidb.engine;

import minidb.sql.ast.ColumnDef;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Row {
    public static byte[] encode(List<ColumnDef> schema, List<Object> values) {
        byte[][] texts = new byte[schema.size()][];
        int size = 0;
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).type == ColumnDef.ColType.INT) {
                size += 8;
            } else {
                texts[i] = String.valueOf(values.get(i)).getBytes(StandardCharsets.UTF_8);
                size += 4 + texts[i].length;
            }
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).type == ColumnDef.ColType.INT) {
                b.putLong(((Number) values.get(i)).longValue());
            } else {
                b.putInt(texts[i].length);
                b.put(texts[i]);
            }
        }
        return b.array();
    }

    public static Object[] decode(List<ColumnDef> schema, byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data);
        Object[] out = new Object[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).type == ColumnDef.ColType.INT) {
                if (b.remaining() < 8) throw new IllegalArgumentException("Corrupt row: insufficient bytes for INT column " + schema.get(i).name);
                out[i] = b.getLong();
            } else {
                if (b.remaining() < 4) throw new IllegalArgumentException("Corrupt row: insufficient bytes for TEXT length in column " + schema.get(i).name);
                int len = b.getInt();
                if (len < 0 || len > b.remaining()) throw new IllegalArgumentException("Corrupt row: invalid string length " + len + " for column " + schema.get(i).name);
                byte[] t = new byte[len];
                b.get(t);
                out[i] = new String(t, StandardCharsets.UTF_8);
            }
        }
        return out;
    }
}
