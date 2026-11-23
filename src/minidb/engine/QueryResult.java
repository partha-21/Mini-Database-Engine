package minidb.engine;

import java.util.List;

/** Result of one statement: either a message, or a table of rows (plus the chosen access path). */
public class QueryResult {
    public final List<String> columns; // null for message-only results
    public final List<Object[]> rows;
    public final String message;
    public final String plan;

    private QueryResult(List<String> columns, List<Object[]> rows, String message, String plan) {
        this.columns = columns;
        this.rows = rows;
        this.message = message;
        this.plan = plan;
    }

    public static QueryResult message(String m) {
        return new QueryResult(null, List.of(), m, null);
    }

    public static QueryResult message(String m, String plan) {
        return new QueryResult(null, List.of(), m, plan);
    }

    public static QueryResult rows(List<String> cols, List<Object[]> rows, String plan) {
        return new QueryResult(cols, rows, null, plan);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (plan != null) sb.append("[plan: ").append(plan).append("]\n");
        if (columns == null) return sb.append(message).toString();
        int[] w = new int[columns.size()];
        for (int i = 0; i < w.length; i++) w[i] = columns.get(i).length();
        for (Object[] r : rows) {
            for (int i = 0; i < w.length; i++) w[i] = Math.max(w[i], String.valueOf(r[i]).length());
        }
        appendRow(sb, w, columns.toArray());
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < w.length; i++) sep.append(i == 0 ? "" : "-+-").append("-".repeat(w[i]));
        sb.append(sep).append('\n');
        for (Object[] r : rows) appendRow(sb, w, r);
        sb.append('(').append(rows.size()).append(rows.size() == 1 ? " row)" : " rows)");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, int[] w, Object[] cells) {
        for (int i = 0; i < w.length; i++) {
            String s = String.valueOf(cells[i]);
            sb.append(i == 0 ? "" : " | ").append(s).append(" ".repeat(w[i] - s.length()));
        }
        sb.append('\n');
    }
}
