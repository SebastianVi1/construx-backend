package org.andre.construx.modules.pricing.importing;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small synthetic PU Win+ FASAR workbook built with POI (docs/pricing.md §5.2a: no Neodata data is committed),
 * with the blocks at the documented places: {@code uma}/{@code uma_cap} named cells on sheet {@code uma},
 * {@code a)TablaFactores} PATRON / TRABAJADOR rows 6-7 (concepts in columns C-N, minimum wage and the SBC cap
 * under their headers in row 5, factor rounding in H11, year in F2), {@code b) CapturaDatos} rows 16-38 with the
 * 8-hour column E (percentages of 2.2/2.3 in D) and {@code d)Salarios} with its header in row 6 (codes in C).
 * Every value is a test parameter that tests change to reach each failure and warning.
 */
public final class SyntheticFasar {
    public static final String[] CONCEPTS = {"RISK", "FIXED_FEE", "EXCESS_3_UMA", "CASH_BENEFITS",
            "PENSIONERS_MEDICAL", "DISABILITY_LIFE", "RETIREMENT", "UNEMPLOYMENT_OLD_AGE", "DAYCARE", "INFONAVIT",
            "PAYROLL_TAX", "OTHER_CHARGES"};

    public Object year = Year.now().getValue();
    public Object umaCap = 100.0;
    public Object uma = 100.0;
    public Object minimumWage = 200.0;
    public Object sbcCapUma = 25.0;
    public Object factorRounding = 6;
    /** Employer and worker rates by concept, in {@link #CONCEPTS} order. */
    public final Object[] employer = {0.05, 0.2, 0.01, 0.007, 0.01, 0.0175, 0.02, 0.0315, 0.01, 0.05, 0.03, 0.0};
    public final Object[] worker = {0.0, 0.0, 0.004, 0.0025, 0.00375, 0.00625, 0.0, 0.01125, 0.0, 0.0, 0.0, 0.0};
    /** Day rows of {@code b) CapturaDatos} by row code, 8-hour column. */
    public final Map<String, Object> days = new LinkedHashMap<>();
    public Object vacationPremiumPct = 0.25;
    public Object sundayPremiumPct = 0.25;
    /** Días equivalentes of the Sunday premium (row 2.3): 4 worked Sundays x 25 %. */
    public Object sundayPremiumDays = 1.0;
    public final List<Object[]> salaries = new ArrayList<>();
    public boolean xlsx;
    public boolean withNames = true;
    public boolean withPatronRow = true;
    public boolean withSalaryHeader = true;
    public final List<String> withoutSheets = new ArrayList<>();
    public final List<String> withoutDayRows = new ArrayList<>();

    public SyntheticFasar() {
        days.put("CAL", 365);
        days.put("1.1", 52);
        days.put("1.2", 6);
        days.put("1.3", 3);
        days.put("1.4", 7);
        days.put("1.5", 3);
        days.put("1.6", 4);
        days.put("1.7", 0);
        days.put("1.8", 0);
        days.put("2.1", 15);
        days.put("2.2", 1.5);
        // code, description, Sn, FSBC, FSR, real wage (the file's own numbers, used only for verification)
        salaries.add(new Object[] {"MO011", "PEON", 350, 1.04, 1.7, 595.0});
        salaries.add(new Object[] {"MO020", "ALBAÑIL", 500, 1.04, 1.7, 850.0});
    }

