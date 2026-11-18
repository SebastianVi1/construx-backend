package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.model.FasarCalculator;
import org.andre.construx.modules.pricing.model.FasarCalculator.Days;
import org.andre.construx.modules.pricing.model.FasarCalculator.Parameters;
import org.andre.construx.modules.pricing.model.FasarCalculator.Rate;
import org.andre.construx.modules.pricing.model.FasarConcept;
import org.andre.construx.modules.pricing.model.PricingRuleException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;

import java.io.File;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The PU Win+ FASAR workbook ({@code .xls} or {@code .xlsx}, small) read into a complete set of FASAR parameters
 * and its salary table (docs/pricing.md §4.5, §5.2). Only the 8-hour working day is read. Where each value is:
 * <ul>
 *   <li>the {@code UMA_CAP} and {@code UMA} named cells (daily UMA in pesos, and the UMA the workbook computes
 *       with);</li>
 *   <li>{@code a)TablaFactores}: the {@code PATRON} and {@code TRABAJADOR} rows, the twelve cells to the right of
 *       the label being the concepts in their sheet order (I to V, INFONAVIT, ISN, Otros cargos); the minimum wage
 *       and {@code Limite superior grupo 2} in the {@code PATRON} row under their headers;
 *       {@code AÑO PARA CAPTURA DE FACTORES} and {@code REDONDEO PARA FACTORES} in the cell right of their
 *       label;</li>
 *   <li>{@code b) CapturaDatos} rows 16-38, in the column headed {@code Jornadas 8 Horas}: the calendar days and the
 *       rows numbered 1.1-1.8, 2.1, 2.2 and 2.3; the premium percentages of 2.2 and 2.3 in the cell left of
 *       it;</li>
 *   <li>{@code d)Salarios}: header row 6, from row 7, the columns from {@code CODIGO} on (code, description, Sn,
 *       FSBC, FSR, real wage); rows without code are skipped.</li>
 * </ul>
 * Contribution rates are the employer and worker sides (summed in the calculation, as {@code TOTAL CUOTA});
 * other charges are the {@code Otros cargos} total; the excess threshold is 3 UMA. Fails with
 * {@code FASAR_MISSING_SHEET}, {@code FASAR_MISSING_BLOCK} or {@code FASAR_INVALID_NUMBER}.
 */
public final class FasarFile {
    static final String UMA = "uma";
    static final String FACTORS = "a)TablaFactores";
    static final String CAPTURE = "b) CapturaDatos";
    static final String SALARIES = "d)Salarios";
    private static final int CAPTURE_FIRST_ROW = 16;
    private static final int CAPTURE_LAST_ROW = 38;
    private static final int SALARY_HEADER_ROW = 6;
    private static final int LABEL_SEARCH_ROWS = 40;
    private static final List<String> DAY_CODES = List.of("1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8",
            "2.1", "2.2", "2.3");
    /** {@code a)TablaFactores} column order, I. Riesgo de trabajo to Otros cargos. */
    private static final FasarConcept[] CONCEPT_ORDER = {FasarConcept.RISK, FasarConcept.FIXED_FEE,
            FasarConcept.EXCESS_3_UMA, FasarConcept.CASH_BENEFITS, FasarConcept.PENSIONERS_MEDICAL,
            FasarConcept.DISABILITY_LIFE, FasarConcept.RETIREMENT, FasarConcept.UNEMPLOYMENT_OLD_AGE,
            FasarConcept.DAYCARE, FasarConcept.INFONAVIT, FasarConcept.PAYROLL_TAX, FasarConcept.OTHER_CHARGES};
    private static final BigDecimal DEFAULT_SBC_CAP = new BigDecimal("25");
    private static final BigDecimal EXCESS_THRESHOLD = new BigDecimal("3");

    /** One category of {@code d)Salarios}; the numbers are the file's, kept for verification. */
    public record Salary(int row, String code, String name, BigDecimal nominalWage, BigDecimal fsr,
                         BigDecimal realWage) {}

    private final Integer year;
    private final BigDecimal umaCap;
    private final BigDecimal minimumWage;
    private final Parameters parameters;
    private final List<Salary> salaries;

