package minidb.engine;

import minidb.sql.ast.ColumnDef;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists table definitions (name | engine | columns) in a small text file,
 * updated atomically (write temp, fsync, rename) so tables survive restarts.
 */
public class Catalog {
    public static class Entry {
        public final String name, engine;
        public final List<ColumnDef> columns;

        public Entry(String name, String engine, List<ColumnDef> columns) {
            this.name = name;
            this.engine = engine;
            this.columns = columns;
        }
    }

    private final Path file;

    public Catalog(String dir) {
        this.file = Paths.get(dir, "catalog.meta");
    }

    public List<Entry> load() throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|");
            List<ColumnDef> cols = new ArrayList<>();
            for (String c : parts[2].split(",")) {
                String[] nt = c.split(":");
                cols.add(new ColumnDef(nt[0], ColumnDef.ColType.valueOf(nt[1])));
            }
            out.add(new Entry(parts[0], parts[1], cols));
        }
        return out;
    }

    public void save(List<Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.name).append('|').append(e.engine).append('|');
            for (int i = 0; i < e.columns.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(e.columns.get(i).name).append(':').append(e.columns.get(i).type);
            }
            sb.append('\n');
        }
        Path tmp = Paths.get(file.toString() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
