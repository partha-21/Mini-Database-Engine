package minidb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts SQL text into tokens. Keywords are case-insensitive; '' escapes a quote in strings. */
public class Lexer {
    private static final Map<String, Token.Type> KEYWORDS = Map.ofEntries(
            Map.entry("SELECT", Token.Type.SELECT), Map.entry("INSERT", Token.Type.INSERT),
            Map.entry("INTO", Token.Type.INTO), Map.entry("VALUES", Token.Type.VALUES),
            Map.entry("FROM", Token.Type.FROM), Map.entry("WHERE", Token.Type.WHERE),
            Map.entry("AND", Token.Type.AND), Map.entry("CREATE", Token.Type.CREATE),
            Map.entry("TABLE", Token.Type.TABLE), Map.entry("DELETE", Token.Type.DELETE),
            Map.entry("UPDATE", Token.Type.UPDATE), Map.entry("SET", Token.Type.SET),
            Map.entry("ORDER", Token.Type.ORDER), Map.entry("BY", Token.Type.BY),
            Map.entry("LIMIT", Token.Type.LIMIT), Map.entry("ASC", Token.Type.ASC),
            Map.entry("DESC", Token.Type.DESC), Map.entry("BEGIN", Token.Type.BEGIN),
            Map.entry("COMMIT", Token.Type.COMMIT), Map.entry("ROLLBACK", Token.Type.ROLLBACK),
            Map.entry("EXPLAIN", Token.Type.EXPLAIN), Map.entry("CHECKPOINT", Token.Type.CHECKPOINT),
            Map.entry("SHOW", Token.Type.SHOW)
    );

    private final String src;
    private int pos = 0;

    public Lexer(String src) {
        this.src = src;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
            if (pos >= src.length()) {
                tokens.add(new Token(Token.Type.EOF, ""));
                return tokens;
            }
            char c = src.charAt(pos);
            if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                pos += 2;
                while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') pos++;
                continue;
            }
            if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                int start = pos;
                pos += 2;
                while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) pos++;
                if (pos + 1 >= src.length()) throw new RuntimeException("Unterminated block comment starting at position " + start);
                pos += 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') tokens.add(readWord());
            else if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) tokens.add(readNumber());
            else if (c == '\'') tokens.add(readString());
            else tokens.add(readSymbol());
        }
    }

    private Token readWord() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        String text = src.substring(start, pos);
        Token.Type kw = KEYWORDS.get(text.toUpperCase());
        return new Token(kw != null ? kw : Token.Type.IDENTIFIER, text);
    }

    private Token readNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
        return new Token(Token.Type.NUMBER, src.substring(start, pos));
    }

    private Token readString() {
        int startPos = pos;
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new RuntimeException("Unterminated string literal starting at position " + startPos);
            char c = src.charAt(pos++);
            if (c == '\'') {
                if (pos < src.length() && src.charAt(pos) == '\'') { // escaped quote
                    sb.append('\'');
                    pos++;
                } else {
                    break;
                }
            } else {
                sb.append(c);
            }
        }
        return new Token(Token.Type.STRING, sb.toString());
    }

    private boolean next(char expected) {
        if (pos < src.length() && src.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private Token readSymbol() {
        char c = src.charAt(pos++);
        switch (c) {
            case '*': return new Token(Token.Type.STAR, "*");
            case ',': return new Token(Token.Type.COMMA, ",");
            case '(': return new Token(Token.Type.LPAREN, "(");
            case ')': return new Token(Token.Type.RPAREN, ")");
            case ';': return new Token(Token.Type.SEMICOLON, ";");
            case '=': return new Token(Token.Type.EQ, "=");
            case '<':
                if (next('=')) return new Token(Token.Type.LTE, "<=");
                if (next('>')) return new Token(Token.Type.NEQ, "<>");
                return new Token(Token.Type.LT, "<");
            case '>':
                if (next('=')) return new Token(Token.Type.GTE, ">=");
                return new Token(Token.Type.GT, ">");
            case '!':
                if (next('=')) return new Token(Token.Type.NEQ, "!=");
                throw new RuntimeException("Unexpected '!' at position " + (pos - 1));
            default:
                throw new RuntimeException("Unexpected character '" + c + "' at position " + (pos - 1));
        }
    }
}
