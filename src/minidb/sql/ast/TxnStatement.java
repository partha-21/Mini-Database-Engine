package minidb.sql.ast;

public class TxnStatement implements Statement {
    public enum Kind { BEGIN, COMMIT, ROLLBACK }

    public final Kind kind;

    public TxnStatement(Kind kind) {
        this.kind = kind;
    }
}
