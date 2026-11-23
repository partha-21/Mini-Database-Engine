package minidb.sql.ast;

public class ColumnDef {
    public enum ColType { INT, TEXT }

    public final String name;
    public final ColType type;

    public ColumnDef(String name, ColType type) {
        this.name = name;
        this.type = type;
    }
}
