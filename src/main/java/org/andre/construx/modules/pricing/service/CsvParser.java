package org.andre.construx.modules.pricing.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 reader: {@code ,} separator, fields optionally wrapped in double quotes, {@code ""}
 * for a quote inside a quoted field, quoted fields may span lines, CRLF, LF or CR line ends and an optional
 * leading byte order mark. Blank lines are skipped but still counted.
 */
final class CsvParser {
    private CsvParser() {
    }

    /** {@code line} is the 1-based physical line where the record starts. */
    record CsvRecord(int line, List<String> fields) {}

    static final class CsvFormatException extends RuntimeException {
        private final int line;

        CsvFormatException(int line, String message) {
            super(message);
            this.line = line;
        }

        int getLine() {
            return line;
        }
    }

    static List<CsvRecord> parse(String text) {
        List<CsvRecord> records = new ArrayList<>();
        int i = text.startsWith("﻿") ? 1 : 0;
        int n = text.length();
        int line = 1;
        while (i < n) {
            int start = line;
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quotedField = false;
            boolean ended = false;
            while (i < n && !ended) {
                char c = text.charAt(i);
                if (c == '"' && field.isEmpty() && !quotedField) {
                    quotedField = true;
                    i++;
                    while (true) {
                        if (i >= n) {
                            throw new CsvFormatException(start, "Unterminated quoted field starting on line " + start);
                        }
                        char q = text.charAt(i);
                        if (q == '"') {
                            if (i + 1 < n && text.charAt(i + 1) == '"') {
                                field.append('"');
                                i += 2;
                                continue;
                            }
                            i++;
                            break;
                        }
                        if (q == '\n' || (q == '\r' && (i + 1 >= n || text.charAt(i + 1) != '\n'))) {
                            line++;
                        }
                        field.append(q);
                        i++;
                    }
                    if (i < n && text.charAt(i) != ',' && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                        throw new CsvFormatException(line, "Unexpected text after a closing quote on line " + line);
                    }
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    quotedField = false;
                    i++;
                } else if (c == '\n' || c == '\r') {
                    i += c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n' ? 2 : 1;
                    line++;
                    ended = true;
                } else {
                    field.append(c);
                    i++;
                }
            }
            fields.add(field.toString());
            boolean blank = fields.size() == 1 && fields.get(0).isEmpty() && !quotedField;
            if (!blank) {
                records.add(new CsvRecord(start, List.copyOf(fields)));
            }
        }
        return records;
    }
}