    private FasarFile(Integer year, BigDecimal umaCap, BigDecimal minimumWage, Parameters parameters,
                      List<Salary> salaries) {
        this.year = year;
        this.umaCap = umaCap;
        this.minimumWage = minimumWage;
        this.parameters = parameters;
        this.salaries = salaries;
    }

    public Integer year() {
        return year;
    }

    /** The daily UMA in pesos, the set's {@code uma}. */
    public BigDecimal umaCap() {
        return umaCap;
    }

    public BigDecimal minimumWage() {
        return minimumWage;
    }

    /** A complete set of parameters, already checked ({@code Tl > 0}). */
    public Parameters parameters() {
        return parameters;
    }

    public List<Salary> salaries() {
        return salaries;
    }

    public static FasarFile read(File file) {
        Workbook book;
        try {
            book = WorkbookFactory.create(file, null, true);
        } catch (Exception | LinkageError ex) {
            throw new ImportFailedException("WORKBOOK_UNREADABLE", null, null, null,
                    "no se pudo leer el libro FASAR");
        }
        try (book) {
            Sheet umaSheet = sheet(book, UMA);
            Sheet factors = sheet(book, FACTORS);
            Sheet capture = sheet(book, CAPTURE);
            Sheet salaries = sheet(book, SALARIES);
            BigDecimal umaCap = named(book, "UMA_CAP");
            named(book, "UMA");
            Factors f = factors(factors);
            Map<String, BigDecimal[]> days = capture(capture);
            BigDecimal uma = umaCap.setScale(4, RoundingMode.HALF_UP);
            if (uma.signum() <= 0) {
                throw invalid(UMA, null, "UMA_CAP", "la UMA debe ser mayor que cero");
            }
            Parameters parameters = parameters(uma, f, days);
            return new FasarFile(f.year, uma, f.minimumWage, parameters, salaries(salaries));
        } catch (java.io.IOException ex) {
            throw new ImportFailedException("WORKBOOK_UNREADABLE", null, null, null,
                    "no se pudo leer el libro FASAR");
        }
    }

    private record Factors(Integer year, BigDecimal minimumWage, BigDecimal sbcCapUma, int factorRounding,
                           Map<FasarConcept, Rate> rates) {}

    private static Parameters parameters(BigDecimal uma, Factors f, Map<String, BigDecimal[]> days) {
        BigDecimal vacationPct = days.get("2.2")[0];
        BigDecimal sundayPct = days.get("2.3")[0];
        BigDecimal sundayPremiumDays = days.get("2.3")[1];
        BigDecimal workedSundays = sundayPct.signum() == 0 ? BigDecimal.ZERO
                : sundayPremiumDays.divide(sundayPct, MathContext.DECIMAL64).setScale(3, RoundingMode.HALF_UP);
        Days d = new Days(days.get("CAL")[1], days.get("1.1")[1], workedSundays, days.get("1.2")[1],
                days.get("1.3")[1], days.get("1.4")[1], days.get("1.5")[1], days.get("1.6")[1], days.get("1.7")[1],
                days.get("1.8")[1], days.get("2.1")[1]);
        Rate other = f.rates.get(FasarConcept.OTHER_CHARGES);
        BigDecimal otherCharges = other.employer().add(other.worker());
        if (otherCharges.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(FACTORS, null, "Otros Cargos", "los otros cargos deben ser una fracción de 0 a 1");
        }
        Parameters p = new Parameters(uma, true, d, vacationPct, sundayPct, otherCharges, EXCESS_THRESHOLD,
                f.sbcCapUma, f.factorRounding, f.rates);
        if (d.calendar().signum() <= 0) {
            throw invalid(CAPTURE, null, "Días Calendario", "los días calendario deben ser mayores que cero");
        }
        try {
            new FasarCalculator(p);
        } catch (PricingRuleException ex) {
            throw invalid(CAPTURE, null, null, "los días no trabajados no dejan días laborados");
        }
        return p;
    }

