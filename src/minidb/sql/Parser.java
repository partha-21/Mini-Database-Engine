package minidb.sql;

import minidb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser. Grammar:
 *   CREATE TABLE t (col TYPE, ...) [USING BTREE|LSM]
 *   INSERT INTO t VALUES (v, ...) [, (v, ...)]*
 *   SELECT * | COUNT(*) | col, ... FROM t [WHERE c AND c ...] [ORDER BY col [ASC|DESC]] [LIMIT n]
 *   UPDATE t SET col = v [, col = v]* [WHERE ...]
 *   DELETE FROM t [WHERE ...]
 *   BEGIN | COMMIT | ROLLBACK | CHECKPOINT | SHOW TABLES | SHOW STATS
 *   EXPLAIN (SELECT | UPDATE | DELETE ...)
 */
public class Parser {
    private final List<Token> tokens;
    private int pos = 0;

    public Parser(String sql) {
        this.tokens = new Lexer(sql).tokenize();
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAt(int n) {
        return tokens.get(Math.min(pos + n, tokens.size() - 1));
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private boolean check(Token.Type t) {
        return peek().type == t;
    }

    private boolean accept(Token.Type t) {
        if (check(t)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(Token.Type t, String context) {
        if (!check(t)) {
            throw new RuntimeException("Parse error near '" + peek().text + "': expected " + t + " " + context);
        }
        return advance();
    }

    public Statement parse() {
        Statement s = parseStatement();
        accept(Token.Type.SEMICOLON);
        if (!check(Token.Type.EOF)) {
            throw new RuntimeException("Parse error: unexpected input after statement: '" + peek().text + "'");
        }
        return s;
    }

    private Statement parseStatement() {
        switch (peek().type) {
            case CREATE: return parseCreate();
            case INSERT: return parseInsert();
            case SELECT: return parseSelect();
            case UPDATE: return parseUpdate();
            case DELETE: return parseDelete();
            case BEGIN: advance(); return new TxnStatement(TxnStatement.Kind.BEGIN);
            case COMMIT: advance(); return new TxnStatement(TxnStatement.Kind.COMMIT);
            case ROLLBACK: advance(); return new TxnStatement(TxnStatement.Kind.ROLLBACK);
            case CHECKPOINT: advance(); return new CheckpointStatement();
            case SHOW: {
                advance();
                String what = expect(Token.Type.IDENTIFIER, "after SHOW (TABLES or STATS)").text.toUpperCase();
                if (!what.equals("TABLES") && !what.equals("STATS")) throw new RuntimeException("SHOW supports TABLES or STATS");
                return new ShowStatement(what);
            }
            case EXPLAIN: {
                advance();
                Statement inner = parseStatement();
                if (!(inner instanceof SelectStatement || inner instanceof UpdateStatement || inner instanceof DeleteStatement)) {
                    throw new RuntimeException("EXPLAIN supports SELECT, UPDATE and DELETE");
                }
                return new ExplainStatement(inner);
            }
            default:
                throw new RuntimeException("Parse error: unexpected token '" + peek().text + "'");
        }
    }

    private Statement parseCreate() {
        expect(Token.Type.CREATE, "");
        expect(Token.Type.TABLE, "after CREATE");
        String name = expect(Token.Type.IDENTIFIER, "table name").text;
        expect(Token.Type.LPAREN, "before column list");
        List<ColumnDef> cols = new ArrayList<>();
        do {
            String col = expect(Token.Type.IDENTIFIER, "column name").text;
            String type = expect(Token.Type.IDENTIFIER, "column type").text.toUpperCase();
            ColumnDef.ColType ct;
            if (type.equals("INT") || type.equals("INTEGER") || type.equals("BIGINT")) ct = ColumnDef.ColType.INT;
            else if (type.equals("TEXT") || type.equals("VARCHAR") || type.equals("STRING")) ct = ColumnDef.ColType.TEXT;
            else throw new RuntimeException("Unknown column type '" + type + "' (use INT or TEXT)");
            if (accept(Token.Type.LPAREN)) { // tolerate VARCHAR(255)
                expect(Token.Type.NUMBER, "length");
                expect(Token.Type.RPAREN, "after length");
            }
            cols.add(new ColumnDef(col, ct));
        } while (accept(Token.Type.COMMA));
        expect(Token.Type.RPAREN, "after column list");
        String engine = "BTREE";
        if (check(Token.Type.IDENTIFIER) && peek().text.equalsIgnoreCase("USING")) {
            advance();
            engine = expect(Token.Type.IDENTIFIER, "engine name after USING").text.toUpperCase();
        }
        return new CreateTableStatement(name, cols, engine);
    }

    private Statement parseInsert() {
        expect(Token.Type.INSERT, "");
        expect(Token.Type.INTO, "after INSERT");
        String name = expect(Token.Type.IDENTIFIER, "table name").text;
        expect(Token.Type.VALUES, "after table name");
        List<List<Object>> rows = new ArrayList<>();
        do {
            expect(Token.Type.LPAREN, "before values");
            List<Object> row = new ArrayList<>();
            do {
                row.add(parseLiteral());
            } while (accept(Token.Type.COMMA));
            expect(Token.Type.RPAREN, "after values");
            rows.add(row);
        } while (accept(Token.Type.COMMA));
        return new InsertStatement(name, rows);
    }

    private Statement parseSelect() {
        expect(Token.Type.SELECT, "");
        List<String> cols = new ArrayList<>();
        boolean count = false;
        if (accept(Token.Type.STAR)) {
            cols.add("*");
        } else if (check(Token.Type.IDENTIFIER) && peek().text.equalsIgnoreCase("count") && peekAt(1).type == Token.Type.LPAREN) {
            advance();
            advance();
            expect(Token.Type.STAR, "inside COUNT(");
            expect(Token.Type.RPAREN, "after COUNT(*");
            count = true;
        } else {
            do {
                cols.add(expect(Token.Type.IDENTIFIER, "column name").text);
            } while (accept(Token.Type.COMMA));
        }
        expect(Token.Type.FROM, "after select list");
        String table = expect(Token.Type.IDENTIFIER, "table name").text;
        List<Condition> where = parseOptionalWhere();
        String orderBy = null;
        boolean desc = false;
        if (accept(Token.Type.ORDER)) {
            expect(Token.Type.BY, "after ORDER");
            orderBy = expect(Token.Type.IDENTIFIER, "ORDER BY column").text;
            if (accept(Token.Type.DESC)) desc = true;
            else accept(Token.Type.ASC);
        }
        long limit = -1;
        if (accept(Token.Type.LIMIT)) {
            Token n = expect(Token.Type.NUMBER, "after LIMIT");
            limit = Long.parseLong(n.text);
            if (limit < 0) throw new RuntimeException("LIMIT must be non-negative");
        }
        return new SelectStatement(cols, count, table, where, orderBy, desc, limit);
    }

    private Statement parseUpdate() {
        expect(Token.Type.UPDATE, "");
        String table = expect(Token.Type.IDENTIFIER, "table name").text;
        expect(Token.Type.SET, "after table name");
        List<Assignment> sets = new ArrayList<>();
        do {
            String col = expect(Token.Type.IDENTIFIER, "column name").text;
            expect(Token.Type.EQ, "after column in SET");
            sets.add(new Assignment(col, parseLiteral()));
        } while (accept(Token.Type.COMMA));
        return new UpdateStatement(table, sets, parseOptionalWhere());
    }

    private Statement parseDelete() {
        expect(Token.Type.DELETE, "");
        expect(Token.Type.FROM, "after DELETE");
        String table = expect(Token.Type.IDENTIFIER, "table name").text;
        return new DeleteStatement(table, parseOptionalWhere());
    }

    private List<Condition> parseOptionalWhere() {
        List<Condition> conds = new ArrayList<>();
        if (!accept(Token.Type.WHERE)) return conds;
        do {
            String col = expect(Token.Type.IDENTIFIER, "condition column").text;
            Condition.Op op = parseOp();
            conds.add(new Condition(col, op, parseLiteral()));
        } while (accept(Token.Type.AND));
        return conds;
    }

    private Condition.Op parseOp() {
        Token t = advance();
        switch (t.type) {
            case EQ: return Condition.Op.EQ;
            case NEQ: return Condition.Op.NEQ;
            case LT: return Condition.Op.LT;
            case LTE: return Condition.Op.LTE;
            case GT: return Condition.Op.GT;
            case GTE: return Condition.Op.GTE;
            default: throw new RuntimeException("Parse error: expected comparison operator, got '" + t.text + "'");
        }
    }

    private Object parseLiteral() {
        Token t = advance();
        if (t.type == Token.Type.NUMBER) {
            try {
                return t.text.contains(".") ? (Object) Double.parseDouble(t.text) : (Object) Long.parseLong(t.text);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Invalid number literal: " + t.text);
            }
        }
        if (t.type == Token.Type.STRING || t.type == Token.Type.IDENTIFIER) return t.text;
        throw new RuntimeException("Parse error: expected a literal value, got '" + t.text + "'");
    }
}
