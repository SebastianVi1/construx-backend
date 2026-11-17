package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.importing.XlsxSheets.RawRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One sheet read by its header names (exact after trim), with cell conversions that fail at the cell. */
final class SheetTable {
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final LocalDate NO_DATE = LocalDate.of(1900, 1, 1);
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT));

    final String name;
    private final Map<String, Integer> columns = new HashMap<>();
    private final int headerRow;
    final List<RawRow> rows;

    private SheetTable(String name, RawRow header, List<RawRow> rows) {
        this.name = name;
        this.headerRow = header == null ? 1 : header.number();
        if (header != null) {
            for (int i = 0; i < header.cells().size(); i++) {
                String title = header.cell(i);
                if (title != null && !title.isBlank()) {
                    columns.putIfAbsent(title.strip(), i);
                }
            }
        }
        this.rows = rows;
    }

    /** The sheet with its header on row 1, or {@code MISSING_SHEET}. */
    static SheetTable of(Map<String, List<RawRow>> sheets, String name) {
        List<RawRow> raw = sheets.get(name);
        if (raw == null) {
            throw new ImportFailedException("MISSING_SHEET", name, null, null, "falta la hoja '" + name + "'");
        }
        if (raw.isEmpty() || raw.get(0).number() != 1) {
            return new SheetTable(name, null, raw);
        }
        return new SheetTable(name, raw.get(0), raw.subList(1, raw.size()));
    }

    /** The sheet whose header is the first of its first ten rows naming {@code marker} (a key-value sheet). */
    static SheetTable findingHeader(Map<String, List<RawRow>> sheets, String name, String marker) {
        List<RawRow> raw = sheets.get(name);
        if (raw == null) {
            throw new ImportFailedException("MISSING_SHEET", name, null, null, "falta la hoja '" + name + "'");
        }
        for (int i = 0; i < Math.min(10, raw.size()); i++) {
            RawRow row = raw.get(i);
            if (row.cells().stream().anyMatch(c -> c != null && c.strip().equals(marker))) {
                return new SheetTable(name, row, raw.subList(i + 1, raw.size()));
            }
        }
        return new SheetTable(name, null, raw);
    }

    SheetTable require(String... names) {
        for (String column : names) {
            if (!columns.containsKey(column)) {
                throw new ImportFailedException("MISSING_COLUMN", name, headerRow, column,
                        "falta la columna '" + column + "'");
            }
        }
        return this;
    }

    boolean has(String column) {
        return columns.containsKey(column);
    }

    /** Trimmed text, null when empty or the column is absent. */
    String text(RawRow row, String column) {
        Integer index = columns.get(column);
        if (index == null) {
            return null;
        }
        String value = row.cell(index);
        if (value == null) {
            return null;
        }
        String cleaned = value.strip().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** A code: trimmed and upper-cased; numeric cells such as {@code 301.0} lose the zero fraction. */
    String code(RawRow row, String column) {
        String value = text(row, column);
        if (value == null) {
            return null;
        }
        if (value.matches("-?\\d+\\.0+")) {
            value = value.substring(0, value.indexOf('.'));
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /** A number or null when empty; text that is not a number fails with {@code failCode}. */
    BigDecimal number(RawRow row, String column, String failCode) {
        String value = text(row, column);
        if (value == null) {
            return null;
        }
        try {
            return BigDecimal.valueOf(Double.parseDouble(value.replace(",", "")));
        } catch (NumberFormatException ex) {
            throw fail(failCode, row, column, "'" + value + "' no es un número");
        }
    }

    /** A number that is present and not negative. */
    BigDecimal nonNegative(RawRow row, String column, String failCode) {
        BigDecimal value = number(row, column, failCode);
        if (value == null) {
            throw fail(failCode, row, column, "falta el número");
        }
        if (value.signum() < 0) {
            throw fail(failCode, row, column, "el número no puede ser negativo");
        }
        return value;
    }

    /** {@code 1}, {@code true}, {@code verdadero} or {@code si} are true; anything else false. */
    boolean flag(RawRow row, String column) {
        String value = text(row, column);
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("verdadero") || v.equals("si") || v.equals("sí")) {
            return true;
        }
        try {
            return Double.parseDouble(v) != 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** An Excel serial or {@code yyyy/MM/dd} / {@code dd/MM/yyyy} text; 1900-01-01 and serial 1 mean no date. */
    LocalDate date(RawRow row, String column) {
        String value = text(row, column);
        if (value == null) {
            return null;
        }
        LocalDate date = null;
        try {
            double serial = Double.parseDouble(value);
            if (serial >= 1 && serial < 2958466) {
                date = EXCEL_EPOCH.plusDays((long) Math.floor(serial));
                if (serial < 2) {
                    date = NO_DATE;
                }
            }
        } catch (NumberFormatException ex) {
            String day = value.split(" ")[0];
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    date = LocalDate.parse(day, format);
                    break;
                } catch (DateTimeParseException ignored) {
                    // next format
                }
            }
        }
        if (date == null) {
            throw fail("INVALID_DATE", row, column, "'" + value + "' no es una fecha");
        }
        return date.equals(NO_DATE) ? null : date;
    }

    ImportFailedException fail(String code, RawRow row, String column, String detail) {
        return new ImportFailedException(code, name, row.number(), column, detail);
    }
}
