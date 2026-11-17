package org.andre.construx.modules.pricing.importing;

/**
 * The workbook cannot be imported (docs/pricing.md §4.5): a stable {@code code}, where it was found and a Spanish
 * message built from both, so the job stores it and the dashboard can render its own text from the code.
 */
public class ImportFailedException extends RuntimeException {
    private final String code;
    private final String sheet;
    private final Integer row;
    private final String column;

    public ImportFailedException(String code, String sheet, Integer row, String column, String detail) {
        super(message(sheet, row, column, detail));
        this.code = code;
        this.sheet = sheet;
        this.row = row;
        this.column = column;
    }

    public String getCode() {
        return code;
    }

    public String getSheet() {
        return sheet;
    }

    public Integer getRow() {
        return row;
    }

    public String getColumn() {
        return column;
    }

    private static String message(String sheet, Integer row, String column, String detail) {
        StringBuilder where = new StringBuilder();
        if (sheet != null) {
            where.append("Hoja ").append(sheet);
        }
        if (row != null) {
            where.append(where.isEmpty() ? "Fila " : ", fila ").append(row);
        }
        if (column != null) {
            where.append(where.isEmpty() ? "Columna '" : ", columna '").append(column).append('\'');
        }
        String text = where.isEmpty() ? detail : where + ": " + detail;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
