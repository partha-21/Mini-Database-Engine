package minidb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import minidb.engine.*;
import minidb.sql.ast.ColumnDef;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server for MiniDB providing an interactive browser-based SQL Studio & REST API.
 * Features:
 *  - Multi-statement SQL script execution (runs all commands in sequence).
 *  - Intelligent Error Suggestion Engine with auto-fix recommendations and one-click apply.
 *  - Persistent Query History tracking in browser localStorage.
 *  - Real-time physical plan inspector and live storage engine metrics.
 *  - High-performance, professional Dark Zinc developer UI (Linear / Supabase aesthetic).
 * Zero external dependencies: powered by standard Java HttpServer.
 */
public class Server {
    private final Database db;
    private final int port;
    private HttpServer server;

    public Server(Database db, int port) {
        this.db = db;
        this.port = port;
    }

    public static void start(String dataDir, int port) throws Exception {
        Database db = new Database(dataDir);
        if (db.allTables().isEmpty()) {
            try (QueryExecutor q = db.newSession()) {
                q.execute("CREATE TABLE users (id INT, name TEXT, age INT) USING BTREE");
                q.execute("INSERT INTO users VALUES (1, 'Alice', 30), (2, 'Bob', 25), (3, 'Charlie', 35), (4, 'Diana', 28)");
                q.execute("CREATE TABLE events (id INT, kind TEXT) USING LSM");
                q.execute("INSERT INTO events VALUES (1, 'login'), (2, 'page_view'), (3, 'purchase')");
            } catch (Exception ignored) {
            }
        }
        Server s = new Server(db, port);
        s.run();
    }

