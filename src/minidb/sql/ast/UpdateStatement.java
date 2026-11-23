package minidb.sql.ast;

import java.util.List;

public class UpdateStatement implements Statement {
    public final String tableName;
    public final List<Assignment> assignments;
    public final List<Condition> where;

    public UpdateStatement(String tableName, List<Assignment> assignments, List<Condition> where) {
        this.tableName = tableName;
        this.assignments = assignments;
        this.where = where;
    }
}
