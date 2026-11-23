package minidb.sql.ast;

public class ExplainStatement implements Statement {
    public final Statement inner;

    public ExplainStatement(Statement inner) {
        this.inner = inner;
    }
}
