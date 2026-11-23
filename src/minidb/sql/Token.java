package minidb.sql;

public class Token {
    public enum Type {
        SELECT, INSERT, INTO, VALUES, FROM, WHERE, AND, CREATE, TABLE,
        DELETE, UPDATE, SET, ORDER, BY, LIMIT, ASC, DESC,
        BEGIN, COMMIT, ROLLBACK, EXPLAIN, CHECKPOINT, SHOW,
        IDENTIFIER, NUMBER, STRING,
        STAR, COMMA, LPAREN, RPAREN, SEMICOLON,
        EQ, NEQ, LT, LTE, GT, GTE,
        EOF
    }

    public final Type type;
    public final String text;

    public Token(Type type, String text) {
        this.type = type;
        this.text = text;
    }

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}
