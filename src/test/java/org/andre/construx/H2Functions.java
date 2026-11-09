package org.andre.construx;

/** Postgres built-ins the migrations use that H2 lacks; registered by {@code db/h2/beforeMigrate.sql}. */
public final class H2Functions {

    private H2Functions() {}

    public static int numNonnulls(Object... values) {
        int count = 0;
        for (Object value : values) {
            if (value != null) count++;
        }
        return count;
    }
}
