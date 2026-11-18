package org.andre.construx.modules.pricing.importing;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streams the wanted sheets of an {@code .xlsx} with SAX (docs/pricing.md §4.5), keeping each cell's raw value:
 * the shared or inline string, or the number as Excel stored it. Other sheets ({@code Partidas},
 * {@code Presupuesto}) are never parsed.
 */
final class XlsxSheets {

    /** One non-empty row: its 1-based number and its cells by 0-based column, null when empty. */
    record RawRow(int number, List<String> cells) {
        String cell(int column) {
            return column < cells.size() ? cells.get(column) : null;
        }
    }

    private XlsxSheets() {
    }

    /**
     * The rows of every wanted sheet found, by trimmed sheet name. {@code rowLimits} caps the data rows of a sheet
     * ({@code TOO_MANY_ROWS}); a file that is not a readable workbook fails with {@code WORKBOOK_UNREADABLE}.
     */
    static Map<String, List<RawRow>> read(File file, Set<String> wanted, Map<String, Integer> rowLimits) {
        Map<String, List<RawRow>> sheets = new HashMap<>();
        try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                try (InputStream sheet = it.next()) {
                    String name = it.getSheetName().strip();
                    if (!wanted.contains(name) || sheets.containsKey(name)) {
                        continue;
                    }
                    Integer limit = rowLimits.get(name);
                    RowHandler handler = new RowHandler(strings, name, limit == null ? Integer.MAX_VALUE : limit);
                    XMLReader xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(handler);
                    xml.parse(new InputSource(sheet));
                    sheets.put(name, handler.rows);
                }
            }
        } catch (ImportFailedException ex) {
            throw ex;
        } catch (Exception | LinkageError ex) {
            throw new ImportFailedException("WORKBOOK_UNREADABLE", null, null, null,
                    "el archivo no es un libro de Excel (.xlsx) legible");
        }
        return sheets;
    }

    private static final class RowHandler extends DefaultHandler {
        private final ReadOnlySharedStringsTable strings;
        private final String sheet;
        private final int limit;
        private final List<RawRow> rows = new ArrayList<>();
        private List<String> cells;
        private int rowNumber;
        private int column;
        private String type;
        private boolean inValue;
        private final StringBuilder value = new StringBuilder();
        private boolean hasValue;

        RowHandler(ReadOnlySharedStringsTable strings, String sheet, int limit) {
            this.strings = strings;
            this.sheet = sheet;
            this.limit = limit;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            switch (localName) {
                case "row" -> {
                    String r = attrs.getValue("r");
                    rowNumber = r == null ? rowNumber + 1 : Integer.parseInt(r);
                    cells = new ArrayList<>();
                    column = -1;
                }
                case "c" -> {
                    String ref = attrs.getValue("r");
                    column = ref == null ? column + 1 : columnOf(ref);
                    type = attrs.getValue("t");
                    value.setLength(0);
                    hasValue = false;
                }
                case "v", "t" -> {
                    inValue = true;
                    hasValue = true;
                }
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (localName) {
                case "v", "t" -> inValue = false;
                case "c" -> {
                    if (hasValue) {
                        put(column, resolve());
                    }
                }
                case "row" -> {
                    if (cells.stream().anyMatch(c -> c != null && !c.isBlank())) {
                        if (rows.size() > limit) {
                            throw new ImportFailedException("TOO_MANY_ROWS", sheet, rowNumber, null,
                                    "la hoja tiene más de " + limit + " renglones");
                        }
                        rows.add(new RawRow(rowNumber, cells));
                    }
                }
                default -> {
                }
            }
        }

        private String resolve() {
            String raw = value.toString();
            if ("s".equals(type)) {
                return strings.getItemAt(Integer.parseInt(raw.strip())).getString();
            }
            if ("b".equals(type)) {
                return "1".equals(raw.strip()) ? "1" : "0";
            }
            return raw;
        }

        private void put(int index, String cell) {
            while (cells.size() <= index) {
                cells.add(null);
            }
            cells.set(index, cell);
        }

        private static int columnOf(String ref) {
            int col = 0;
            for (int i = 0; i < ref.length(); i++) {
                char c = ref.charAt(i);
                if (c < 'A' || c > 'Z') {
                    break;
                }
                col = col * 26 + (c - 'A' + 1);
            }
            return col - 1;
        }
    }
}