    public byte[] bytes() {
        try (Workbook book = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("N_Campos Generales").createRow(0).createCell(0).setCellValue("NOMBRE DE CELDA");
            umaSheet(book);
            factors(book);
            capture(book);
            salariesSheet(book);
            for (String name : withoutSheets) {
                book.removeSheetAt(book.getSheetIndex(name));
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Path file(Path dir) {
        try {
            Path path = Files.createTempFile(dir, "fasar", xlsx ? ".xlsx" : ".xls");
            Files.write(path, bytes());
            return path;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void umaSheet(Workbook book) {
        Sheet sheet = book.createSheet("uma");
        put(sheet, 3, 'C', "uma_cap");
        put(sheet, 3, 'D', umaCap);
        put(sheet, 4, 'C', "uma");
        put(sheet, 4, 'D', uma);
        if (withNames) {
            Name cap = book.createName();
            cap.setNameName("UMA_CAP");
            cap.setRefersToFormula("uma!$D$3");
            Name daily = book.createName();
            daily.setNameName("UMA");
            daily.setRefersToFormula("uma!$D$4");
        }
    }

    private void factors(Workbook book) {
        Sheet sheet = book.createSheet("a)TablaFactores");
        put(sheet, 2, 'E', "AÑO PARA CAPTURA DE FACTORES:");
        put(sheet, 2, 'F', year);
        put(sheet, 4, 'C', "I. RIESGO");
        put(sheet, 5, 'C', "DE TRABAJO");
        put(sheet, 5, 'D', "Cuota Fija");
        put(sheet, 5, 'P', "SALARIO\n MINIMO: ");
        put(sheet, 5, 'Q', "Limite superior grupo 1:");
        put(sheet, 5, 'R', "Limite superior grupo 2:");
        if (withPatronRow) {
            put(sheet, 6, 'B', "PATRON");
        }
        put(sheet, 7, 'B', "TRABAJADOR");
        for (int i = 0; i < CONCEPTS.length; i++) {
            put(sheet, 6, (char) ('C' + i), employer[i]);
            put(sheet, 7, (char) ('C' + i), worker[i]);
        }
        put(sheet, 6, 'P', minimumWage);
        put(sheet, 6, 'Q', 25);
        put(sheet, 6, 'R', sbcCapUma);
        put(sheet, 9, 'B', "TOTAL CUOTA");
        put(sheet, 11, 'G', "REDONDEO PARA FACTORES:");
        put(sheet, 11, 'H', factorRounding);
    }

    private void capture(Workbook book) {
        Sheet sheet = book.createSheet("b) CapturaDatos");
        put(sheet, 15, 'E', "Jornadas 8 Horas");
        put(sheet, 15, 'F', "Jornadas Horas Extras");
        if (!withoutDayRows.contains("CAL")) {
            put(sheet, 16, 'B', "Días Calendario Anualizado");
            put(sheet, 16, 'E', days.get("CAL"));
            put(sheet, 16, 'F', 365);
        }
        put(sheet, 17, 'B', "1.   Días no Trabajados");
        int row = 18;
        for (String code : List.of("1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8")) {
            dayRow(sheet, row++, code, null, days.get(code));
        }
        put(sheet, 28, 'B', "2.   Días pagados por LFT");
        dayRow(sheet, 29, "2.1", null, days.get("2.1"));
        dayRow(sheet, 30, "2.2", vacationPremiumPct, days.get("2.2"));
        dayRow(sheet, 31, "2.3", sundayPremiumPct, sundayPremiumDays);
        put(sheet, 34, 'B', 2.4);
        put(sheet, 34, 'D', "Total de Días Equivalentes por Horas extras");
        put(sheet, 34, 'F', 119.25);
        put(sheet, 35, 'B', 2.5);
        put(sheet, 35, 'E', 0.03);
        put(sheet, 40, 'A', "Marco jurídico");
    }

    private void dayRow(Sheet sheet, int row, String code, Object pct, Object value) {
        if (withoutDayRows.contains(code)) {
            return;
        }
        // the real workbook stores row codes as numbers (1.1000000000000001)
        put(sheet, row, 'B', Double.parseDouble(code));
        put(sheet, row, 'C', "Concepto " + code);
        if (pct != null) {
            put(sheet, row, 'D', pct);
        }
        put(sheet, row, 'E', value);
        put(sheet, row, 'F', value);
    }

    private void salariesSheet(Workbook book) {
        Sheet sheet = book.createSheet("d)Salarios");
        put(sheet, 3, 'C', "JORNADAS LABORABLES DE 8 HORAS (SALARIOS NORMALES)");
        put(sheet, 5, 'E', "SALARIO ");
        put(sheet, 5, 'F', "FACTOR");
        put(sheet, 6, 'B', "No");
        if (withSalaryHeader) {
            put(sheet, 6, 'C', "CODIGO");
        }
        put(sheet, 6, 'D', "DESCRIPCION DE CATEGORIA");
        put(sheet, 6, 'E', "NOMINAL Sn ");
        put(sheet, 6, 'F', "SALARIO BASE COTIZACION");
        put(sheet, 6, 'G', "SALARIO REAL");
        put(sheet, 6, 'H', "SALARIO REAL");
        if (withSalaryHeader) {
            put(sheet, 6, 'K', "CODIGO");
        }
        int row = 7;
        for (Object[] s : salaries) {
            put(sheet, row, 'B', row - 6);
            for (int i = 0; i < s.length; i++) {
                put(sheet, row, (char) ('C' + i), s[i]);
            }
            put(sheet, row, 'K', "");
            row++;
        }
        // a row without code is skipped
        put(sheet, row, 'B', row - 6);
        put(sheet, row, 'E', 0);
    }

    /** One cell by 1-based row and column letter; a {@link Number} is numeric, anything else text. */
    private static void put(Sheet sheet, int row, char column, Object value) {
        if (value == null) {
            return;
        }
        Row r = sheet.getRow(row - 1) != null ? sheet.getRow(row - 1) : sheet.createRow(row - 1);
        Cell cell = r.createCell(column - 'A');
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(value.toString());
        }
    }
}
