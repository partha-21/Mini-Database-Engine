package minidb.sql.ast;

import java.util.List;

public class InsertStatement implements Statement {
    public final String tableName;
    public final List<List<Object>> rows; // one or more VALUES tuples

    public InsertStatement(String tableName, List<List<Object>> rows) {
        this.tableName = tableName;
        this.rows = rows;
    }
}
