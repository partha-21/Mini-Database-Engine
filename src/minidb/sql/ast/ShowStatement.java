package minidb.sql.ast;

public class ShowStatement implements Statement {
    public final String what; // TABLES or STATS

    public ShowStatement(String what) {
        this.what = what;
    }
}