    private static Factors factors(Sheet sheet) {
        Row patron = labelRow(sheet, "PATRON");
        Row worker = labelRow(sheet, "TRABAJADOR");
        if (patron == null || worker == null) {
            throw new ImportFailedException("FASAR_MISSING_BLOCK", FACTORS, null,
                    patron == null ? "PATRON" : "TRABAJADOR", "falta el renglón de cuotas");
        }
        int first = labelColumn(patron, "PATRON") + 1;
        int workerFirst = labelColumn(worker, "TRABAJADOR") + 1;
        Map<FasarConcept, Rate> rates = new EnumMap<>(FasarConcept.class);
        for (int i = 0; i < CONCEPT_ORDER.length; i++) {
            rates.put(CONCEPT_ORDER[i], new Rate(rate(sheet, patron, first + i), rate(sheet, worker, workerFirst + i)));
        }
        Integer year = null;
        Cell yearLabel = findLabel(sheet, "ANO PARA CAPTURA DE FACTORES");
        if (yearLabel != null) {
            Cell value = nextValue(sheet.getRow(yearLabel.getRowIndex()), yearLabel.getColumnIndex());
            if (value != null) {
                BigDecimal y = number(sheet, value);
                if (y.stripTrailingZeros().scale() > 0 || y.intValue() < 1900 || y.intValue() > 2999) {
                    throw invalid(FACTORS, value, null, "el año no es válido");
                }
                year = y.intValue();
            }
        }
        Cell wageHeader = findHeaderAbove(sheet, patron.getRowNum(), "SALARIO MINIMO");
        if (wageHeader == null) {
            throw new ImportFailedException("FASAR_MISSING_BLOCK", FACTORS, null, "SALARIO MINIMO",
                    "falta el salario mínimo");
        }
        BigDecimal minimumWage = nonNegative(sheet, patron.getCell(wageHeader.getColumnIndex()), patron,
                wageHeader.getColumnIndex(), 2, null);
        BigDecimal sbcCap = DEFAULT_SBC_CAP;
        Cell capHeader = findHeaderAbove(sheet, patron.getRowNum(), "LIMITE SUPERIOR GRUPO 2");
        if (capHeader != null && patron.getCell(capHeader.getColumnIndex()) != null) {
            sbcCap = nonNegative(sheet, patron.getCell(capHeader.getColumnIndex()), patron,
                    capHeader.getColumnIndex(), 2, null);
            if (sbcCap.signum() == 0) {
                throw invalid(FACTORS, patron.getCell(capHeader.getColumnIndex()), null,
                        "el límite superior debe ser mayor que cero");
            }
        }
        int rounding = 6;
        Cell roundingLabel = findLabel(sheet, "REDONDEO PARA FACTORES");
        if (roundingLabel != null) {
            Cell value = nextValue(sheet.getRow(roundingLabel.getRowIndex()), roundingLabel.getColumnIndex());
            if (value != null) {
                BigDecimal r = number(sheet, value);
                if (r.stripTrailingZeros().scale() > 0 || r.intValue() < 0 || r.intValue() > 8) {
                    throw invalid(FACTORS, value, null, "el redondeo debe ser de 0 a 8 decimales");
                }
                rounding = r.intValue();
            }
        }
        return new Factors(year, minimumWage, sbcCap, rounding, rates);
    }

