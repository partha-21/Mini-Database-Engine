package minidb.sql.ast;

import java.util.List;

public class SelectStatement implements Statement {
    public final List<String> columns; // ["*"] for all
    public final boolean countStar;
    public final String tableName;
    public final List<Condition> where;
    public final String orderBy;       // nullable
    public final boolean orderDesc;
    public final long limit;           // -1 = none

    public SelectStatement(List<String> columns, boolean countStar, String tableName, List<Condition> where,
                           String orderBy, boolean orderDesc, long limit) {
        this.columns = columns;
        this.countStar = countStar;
        this.tableName = tableName;
        this.where = where;
        this.orderBy = orderBy;
        this.orderDesc = orderDesc;
        this.limit = limit;
    }
}
