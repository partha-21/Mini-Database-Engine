package minidb.sql.ast;

import java.util.List;

public class CreateTableStatement implements Statement {
    public final String tableName;
    public final List<ColumnDef> columns;
    public final String indexEngine; // BTREE or LSM

    public CreateTableStatement(String tableName, List<ColumnDef> columns, String indexEngine) {
        this.tableName = tableName;
        this.columns = columns;
        this.indexEngine = indexEngine;
    }
}