    public void run() throws IOException {
        int actualPort = port;
        while (actualPort < port + 50) {
            try {
                server = HttpServer.create(new InetSocketAddress(actualPort), 0);
                break;
            } catch (java.net.BindException e) {
                actualPort++;
            }
        }
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            actualPort = server.getAddress().getPort();
        }

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor() != null
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newCachedThreadPool());

        server.createContext("/", new UIHandler());
        server.createContext("/api/query", new QueryHandler());
        server.createContext("/api/tables", new TablesHandler());
        server.createContext("/api/stats", new StatsHandler());

        server.start();
        System.out.println("==============================================================================");
        System.out.println("  MiniDB Web Studio is running on localhost!");
        System.out.println("  Open your browser at: http://localhost:" + actualPort);
        System.out.println("  Data Directory:       " + Paths.get(db.options() != null ? "minidb-data" : "").toAbsolutePath());
        System.out.println("==============================================================================");
    }

    public void stop() {
        if (server != null) server.stop(0);
        db.close();
    }

    public static List<String> splitStatements(String script) {
        List<String> stmts = new ArrayList<>();
        if (script == null) return stmts;
        StringBuilder cur = new StringBuilder();
        int len = script.length();
        boolean inStr = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < len; i++) {
            char c = script.charAt(i);

            if (inLineComment) {
                cur.append(c);
                if (c == '\n' || c == '\r') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                cur.append(c);
                if (c == '*' && i + 1 < len && script.charAt(i + 1) == '/') {
                    cur.append('/');
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (inStr) {
                cur.append(c);
                if (c == '\'') {
                    if (i + 1 < len && script.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inStr = false;
                    }
                }
                continue;
            }

            if (c == '-' && i + 1 < len && script.charAt(i + 1) == '-') {
                cur.append("--");
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && i + 1 < len && script.charAt(i + 1) == '*') {
                cur.append("/*");
                i++;
                inBlockComment = true;
                continue;
            }
            if (c == '\'') {
                cur.append(c);
                inStr = true;
                continue;
            }
            if (c == ';') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) stmts.add(s);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String rem = cur.toString().trim();
        if (!rem.isEmpty()) stmts.add(rem);
        return stmts;
    }

    public static String getSuggestion(String sql, String error) {
        String err = error != null ? error.toLowerCase() : "";
        String sUpper = sql != null ? sql.toUpperCase() : "";

        if (err.contains("first column is the primary key and must be int")) {
            return "MiniDB requires the first column to be an INT primary key (used as the clustered index key). Change column 1 to INT, e.g.: CREATE TABLE tableName (id INT, ...)";
        }
        if (err.contains("cannot update the primary key")) {
            return "Primary keys cannot be updated in-place. Delete the old row with DELETE FROM ... WHERE id = ... and insert a new row with INSERT INTO ...";
        }
        if (err.contains("type mismatch") && err.contains("is int")) {
            return "Column expects an INT. Provide numbers without quotes or decimals (e.g. 25 instead of '25' or 25.5).";
        }
        if (err.contains("type mismatch") && err.contains("is text")) {
            return "Column expects TEXT. Wrap string literals in single quotes, e.g.: 'Alice'.";
        }
        if (err.contains("duplicate primary key")) {
            return "A row with this primary key already exists. Use a unique integer or perform an UPDATE/DELETE on the existing row first.";
        }
        if (err.contains("no such table")) {
            return "The specified table does not exist. Check spelling or create it first. Run 'SHOW TABLES;' to view existing tables.";
        }
        if (err.contains("no such column")) {
            return "The specified column was not found in the table. Run 'SHOW TABLES;' or 'SELECT * FROM <table_name> LIMIT 1;' to view the schema.";
        }
        if (sUpper.contains(" OR ") || sUpper.contains("\tOR ") || sUpper.contains("\nOR ")) {
            return "MiniDB's query engine currently supports 'AND' in WHERE clauses. 'OR' clauses are not yet supported. Rewrite as separate queries or use an index range scan.";
        }
        if (sUpper.contains(" JOIN ")) {
            return "JOIN operations are not supported yet in MiniDB. Query tables individually or perform application-level joins.";
        }
        if (sUpper.contains("DROP TABLE")) {
            return "DROP TABLE is not currently implemented. To reset tables, run CHECKPOINT and delete files in the data directory, or create a table with a different name.";
        }
        if (sUpper.contains("SUM(") || sUpper.contains("AVG(") || sUpper.contains("MIN(") || sUpper.contains("MAX(")) {
            return "MiniDB currently supports COUNT(*) for aggregations. Extended aggregates (SUM, AVG, MIN, MAX) are planned for Phase 2.";
        }
        if (err.contains("unknown column type")) {
            return "MiniDB supports column types: INT (64-bit integer) and TEXT (string / VARCHAR). Other types like FLOAT, DATE, BLOB are not yet supported.";
        }
        if (err.contains("not allowed inside a transaction")) {
            return "DDL statements (CREATE TABLE) and CHECKPOINT must be run outside transactions. Run 'COMMIT;' or 'ROLLBACK;' first to close the open transaction.";
        }
        if (err.contains("no active transaction")) {
            return "No transaction is currently open. Start one with 'BEGIN;' before issuing 'COMMIT;' or 'ROLLBACK;'.";
        }
        if (err.contains("a transaction is already active")) {
            return "A transaction is already open in this session. Issue 'COMMIT;' or 'ROLLBACK;' before starting a new one.";
        }
        return "Check statement syntax. Ensure table names and column names use standard identifiers [a-z0-9_] and strings use single quotes.";
    }

    public static String getSuggestedSql(String sql, String error) {
        if (sql == null) return null;
        String sTrim = sql.trim();
        String sUpper = sTrim.toUpperCase();
        String err = error != null ? error.toLowerCase() : "";

        if (sUpper.startsWith("CREATE TABLE")) {
            if (err.contains("first column is the primary key") || err.contains("unknown column type")) {
                String[] parts = sTrim.split("\\s+");
                String tblName = parts.length >= 3 ? parts[2].replaceAll("[^a-zA-Z0-9_]", "") : "accounts";
                return "CREATE TABLE " + tblName + " (id INT, name TEXT, balance INT) USING BTREE;";
            }
        }
        if (err.contains("no active transaction") && (sUpper.contains("COMMIT") || sUpper.contains("ROLLBACK"))) {
            return "BEGIN;\nINSERT INTO users VALUES (10, 'Grace', 27);\nCOMMIT;";
        }
        if (sUpper.contains(" DROP TABLE ")) {
            return "CHECKPOINT;";
        }
        return null;
    }

    private class UIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                send(ex, 405, "text/plain", "Method Not Allowed");
                return;
            }
            byte[] html = getIndexHtml().getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "text/html; charset=utf-8", html);
        }
    }

    private class QueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                send(ex, 405, "application/json", "{\"error\":\"POST required\"}");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String rawSql = extractSql(body);
            if (rawSql == null || rawSql.isBlank()) {
                send(ex, 400, "application/json", "{\"success\":false,\"error\":\"Empty SQL statement\"}");
                return;
            }

            List<String> statements = splitStatements(rawSql);
            if (statements.isEmpty()) {
                send(ex, 400, "application/json", "{\"success\":false,\"error\":\"No valid statements found\"}");
                return;
            }

            long totalStart = System.nanoTime();
            StringBuilder outJson = new StringBuilder();
            outJson.append("{\"success\":");

            List<String> resultsJson = new ArrayList<>();
            boolean overallSuccess = true;
            String errorMsg = null;
            String failedSql = null;
            String suggestion = null;
            String suggestedSql = null;

            try (QueryExecutor session = db.newSession()) {
                for (String stmt : statements) {
                    long stmtStart = System.nanoTime();
                    try {
                        QueryResult res = session.execute(stmt);
                        double durationMs = (System.nanoTime() - stmtStart) / 1_000_000.0;
                        resultsJson.add(stmtToJson(stmt, res, durationMs));
                    } catch (Exception e) {
                        overallSuccess = false;
                        errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                        failedSql = stmt;
                        suggestion = getSuggestion(stmt, errorMsg);
                        suggestedSql = getSuggestedSql(stmt, errorMsg);
                        double durationMs = (System.nanoTime() - stmtStart) / 1_000_000.0;
                        resultsJson.add("{\"sql\":\"" + escapeJson(stmt) + "\",\"success\":false,\"error\":\""
                                + escapeJson(errorMsg) + "\",\"durationMs\":" + String.format(Locale.US, "%.2f", durationMs) + "}");
                        break;
                    }
                }
            }

            double totalDurationMs = (System.nanoTime() - totalStart) / 1_000_000.0;
            outJson.append(overallSuccess ? "true" : "false").append(',');
            outJson.append("\"totalDurationMs\":").append(String.format(Locale.US, "%.2f", totalDurationMs)).append(',');
            outJson.append("\"statementCount\":").append(statements.size()).append(',');
            if (errorMsg != null) {
                outJson.append("\"error\":\"").append(escapeJson(errorMsg)).append("\",");
                outJson.append("\"failedSql\":\"").append(escapeJson(failedSql)).append("\",");
                outJson.append("\"suggestion\":\"").append(escapeJson(suggestion)).append("\",");
                if (suggestedSql != null) {
                    outJson.append("\"suggestedSql\":\"").append(escapeJson(suggestedSql)).append("\",");
                }
            }
            outJson.append("\"results\":[");
            for (int i = 0; i < resultsJson.size(); i++) {
                if (i > 0) outJson.append(',');
                outJson.append(resultsJson.get(i));
            }
            outJson.append("]}");

            send(ex, 200, "application/json; charset=utf-8", outJson.toString());
        }
    }

    private class TablesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            List<Table> tables = db.allTables();
            for (int i = 0; i < tables.size(); i++) {
                Table t = tables.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":\"").append(escapeJson(t.getName())).append("\",");
                sb.append("\"engine\":\"").append(escapeJson(t.getEngineName())).append("\",");
                sb.append("\"columns\":[");
                List<ColumnDef> cols = t.getColumns();
                for (int j = 0; j < cols.size(); j++) {
                    if (j > 0) sb.append(',');
                    sb.append("{\"name\":\"").append(escapeJson(cols.get(j).name)).append("\",");
                    sb.append("\"type\":\"").append(cols.get(j).type.name()).append("\"}");
                }
                sb.append("]}");
            }
            sb.append(']');
            send(ex, 200, "application/json; charset=utf-8", sb.toString());
        }
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String stats = db.stats();
            String json = "{\"stats\":\"" + escapeJson(stats) + "\"}";
            send(ex, 200, "application/json; charset=utf-8", json);
        }
    }

    private static String extractSql(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"sql\"")) {
            int keyIdx = trimmed.indexOf("\"sql\"");
            int colonIdx = trimmed.indexOf(':', keyIdx);
            int firstQuote = trimmed.indexOf('"', colonIdx + 1);
            if (firstQuote != -1) {
                StringBuilder sb = new StringBuilder();
                boolean esc = false;
                for (int i = firstQuote + 1; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    if (esc) {
                        if (c == 'n') sb.append('\n');
                        else if (c == 'r') sb.append('\r');
                        else if (c == 't') sb.append('\t');
                        else sb.append(c);
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        break;
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
        }
        return body;
    }

    private static String stmtToJson(String sql, QueryResult res, double durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sql\":\"").append(escapeJson(sql)).append("\",");
        sb.append("\"success\":true,");
        sb.append("\"durationMs\":").append(String.format(Locale.US, "%.2f", durationMs)).append(',');
        if (res.message != null) {
            sb.append("\"message\":\"").append(escapeJson(res.message)).append("\",");
        }
        if (res.plan != null) {
            sb.append("\"plan\":\"").append(escapeJson(res.plan)).append("\",");
        }
        if (res.columns != null) {
            sb.append("\"columns\":[");
            for (int i = 0; i < res.columns.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(res.columns.get(i))).append('"');
            }
            sb.append("],");
        } else {
            sb.append("\"columns\":[],");
        }
        if (res.rows != null) {
            sb.append("\"rows\":[");
            for (int i = 0; i < res.rows.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('[');
                Object[] row = res.rows.get(i);
                for (int j = 0; j < row.length; j++) {
                    if (j > 0) sb.append(',');
                    if (row[j] == null) sb.append("null");
                    else if (row[j] instanceof Number) sb.append(row[j]);
                    else sb.append('"').append(escapeJson(row[j].toString())).append('"');
                }
                sb.append(']');
            }
            sb.append(']');
        } else {
            sb.append("\"rows\":[]");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private void send(HttpExchange ex, int status, String mime, String body) throws IOException {
        send(ex, status, mime, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange ex, int status, String mime, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String getIndexHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>MiniDB Studio</title>
  <style>
    :root {
      --bg-base: #09090b;
      --bg-sidebar: #0d0d10;
      --bg-card: #141417;
      --bg-elevated: #1a1a1e;
      --bg-input: #09090b;
      --border-subtle: #1e1e22;
      --border-default: #27272a;
      --border-hover: #3f3f46;
      --text-white: #fafafa;
      --text-primary: #e4e4e7;
      --text-secondary: #a1a1aa;
      --text-muted: #71717a;
      --text-faint: #52525b;
      --btn-primary-bg: #fafafa;
      --btn-primary-text: #09090b;
      --btn-primary-hover: #e4e4e7;
      --error-bg: #130f10;
      --error-border: #2a1619;
      --error-line: #f43f5e;
      --error-text: #fca5a5;
      --suggest-bg: #13120d;
      --suggest-border: #2c2514;
      --suggest-line: #f59e0b;
      --suggest-text: #fde68a;
      --emerald: #10b981;
      --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      --font-mono: "JetBrains Mono", "SF Mono", "Fira Code", Menlo, Consolas, monospace;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg-base);
      color: var(--text-primary);
      font-family: var(--font-sans);
      display: flex;
      height: 100vh;
      overflow: hidden;
      font-size: 13px;
      line-height: 1.5;
      -webkit-font-smoothing: antialiased;
    }
    
    /* SCROLLBAR */
    ::-webkit-scrollbar { width: 6px; height: 6px; }
    ::-webkit-scrollbar-track { background: transparent; }
    ::-webkit-scrollbar-thumb { background: #27272a; border-radius: 3px; }
    ::-webkit-scrollbar-thumb:hover { background: #3f3f46; }

    /* SIDEBAR */
    #sidebar {
      width: 320px;
      min-width: 320px;
      background: var(--bg-sidebar);
      border-right: 1px solid var(--border-default);
      display: flex;
      flex-direction: column;
      user-select: none;
    }
    .brand-header {
      padding: 16px 18px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-subtle);
    }
    .brand-title {
      display: flex;
      align-items: center;
      gap: 10px;
      font-weight: 600;
      font-size: 14px;
      color: var(--text-white);
      letter-spacing: -0.01em;
    }
    .brand-title svg { color: var(--text-white); }
    .brand-meta {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .engine-badge {
      font-family: var(--font-mono);
      font-size: 10px;
      color: var(--text-muted);
      border: 1px solid var(--border-default);
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--bg-base);
      font-weight: 500;
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 11px;
      color: var(--emerald);
      font-weight: 500;
    }
    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--emerald);
      box-shadow: 0 0 6px rgba(16, 185, 129, 0.4);
    }

    /* SEGMENTED TAB SWITCHER */
    .tab-switcher-wrap {
      padding: 12px 14px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .tab-switcher {
      display: flex;
      background: var(--bg-base);
      border: 1px solid var(--border-default);
      border-radius: 6px;
      padding: 2px;
      gap: 2px;
    }
    .tab-btn {
      flex: 1;
      padding: 6px 10px;
      background: transparent;
      border: none;
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 500;
      border-radius: 4px;
      cursor: pointer;
      transition: all 0.15s ease;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
    }
    .tab-btn:hover { color: var(--text-primary); }
    .tab-btn.active {
      background: var(--bg-elevated);
      color: var(--text-white);
      box-shadow: 0 1px 3px rgba(0,0,0,0.5);
    }

    .tab-body {
      flex: 1;
      overflow-y: auto;
      padding: 12px 14px;
      display: none;
    }
    .tab-body.active { display: block; }

    /* SCHEMA SECTION */
    .section-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-faint);
      letter-spacing: 0.05em;
      text-transform: uppercase;
      margin-bottom: 10px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .table-list { list-style: none; display: flex; flex-direction: column; gap: 8px; }
    .table-card {
      background: var(--bg-card);
      border: 1px solid var(--border-default);
      border-radius: 6px;
      padding: 10px 12px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .table-card:hover {
      background: var(--bg-elevated);
      border-color: var(--border-hover);
    }
    .table-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;
    }
    .table-name {
      font-weight: 600;
      font-size: 13px;
      color: var(--text-white);
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .engine-tag {
      font-family: var(--font-mono);
      font-size: 9.5px;
      font-weight: 600;
      padding: 1px 5px;
      border-radius: 3px;
      color: var(--text-secondary);
      border: 1px solid var(--border-hover);
      background: var(--bg-base);
    }
    .col-row {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 6px;
    }
    .col-pill {
      font-family: var(--font-mono);
      font-size: 10.5px;
      color: var(--text-muted);
      background: var(--bg-base);
      padding: 2px 6px;
      border-radius: 3px;
      border: 1px solid var(--border-subtle);
    }
    .col-pill.pk {
      color: var(--text-secondary);
      border-color: var(--border-default);
    }

    /* HISTORY LIST */
    .history-list { display: flex; flex-direction: column; gap: 6px; }
    .history-card {
      background: var(--bg-card);
      border: 1px solid var(--border-default);
      border-radius: 6px;
      padding: 9px 12px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .history-card:hover {
      background: var(--bg-elevated);
      border-color: var(--border-hover);
    }
    .history-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 11px;
      color: var(--text-muted);
      margin-bottom: 4px;
    }
    .hist-dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      display: inline-block;
      margin-right: 5px;
    }
    .hist-dot.ok { background: var(--emerald); }
    .hist-dot.fail { background: var(--error-line); }
    .history-sql {
      font-family: var(--font-mono);
      font-size: 11.5px;
      color: var(--text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .btn-ghost-small {
      background: transparent;
      border: 1px solid var(--border-default);
      color: var(--text-muted);
      font-size: 10.5px;
      padding: 2px 7px;
      border-radius: 4px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .btn-ghost-small:hover {
      color: var(--text-primary);
      border-color: var(--border-hover);
    }

    /* SIDEBAR FOOTER DIAGNOSTICS */
    .sidebar-footer {
      border-top: 1px solid var(--border-subtle);
      background: var(--bg-base);
      padding: 10px 14px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .diagnostics-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 11px;
      color: var(--text-muted);
      font-weight: 500;
    }
    .diagnostics-body {
      font-family: var(--font-mono);
      font-size: 10.5px;
      color: var(--text-muted);
      background: var(--bg-sidebar);
      border: 1px solid var(--border-subtle);
      border-radius: 4px;
      padding: 8px 10px;
      max-height: 100px;
      overflow-y: auto;
      white-space: pre-wrap;
    }

    /* MAIN WORKSPACE */
    #main {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      background: var(--bg-base);
    }

    /* TOOLBAR / QUICK TEMPLATES */
    .toolbar-bar {
      padding: 10px 20px;
      background: var(--bg-sidebar);
      border-bottom: 1px solid var(--border-default);
      display: flex;
      gap: 6px;
      align-items: center;
      overflow-x: auto;
      white-space: nowrap;
    }
    .toolbar-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-faint);
      letter-spacing: 0.05em;
      text-transform: uppercase;
      margin-right: 4px;
    }
    .chip-btn {
      background: var(--bg-card);
      border: 1px solid var(--border-default);
      color: var(--text-secondary);
      padding: 5px 10px;
      border-radius: 5px;
      font-size: 11.5px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
      display: inline-flex;
      align-items: center;
      gap: 5px;
    }
    .chip-btn:hover {
      background: var(--bg-elevated);
      color: var(--text-white);
      border-color: var(--border-hover);
    }

    /* EDITOR SECTION */
    .editor-wrapper {
      padding: 14px 20px 12px;
      background: var(--bg-sidebar);
      border-bottom: 1px solid var(--border-default);
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .editor-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 12px;
      color: var(--text-muted);
    }
    .editor-title {
      font-weight: 600;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .kbd-shortcut {
      font-family: var(--font-sans);
      font-size: 11px;
      color: var(--text-muted);
    }
    kbd {
      background: var(--bg-card);
      border: 1px solid var(--border-default);
      color: var(--text-secondary);
      font-size: 10px;
      padding: 2px 5px;
      border-radius: 4px;
      font-family: var(--font-mono);
      font-weight: 600;
    }
    .textarea-box {
      position: relative;
    }
    textarea#sqlInput {
      width: 100%;
      height: 110px;
      background: var(--bg-input);
      border: 1px solid var(--border-default);
      border-radius: 6px;
      color: var(--text-white);
      padding: 12px 14px;
      font-family: var(--font-mono);
      font-size: 13px;
      line-height: 1.5;
      resize: vertical;
      outline: none;
      transition: border-color 0.15s ease, box-shadow 0.15s ease;
    }
    textarea#sqlInput:focus {
      border-color: var(--border-hover);
      box-shadow: 0 0 0 1px var(--border-hover);
    }
    .editor-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .btn-secondary {
      background: transparent;
      border: 1px solid var(--border-default);
      color: var(--text-secondary);
      font-size: 12px;
      padding: 6px 12px;
      border-radius: 5px;
      cursor: pointer;
      font-weight: 500;
      transition: all 0.15s ease;
    }
    .btn-secondary:hover {
      background: var(--bg-elevated);
      color: var(--text-white);
      border-color: var(--border-hover);
    }
    .btn-primary {
      background: var(--btn-primary-bg);
      color: var(--btn-primary-text);
      border: 1px solid transparent;
      font-size: 12.5px;
      font-weight: 600;
      padding: 7px 18px;
      border-radius: 5px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: all 0.15s ease;
      box-shadow: 0 1px 2px rgba(0,0,0,0.4);
    }
    .btn-primary:hover {
      background: var(--btn-primary-hover);
      transform: translateY(-0.5px);
    }
    .btn-primary:active {
      transform: translateY(0);
    }

    /* RESULTS VIEWPORT */
    .results-viewport {
      flex: 1;
      overflow-y: auto;
      padding: 16px 20px 24px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .empty-state {
      margin: auto;
      text-align: center;
      color: var(--text-muted);
      padding: 40px 20px;
    }
    .empty-state svg {
      color: var(--text-faint);
      margin-bottom: 12px;
    }
    .empty-state p { font-size: 13px; max-width: 380px; margin: 0 auto; line-height: 1.6; }

    /* SUMMARY BANNER */
    .batch-summary {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 14px;
      background: var(--bg-card);
      border: 1px solid var(--border-default);
      border-radius: 6px;
      font-size: 12px;
      color: var(--text-secondary);
    }

    /* ERROR NOTIFICATION CARD */
    .card-error {
      background: var(--error-bg);
      border: 1px solid var(--error-border);
      border-left: 3px solid var(--error-line);
      border-radius: 6px;
      padding: 14px 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .card-error-head {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
      font-size: 13px;
      color: var(--error-text);
    }
    .card-error-body {
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--text-primary);
      line-height: 1.5;
    }
    .card-error-stmt {
      font-family: var(--font-mono);
      font-size: 11px;
      color: var(--text-muted);
      background: rgba(0,0,0,0.3);
      padding: 6px 10px;
      border-radius: 4px;
      border: 1px solid var(--error-border);
    }

    /* INTELLIGENT SUGGESTION CARD */
    .card-suggestion {
      background: var(--suggest-bg);
      border: 1px solid var(--suggest-border);
      border-left: 3px solid var(--suggest-line);
      border-radius: 6px;
      padding: 14px 16px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .card-suggestion-head {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
      font-size: 13px;
      color: var(--suggest-text);
    }
    .card-suggestion-body {
      font-size: 12.5px;
      color: var(--text-primary);
      line-height: 1.55;
    }
    .card-suggestion-actions {
      display: flex;
      gap: 8px;
      align-items: center;
      margin-top: 2px;
    }
    .btn-apply-fix {
      background: #fafafa;
      color: #09090b;
      border: none;
      font-size: 11.5px;
      font-weight: 600;
      padding: 5px 12px;
      border-radius: 4px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 5px;
      transition: all 0.15s ease;
    }
    .btn-apply-fix:hover {
      background: #e4e4e7;
    }

    /* STATEMENT RESULT CARD */
    .card-statement {
      background: var(--bg-card);
      border: 1px solid var(--border-default);
      border-radius: 6px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
    .card-statement-head {
      padding: 10px 14px;
      background: var(--bg-elevated);
      border-bottom: 1px solid var(--border-default);
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 12px;
    }
    .stmt-meta-left {
      display: flex;
      align-items: center;
      gap: 8px;
      overflow: hidden;
    }
    .stmt-badge {
      font-family: var(--font-mono);
      font-size: 11px;
      color: var(--text-muted);
      background: var(--bg-base);
      border: 1px solid var(--border-default);
      padding: 1px 6px;
      border-radius: 4px;
      font-weight: 600;
    }
    .stmt-sql-code {
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 480px;
    }
    .stmt-meta-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .plan-tag {
      font-family: var(--font-mono);
      font-size: 11px;
      color: #93c5fd;
      background: #0f172a;
      border: 1px solid #1e293b;
      padding: 2px 7px;
      border-radius: 4px;
    }
    .duration-tag {
      font-family: var(--font-mono);
      font-size: 11px;
      color: var(--text-muted);
    }
    .btn-copy-tsv {
      background: transparent;
      border: 1px solid var(--border-default);
      color: var(--text-muted);
      font-size: 11px;
      padding: 2px 7px;
      border-radius: 4px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      transition: all 0.15s ease;
    }
    .btn-copy-tsv:hover {
      color: var(--text-white);
      border-color: var(--border-hover);
    }

    /* DATA TABLE */
    .table-container {
      width: 100%;
      overflow-x: auto;
      max-height: 400px;
    }
    table.data-grid {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
      font-family: var(--font-mono);
      text-align: left;
    }
    table.data-grid th {
      background: var(--bg-card);
      color: var(--text-muted);
      font-weight: 600;
      padding: 8px 14px;
      border-bottom: 1px solid var(--border-default);
      letter-spacing: 0.04em;
      text-transform: uppercase;
      font-size: 11px;
      position: sticky;
      top: 0;
      z-index: 1;
    }
    table.data-grid th.col-index,
    table.data-grid td.col-index {
      width: 40px;
      text-align: center;
      color: var(--text-faint);
      border-right: 1px solid var(--border-subtle);
      user-select: none;
    }
    table.data-grid td {
      padding: 7px 14px;
      border-bottom: 1px solid var(--border-subtle);
      color: var(--text-primary);
      white-space: nowrap;
    }
    table.data-grid tr:hover td {
      background: var(--bg-elevated);
    }
    table.data-grid tr:last-child td {
      border-bottom: none;
    }
    .val-null {
      color: var(--text-faint);
      font-style: italic;
    }
    .val-num {
      text-align: right;
    }
    .message-banner {
      padding: 12px 16px;
      color: var(--text-secondary);
      font-size: 12.5px;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .table-footer {
      padding: 6px 14px;
      background: var(--bg-card);
      border-top: 1px solid var(--border-subtle);
      font-size: 11px;
      color: var(--text-muted);
      display: flex;
      justify-content: space-between;
    }

    /* TOAST */
    #toast {
      position: fixed;
      bottom: 24px;
      right: 24px;
      background: #18181b;
      color: #fafafa;
      border: 1px solid var(--border-default);
      padding: 8px 14px;
      border-radius: 6px;
      font-size: 12px;
      box-shadow: 0 4px 12px rgba(0,0,0,0.5);
      opacity: 0;
      transform: translateY(10px);
      transition: all 0.2s ease;
      pointer-events: none;
      z-index: 9999;
    }
    #toast.show {
      opacity: 1;
      transform: translateY(0);
    }
  </style>
</head>
<body>

  <!-- SIDEBAR -->
  <aside id="sidebar">
    <div class="brand-header">
      <div class="brand-title">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
          <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
          <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
        </svg>
        <span>MiniDB Studio</span>
      </div>
      <div class="brand-meta">
        <span class="engine-badge">B+Tree / LSM</span>
        <span class="status-pill"><span class="status-dot"></span>Live</span>
      </div>
    </div>

    <div class="tab-switcher-wrap">
      <div class="tab-switcher">
        <button class="tab-btn active" onclick="switchTab('tablesTab', this)">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18"/></svg>
          Schema
        </button>
        <button class="tab-btn" onclick="switchTab('historyTab', this)">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
          History
        </button>
      </div>
    </div>

    <!-- TABLES SCHEMA TAB -->
    <div id="tablesTab" class="tab-body active">
      <div class="section-label">
        <span>Tables & Schema</span>
        <button class="btn-ghost-small" onclick="loadTables()">Refresh</button>
      </div>
      <ul class="table-list" id="tableList">
        <li style="color:var(--text-muted);font-size:12px;padding:8px 0;">Loading tables...</li>
      </ul>
    </div>

    <!-- QUERY HISTORY TAB -->
    <div id="historyTab" class="tab-body">
      <div class="section-label">
        <span>Execution History</span>
        <button class="btn-ghost-small" onclick="clearHistory()">Clear All</button>
      </div>
      <div class="history-list" id="historyList"></div>
    </div>

    <!-- FOOTER DIAGNOSTICS -->
    <div class="sidebar-footer">
      <div class="diagnostics-header">
        <span>STORAGE ENGINE STATS</span>
        <button class="btn-ghost-small" onclick="loadStats()">Poll</button>
      </div>
      <div class="diagnostics-body" id="statsCard">Fetching engine metrics...</div>
    </div>
  </aside>

  <!-- MAIN WORKBENCH -->
  <main id="main">
    <!-- TOOLBAR TEMPLATES -->
    <div class="toolbar-bar">
      <span class="toolbar-label">Templates</span>
      <button class="chip-btn" onclick="setQuery('SELECT * FROM users ORDER BY age DESC LIMIT 10;')">
        <span>Users (B+Tree)</span>
      </button>
      <button class="chip-btn" onclick="setQuery('SELECT * FROM events;')">
        <span>Events (LSM)</span>
      </button>
      <button class="chip-btn" onclick="setQuery('EXPLAIN SELECT * FROM users WHERE id >= 2 AND id <= 4;')">
        <span>Explain Range Scan</span>
      </button>
      <button class="chip-btn" onclick="loadMultiStatementDemo()">
        <span>Multi-Statement Script</span>
      </button>
      <button class="chip-btn" onclick="loadErrorSuggestionDemo()">
        <span>Error & Suggestion Demo</span>
      </button>
      <button class="chip-btn" onclick="setQuery('CHECKPOINT;')">
        <span>Checkpoint WAL</span>
      </button>
      <button class="chip-btn" onclick="setQuery('SHOW STATS;')">
        <span>Show Stats</span>
      </button>
    </div>

    <!-- SQL EDITOR -->
    <div class="editor-wrapper">
      <div class="editor-top">
        <span class="editor-title">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
          SQL Query Editor
        </span>
        <span class="kbd-shortcut"><kbd>Ctrl</kbd> + <kbd>Enter</kbd> to execute batch</span>
      </div>
      <div class="textarea-box">
        <textarea id="sqlInput" spellcheck="false" placeholder="Enter one or multiple SQL statements separated by semicolons...&#10;e.g.&#10;SELECT * FROM users;&#10;SELECT * FROM events;">SELECT * FROM users;</textarea>
      </div>
      <div class="editor-actions">
        <button class="btn-secondary" onclick="document.getElementById('sqlInput').value = ''; document.getElementById('sqlInput').focus();">Clear</button>
        <button class="btn-primary" id="runBtn" onclick="executeSql()">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
          Run Batch
        </button>
      </div>
    </div>

    <!-- RESULTS VIEWPORT -->
    <div class="results-viewport" id="resultsArea">
      <div class="empty-state">
        <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
          <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
          <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
        </svg>
        <p>Execute SQL queries to inspect multi-statement execution, physical access plans, and storage engine metrics.</p>
      </div>
    </div>
  </main>

  <div id="toast">Copied to clipboard</div>

  <script>
    const sqlInput = document.getElementById('sqlInput');
    const resultsArea = document.getElementById('resultsArea');
    const tableList = document.getElementById('tableList');
    const statsCard = document.getElementById('statsCard');
    const historyList = document.getElementById('historyList');
    const toast = document.getElementById('toast');

    // Tab key indent support inside textarea
    sqlInput.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        executeSql();
      } else if (e.key === 'Tab') {
        e.preventDefault();
        const start = sqlInput.selectionStart;
        const end = sqlInput.selectionEnd;
        sqlInput.value = sqlInput.value.substring(0, start) + '  ' + sqlInput.value.substring(end);
        sqlInput.selectionStart = sqlInput.selectionEnd = start + 2;
      }
    });

    function showToast(msg) {
      toast.textContent = msg;
      toast.classList.add('show');
      setTimeout(() => toast.classList.remove('show'), 2000);
    }

    function switchTab(tabId, btn) {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-body').forEach(c => c.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById(tabId).classList.add('active');
      if (tabId === 'historyTab') renderHistory();
    }

    function setQuery(sql) {
      sqlInput.value = sql;
      sqlInput.focus();
      executeSql();
    }

    function loadMultiStatementDemo() {
      sqlInput.value = `-- Multi-Statement Transaction Demo\\n` +
        `BEGIN;\\n` +
        `INSERT INTO users VALUES (5, 'Eve', 22), (6, 'Frank', 41);\\n` +
        `UPDATE users SET age = 23 WHERE id = 5;\\n` +
        `COMMIT;\\n` +
        `SELECT * FROM users WHERE id >= 4 ORDER BY age DESC;`;
      sqlInput.focus();
    }

    function loadErrorSuggestionDemo() {
      sqlInput.value = `-- Testing Error Detection & Suggestion Engine\\n` +
        `CREATE TABLE accounts (account_num VARCHAR(30), balance FLOAT) USING BTREE;`;
      sqlInput.focus();
    }

    function applyFix(suggestedSql) {
      if (!suggestedSql) return;
      sqlInput.value = suggestedSql;
      sqlInput.focus();
      showToast('Applied recommendation to editor');
    }

    function getHistory() {
      try { return JSON.parse(localStorage.getItem('minidb_history') || '[]'); } catch (e) { return []; }
    }

    function saveHistory(sql, success, durationMs) {
      const hist = getHistory();
      hist.unshift({ sql, success, durationMs, time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) });
      if (hist.length > 50) hist.pop();
      localStorage.setItem('minidb_history', JSON.stringify(hist));
      renderHistory();
    }

    function clearHistory() {
      localStorage.removeItem('minidb_history');
      renderHistory();
      showToast('Query history cleared');
    }

    function renderHistory() {
      const hist = getHistory();
      if (!historyList) return;
      if (hist.length === 0) {
        historyList.innerHTML = '<div style="color:var(--text-muted);font-size:12px;padding:12px 0;">No query history recorded yet.</div>';
        return;
      }
      historyList.innerHTML = hist.map(item => `
        <div class="history-card" onclick="loadFromHistory(decodeURIComponent('${encodeURIComponent(item.sql)}'))">
          <div class="history-top">
            <span><span class="hist-dot ${item.success ? 'ok' : 'fail'}"></span>${item.success ? 'Success' : 'Error'} · ${item.durationMs}ms</span>
            <span>${item.time}</span>
          </div>
          <div class="history-sql">${escapeHtml(item.sql.split('\\n')[0])}</div>
        </div>
      `).join('');
    }

    function loadFromHistory(sql) {
      sqlInput.value = sql;
      sqlInput.focus();
      showToast('Loaded query into editor');
    }

    async function loadTables() {
      try {
        const res = await fetch('/api/tables');
        const tables = await res.json();
        if (tables.length === 0) {
          tableList.innerHTML = '<li style="color:var(--text-muted);font-size:12px;padding:8px 0;">No tables found.</li>';
          return;
        }
        tableList.innerHTML = tables.map(t => {
          const colsHtml = t.columns.map((c, idx) => `
            <span class="col-pill ${idx === 0 ? 'pk' : ''}" title="${idx === 0 ? 'Clustered Primary Key' : 'Column'}">
              ${escapeHtml(c.name)} <span style="opacity:0.6">${c.type}</span>${idx === 0 ? ' 🔑' : ''}
            </span>
          `).join('');
          return `
            <li class="table-card" onclick="setQuery('SELECT * FROM ${escapeHtml(t.name)} LIMIT 50;')">
              <div class="table-head">
                <span class="table-name">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
                  ${escapeHtml(t.name)}
                </span>
                <span class="engine-tag">${t.engine}</span>
              </div>
              <div class="col-row">${colsHtml}</div>
            </li>
          `;
        }).join('');
      } catch (err) {
        tableList.innerHTML = '<li style="color:var(--error-line);font-size:12px;">Failed to load tables</li>';
      }
    }

    async function loadStats() {
      try {
        const res = await fetch('/api/stats');
        const data = await res.json();
        statsCard.textContent = data.stats || 'No metrics reported';
      } catch (err) {
        statsCard.textContent = 'Engine diagnostics offline';
      }
    }

    async function executeSql() {
      const sql = sqlInput.value.trim();
      if (!sql) return;
      const runBtn = document.getElementById('runBtn');
      runBtn.disabled = true;
      runBtn.style.opacity = '0.6';
      resultsArea.innerHTML = `
        <div style="color:var(--text-muted);padding:14px;font-size:12px;display:flex;align-items:center;gap:8px;">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="animation:spin 1s linear infinite;"><circle cx="12" cy="12" r="10"/><path d="M12 2a10 10 0 0 1 10 10"/></svg>
          Executing batch statements...
        </div>
        <style>@keyframes spin { 100% { transform: rotate(360deg); } }</style>
      `;
      try {
        const res = await fetch('/api/query', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sql })
        });
        const data = await res.json();
        saveHistory(sql, data.success, data.totalDurationMs || 0);
        renderResponse(data);
        loadTables();
        loadStats();
      } catch (err) {
        resultsArea.innerHTML = `
          <div class="card-error">
            <div class="card-error-head">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              <span>Network / Engine Communication Error</span>
            </div>
            <div class="card-error-body">${escapeHtml(err.message)}</div>
          </div>
        `;
      } finally {
        runBtn.disabled = false;
        runBtn.style.opacity = '1';
      }
    }

    function renderResponse(data) {
      let html = '';

      // Summary Header
      html += `
        <div class="batch-summary">
          <span>Batch Execution: <b>${data.statementCount || 0} statement${data.statementCount === 1 ? '' : 's'}</b></span>
          <span>Status: <b style="color:${data.success ? 'var(--emerald)' : 'var(--error-line)'}">${data.success ? 'Success' : 'Error'}</b> · Total Time: <b>${data.totalDurationMs} ms</b></span>
        </div>
      `;

      // Error Section (if any)
      if (!data.success && data.error) {
        html += `
          <div class="card-error">
            <div class="card-error-head">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              <span>Execution Error</span>
            </div>
            <div class="card-error-body">${escapeHtml(data.error)}</div>
            ${data.failedSql ? `<div class="card-error-stmt">Failed Statement: <code>${escapeHtml(data.failedSql)}</code></div>` : ''}
          </div>
        `;

        if (data.suggestion) {
          html += `
            <div class="card-suggestion">
              <div class="card-suggestion-head">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
                <span>Intelligent Fix Recommendation</span>
              </div>
              <div class="card-suggestion-body">${escapeHtml(data.suggestion)}</div>
              ${data.suggestedSql ? `
                <div class="card-suggestion-actions">
                  <button class="btn-apply-fix" onclick="applyFix(decodeURIComponent('${encodeURIComponent(data.suggestedSql)}'))">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                    Apply Fix to Editor
                  </button>
                </div>
              ` : ''}
            </div>
          `;
        }
      }

      // Statement Result Cards
      if (data.results && data.results.length > 0) {
        for (let i = 0; i < data.results.length; i++) {
          const st = data.results[i];
          const tableId = 'resTable_' + i;
          html += `
            <div class="card-statement">
              <div class="card-statement-head">
                <div class="stmt-meta-left">
                  <span class="stmt-badge">#${i + 1}</span>
                  <span class="stmt-sql-code" title="${escapeHtml(st.sql)}">${escapeHtml(st.sql)}</span>
                </div>
                <div class="stmt-meta-right">
                  ${st.plan ? `<span class="plan-tag" title="Physical Execution Plan">${escapeHtml(st.plan)}</span>` : ''}
                  <span class="duration-tag">⏱ ${st.durationMs} ms</span>
                  ${st.rows && st.rows.length > 0 ? `
                    <button class="btn-copy-tsv" onclick="copyTableTsv('${tableId}')">
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                      Copy TSV
                    </button>
                  ` : ''}
                </div>
              </div>
          `;

          if (st.message && (!st.rows || st.rows.length === 0)) {
            html += `
              <div class="message-banner">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--emerald)" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                <span>${escapeHtml(st.message)}</span>
              </div>
            `;
          } else if (st.rows && st.columns) {
            html += `
              <div class="table-container">
                <table class="data-grid" id="${tableId}">
                  <thead>
                    <tr>
                      <th class="col-index">#</th>
                      ${st.columns.map(c => `<th>${escapeHtml(c)}</th>`).join('')}
                    </tr>
                  </thead>
                  <tbody>
                    ${st.rows.map((row, rIdx) => `
                      <tr>
                        <td class="col-index">${rIdx + 1}</td>
                        ${row.map(cell => {
                          if (cell === null) return '<td><span class="val-null">null</span></td>';
                          const isNum = typeof cell === 'number';
                          return `<td class="${isNum ? 'val-num' : ''}">${escapeHtml(cell.toString())}</td>`;
                        }).join('')}
                      </tr>
                    `).join('')}
                  </tbody>
                </table>
              </div>
              <div class="table-footer">
                <span>Returned ${st.rows.length} row${st.rows.length === 1 ? '' : 's'}</span>
                <span>${st.columns.length} columns</span>
              </div>
            `;
          }
          html += '</div>';
        }
      }

      resultsArea.innerHTML = html;
    }

    function copyTableTsv(tableId) {
      const table = document.getElementById(tableId);
      if (!table) return;
      let tsv = [];
      for (const row of table.rows) {
        let cells = [];
        for (let i = 1; i < row.cells.length; i++) {
          cells.push(row.cells[i].innerText);
        }
        tsv.push(cells.join('\\t'));
      }
      navigator.clipboard.writeText(tsv.join('\\n')).then(() => {
        showToast('Table copied as TSV');
      });
    }

    function escapeHtml(s) {
      if (s === null || s === undefined) return '';
      return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    loadTables();
    loadStats();
    renderHistory();
  </script>
</body>
</html>
        """;
    }
}
