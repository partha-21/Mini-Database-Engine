package minidb.sql.ast;

public class Assignment {
    public final String column;
    public final Object value;

    public Assignment(String column, Object value) {
        this.column = column;
        this.value = value;
    }
}