    /** By code ({@code CAL} for the calendar days): {percentage or null, value of the 8-hour column}. */
    private static Map<String, BigDecimal[]> capture(Sheet sheet) {
        Cell header = null;
        for (int r = 0; r < CAPTURE_LAST_ROW && header == null; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                for (Cell cell : row) {
                    if (key(text(cell)).equals("JORNADAS 8 HORAS")) {
                        header = cell;
                        break;
                    }
                }
            }
        }
        if (header == null) {
            throw new ImportFailedException("FASAR_MISSING_BLOCK", CAPTURE, null, "Jornadas 8 Horas",
                    "falta la columna de jornadas de 8 horas");
        }
        int column = header.getColumnIndex();
        Map<String, BigDecimal[]> days = new HashMap<>();
        for (int r = CAPTURE_FIRST_ROW - 1; r < CAPTURE_LAST_ROW; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String code = null;
            for (int c = 0; c < column && code == null; c++) {
                Cell cell = row.getCell(c);
                code = dayCode(cell);
            }
            if (code == null || days.containsKey(code)) {
                continue;
            }
            BigDecimal pct = null;
            if (code.equals("2.2") || code.equals("2.3")) {
                pct = nonNegative(sheet, row.getCell(column - 1), row, column - 1, 8, BigDecimal.ZERO);
                if (pct.compareTo(BigDecimal.ONE) > 0) {
                    throw invalid(CAPTURE, row.getCell(column - 1), null, "el porcentaje debe ser de 0 a 100 %");
                }
            }
            days.put(code, new BigDecimal[] {pct, nonNegative(sheet, row.getCell(column), row, column, 3,
                    BigDecimal.ZERO)});
        }
        if (!days.containsKey("CAL")) {
            throw new ImportFailedException("FASAR_MISSING_BLOCK", CAPTURE, null, "Días Calendario",
                    "faltan los días calendario");
        }
        for (String code : DAY_CODES) {
            if (!days.containsKey(code)) {
                throw new ImportFailedException("FASAR_MISSING_BLOCK", CAPTURE, null, code,
                        "falta el renglón " + code);
            }
        }
        return days;
    }

    /** {@code CAL} for the calendar-days label, a row code such as {@code 1.1}, or null. */
    private static String dayCode(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        if (type == CellType.NUMERIC) {
            String code = BigDecimal.valueOf(cell.getNumericCellValue()).setScale(1, RoundingMode.HALF_UP)
                    .toPlainString();
            return DAY_CODES.contains(code) ? code : null;
        }
        String text = key(text(cell));
        if (text.startsWith("DIAS CALENDARIO")) {
            return "CAL";
        }
        return DAY_CODES.contains(text) ? text : null;
    }

    private static List<Salary> salaries(Sheet sheet) {
        Row header = sheet.getRow(SALARY_HEADER_ROW - 1);
        int codeColumn = -1;
        if (header != null) {
            for (Cell cell : header) {
                if (key(text(cell)).equals("CODIGO")) {
                    codeColumn = cell.getColumnIndex();
                    break;
                }
            }
        }
        if (codeColumn < 0) {
            throw new ImportFailedException("FASAR_MISSING_BLOCK", SALARIES, SALARY_HEADER_ROW, "CODIGO",
                    "falta el encabezado de la tabla de salarios");
        }
        List<Salary> out = new ArrayList<>();
        for (int r = SALARY_HEADER_ROW; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String code = text(row.getCell(codeColumn)).strip().toUpperCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            out.add(new Salary(r + 1, code, text(row.getCell(codeColumn + 1)).strip(),
                    optional(sheet, row, codeColumn + 2, 2), optional(sheet, row, codeColumn + 4, 8),
                    optional(sheet, row, codeColumn + 5, 2)));
        }
        return out;
    }

    private static Sheet sheet(Workbook book, String name) {
        for (int i = 0; i < book.getNumberOfSheets(); i++) {
            if (book.getSheetName(i).strip().equals(name)) {
                return book.getSheetAt(i);
            }
        }
        throw new ImportFailedException("FASAR_MISSING_SHEET", name, null, null, "falta la hoja");
    }

    private static BigDecimal named(Workbook book, String name) {
        Name defined = book.getName(name);
        if (defined == null) {
            for (Name n : book.getAllNames()) {
                if (n.getNameName().equalsIgnoreCase(name)) {
                    defined = n;
                    break;
                }
            }
        }
        Cell cell = null;
        if (defined != null && defined.getRefersToFormula() != null) {
            try {
                CellReference ref = new AreaReference(defined.getRefersToFormula(), book.getSpreadsheetVersion())
                        .getFirstCell();
                Sheet sheet = ref.getSheetName() == null ? null : book.getSheet(ref.getSheetName());
                Row row = sheet == null ? null : sheet.getRow(ref.getRow());
                cell = row == null ? null : row.getCell(ref.getCol());
            } catch (RuntimeException ex) {
                cell = null;
            }
        }
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            throw new ImportFailedException("FASAR_MISSING_BLOCK", UMA, null, name.toLowerCase(Locale.ROOT),
                    "falta la celda con nombre " + name.toLowerCase(Locale.ROOT));
        }
        BigDecimal value = number(cell.getSheet(), cell);
        if (value.signum() <= 0) {
            throw invalid(UMA, cell, name.toLowerCase(Locale.ROOT), "la UMA debe ser mayor que cero");
        }
        return value;
    }

    private static BigDecimal rate(Sheet sheet, Row row, int column) {
        BigDecimal value = nonNegative(sheet, row.getCell(column), row, column, 8, BigDecimal.ZERO);
        if (value.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(sheet.getSheetName(), row.getCell(column), null, "una cuota es una fracción de 0 a 1");
        }
        return value;
    }

    /**
     * A number of at least zero rounded to {@code decimals}; a blank cell is {@code blank} (null means required).
     */
    private static BigDecimal nonNegative(Sheet sheet, Cell cell, Row row, int column, int decimals,
                                          BigDecimal blank) {
        if (cell == null || cell.getCellType() == CellType.BLANK
                || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) {
            if (blank != null) {
                return blank;
            }
            throw new ImportFailedException("FASAR_INVALID_NUMBER", sheet.getSheetName(), row.getRowNum() + 1,
                    CellReference.convertNumToColString(column), "falta un número");
        }
        BigDecimal value = number(sheet, cell);
        if (value.signum() < 0) {
            throw invalid(sheet.getSheetName(), cell, null, "el número no puede ser negativo");
        }
        return value.setScale(decimals, RoundingMode.HALF_UP);
    }

    private static BigDecimal optional(Sheet sheet, Row row, int column, int decimals) {
        Cell cell = row.getCell(column);
        if (cell == null || cell.getCellType() == CellType.BLANK
                || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) {
            return null;
        }
        return nonNegative(sheet, cell, row, column, decimals, null);
    }

    /** The cell's number (a formula's cached result); text that is not a number is {@code FASAR_INVALID_NUMBER}. */
    private static BigDecimal number(Sheet sheet, Cell cell) {
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        if (type == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (Double.isFinite(value)) {
                return BigDecimal.valueOf(value);
            }
        } else if (type == CellType.STRING) {
            try {
                return new BigDecimal(cell.getStringCellValue().strip().replace(",", ""));
            } catch (NumberFormatException ignored) {
                // falls through
            }
        }
        throw invalid(sheet.getSheetName(), cell, null, "el valor no es un número");
    }

    private static ImportFailedException invalid(String sheet, Cell cell, String column, String detail) {
        return new ImportFailedException("FASAR_INVALID_NUMBER", sheet, cell == null ? null : cell.getRowIndex() + 1,
                cell == null ? column : CellReference.convertNumToColString(cell.getColumnIndex()), detail);
    }

    private static Row labelRow(Sheet sheet, String label) {
        Cell cell = findLabel(sheet, label);
        return cell == null ? null : sheet.getRow(cell.getRowIndex());
    }

    private static int labelColumn(Row row, String label) {
        for (Cell cell : row) {
            if (key(text(cell)).equals(label)) {
                return cell.getColumnIndex();
            }
        }
        throw new IllegalStateException(label);
    }

    /** The first cell of the top rows whose text, without accents, case or punctuation, starts with {@code key}. */
    private static Cell findLabel(Sheet sheet, String label) {
        for (int r = 0; r < LABEL_SEARCH_ROWS; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                String text = key(text(cell));
                if (!text.isEmpty() && (text.equals(label) || text.startsWith(label + " ")
                        || label.length() > 12 && text.startsWith(label))) {
                    return cell;
                }
            }
        }
        return null;
    }

    private static Cell findHeaderAbove(Sheet sheet, int rowIndex, String label) {
        for (int r = 0; r < rowIndex; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (key(text(cell)).startsWith(label)) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** The first non-blank cell right of {@code column}. */
    private static Cell nextValue(Row row, int column) {
        for (int c = column + 1; c <= row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !text(cell).isBlank()) {
                return cell;
            }
        }
        return null;
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /** Upper case, no accents, punctuation and runs of whitespace as single spaces. */
    private static String key(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        return plain.replaceAll("[^A-Z0-9.]+", " ").strip().replaceAll("\\.$", "").strip();
    }
}
