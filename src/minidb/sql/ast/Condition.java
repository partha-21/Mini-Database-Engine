package minidb.sql.ast;

/** A single "column OP literal" comparison. Conditions in a WHERE are AND-ed. */
public class Condition {
    public enum Op { EQ, NEQ, LT, LTE, GT, GTE }

    public final String column;
    public final Op op;
    public final Object value; // Long, Double or String

    public Condition(String column, Op op, Object value) {
        this.column = column;
        this.op = op;
        this.value = value;
    }

    @Override
    public String toString() {
        return column + " " + op + " " + value;
    }
}
