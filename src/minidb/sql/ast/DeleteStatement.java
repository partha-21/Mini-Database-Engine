package minidb.sql.ast;

import java.util.List;

public class DeleteStatement implements Statement {
    public final String tableName;
    public final List<Condition> where;

    public DeleteStatement(String tableName, List<Condition> where) {
        this.tableName = tableName;
        this.where = where;
    }
}
