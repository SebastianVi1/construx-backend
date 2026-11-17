package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.importing.XlsxSheets.RawRow;
import org.andre.construx.modules.pricing.model.ResourceType;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The PU Win+ Presupuesto workbook read into typed rows (docs/pricing.md §4.5). Reading fails on the first broken
 * sheet, column or cell; cell-level warnings ({@code COST_ROUNDED}, {@code NAME_TRUNCATED}, ...) are collected
 * into {@link #warnings}. {@code Partidas} and {@code Presupuesto} are never read.
 */
public final class PresupuestoFile {
    static final String CATALOG = "CatalogoGeneral";
    static final String UNITS = "Unidades";
    static final String FAMILIES = "Familias";
    static final String SUPPLIERS = "Proveedores";
    static final String ANALYSES = "Matrices";
    static final String LABOR = "DetalleManoObra";
    static final String EQUIPMENT = "DetalleMaquinaria";
    static final String OPERATION = "OperacionCH";
    static final String CONSUMPTION = "ConsumosCH";
    static final String STANDBY = "PorcentajesCh";
    static final String GENERAL = "N_Campos Generales";
    static final int CATALOG_ROW_LIMIT = 200_000;
    static final int ANALYSIS_ROW_LIMIT = 500_000;
    static final int NAME_MAX = 160;

    public record FileUnit(String code, String description) {}

    public record FileFamily(String code, String name) {}

    public record FileSupplier(int row, String code, String name, String rfc, String address, String neighborhood,
                               String city, String state, String postalCode, String phone, String mobile, String fax,
                               String email, String contact, String notes, String website, boolean isDefault) {}

    public record FileResource(int row, String code, ResourceType type, String name, String description,
                               String unitCode, String familyCode, String grouper, boolean percentage,
                               Short percentageBase, String supplierCode, String technicalSheet, String reference,
                               String sapCode, boolean discontinued, String procedure, boolean hourlyCost,
                               BigDecimal cost, LocalDate costOn) {}

    public record FileLine(int row, String parentCode, String componentCode, int lineNo, char operator,
                           BigDecimal quantity, String expression) {}

    public record FileLabor(int row, String code, BigDecimal nominalWage, BigDecimal fsr, BigDecimal realWage,
                            BigDecimal fsbc, BigDecimal sbc, BigDecimal perDiem, BigDecimal mealsLodging,
                            BigDecimal funeralExpenses, BigDecimal otherBenefits, Short fasarType) {}

    /** {@code texts} and {@code numbers} by database column of {@code equipment_rate_detail}. */
    public record FileEquipment(int row, String code, Map<String, String> texts, Map<String, BigDecimal> numbers) {}

    public record FileEquipmentLine(int row, String equipmentCode, String componentCode, char operator,
                                    BigDecimal quantity) {}

    public record FileStandby(String charge, BigDecimal standbyPct, BigDecimal reservePct) {}

    /** The FASAR fields of {@code N_Campos Generales} (docs/pricing.md §5.3), already as fractions. */
    public record GeneralFields(BigDecimal minimumWage, BigDecimal retirement, BigDecimal infonavit, BigDecimal risk,
                                BigDecimal payrollTax, BigDecimal otherChargesPct, Boolean sumEmployerAndWorker) {}

    /** {@code equipment_rate_detail} text columns by Neodata header. */
    static final Map<String, String> EQUIPMENT_TEXTS = linked(
            "UnidadPotencia", "power_unit", "CargoFijo", "fixed_charge_code", "ValorAdquisicion",
            "acquisition_value_code", "EqEspera", "standby_code", "EqReserva", "reserve_code", "Llantas", "tires_code",
            "PzaEsp", "special_parts_code", "Combustible", "fuel_code", "Lubricante", "lubricant_code",
            "OtrasFuentes", "other_energy_code", "Marca", "brand", "Modelo", "model", "NumeroSerie", "serial_number",
            "Capacidad", "capacity", "Ubicacion", "location", "VidaUtil", "useful_life", "Propietario", "owner",
            "Disponibilidad", "availability");

    /** {@code equipment_rate_detail} numeric columns by Neodata header, with the column's scale. */
    static final Map<String, String> EQUIPMENT_NUMBERS = linked(
            "VidaEconomicaLlantas", "tires_life_hours:2", "VidaEconomicaPiezas", "special_parts_life_hours:2",
            "VidaEconomicaHrs", "economic_life_hours:2", "HorasAño", "hours_per_year:2", "HorasCambio",
            "oil_change_hours:2", "TipoCalculo", "calculation_type:0", "potencia", "power:4", "foper",
            "operation_factor:6", "TipoCombustible", "fuel_type:0", "FactorCombustible", "fuel_factor:8",
            "FactorOtrasFuentes", "other_energy_factor:8", "FactorLubricante", "lubricant_factor:8",
            "CapacidadCarter", "crankcase_capacity:4", "ValorRescate", "salvage_pct:6", "PrimaSeguros",
            "insurance_pct:6", "FactorMantenimiento", "maintenance_factor:6", "TasaInteresM0", "interest_rate:6",
            "ValorComercial", "commercial_value:2", "CantidadCombustible", "fuel_qty_per_hour:6",
            "CantidadLubricante", "lubricant_qty_per_hour:6");

    final List<FileUnit> units = new ArrayList<>();
    final List<FileFamily> families = new ArrayList<>();
    final List<FileSupplier> suppliers = new ArrayList<>();
    final Map<String, FileResource> resources = new LinkedHashMap<>();
    final List<FileLine> lines = new ArrayList<>();
    final List<FileLabor> labor = new ArrayList<>();
    final List<FileEquipment> equipment = new ArrayList<>();
    final List<FileEquipmentLine> operationLines = new ArrayList<>();
    final List<FileEquipmentLine> consumptionLines = new ArrayList<>();
    final List<FileStandby> standby = new ArrayList<>();
    final Map<String, Integer> rowCounts = new LinkedHashMap<>();
    GeneralFields generalFields;
    final ImportWarnings warnings = new ImportWarnings();

    private PresupuestoFile() {
    }

    /** The rows of the {@code Unidades} sheet. */
    public List<FileUnit> unitsListed() {
        return units;
    }

    /** Reads and checks the workbook; the first failure of §4.5 throws {@link ImportFailedException}. */
    public static PresupuestoFile read(File file) {
        Map<String, List<RawRow>> sheets = XlsxSheets.read(file,
                Set.of(CATALOG, UNITS, FAMILIES, SUPPLIERS, ANALYSES, LABOR, EQUIPMENT, OPERATION, CONSUMPTION,
                        STANDBY, GENERAL),
                Map.of(CATALOG, CATALOG_ROW_LIMIT, ANALYSES, ANALYSIS_ROW_LIMIT));
        PresupuestoFile parsed = new PresupuestoFile();
        SheetTable catalog = SheetTable.of(sheets, CATALOG).require("Codigo", "Descripcion completa", "Descripcion",
                "Unidad", "Tipo", "Familia", "Agrupador", "%", "Tipo %", "Proveedor", "FichaTecnica", "Referencia",
                "CodigoSap", "InsumoDescontinuado", "Procedimiento", "InsumoIntegrado", "EsCostoHorario", "Costo0",
                "Fecha0");
        SheetTable units = SheetTable.of(sheets, UNITS).require("Unidad", "Descripcion");
        SheetTable families = SheetTable.of(sheets, FAMILIES).require("Familia", "Descripcion");
        SheetTable suppliers = SheetTable.of(sheets, SUPPLIERS).require("Proveedor", "RazonSocial");
        SheetTable analyses = SheetTable.of(sheets, ANALYSES).require("CodigoM", "CodigoI", "Renglon", "/",
                "Cantidad", "Expresion");
        SheetTable labor = SheetTable.of(sheets, LABOR).require("Codigo", "SalarioBaseM0", "FasarM0",
                "SalarioRealM0", "FactorSalarioIntegradoM0", "SalarioIntegradoM0", "ViaticosM0",
                "AlimentacionYHospedajeM0", "GastosDeSepelioM0", "OtrasPrestacionesM0", "TipoFasarM0");
        SheetTable equipment = SheetTable.of(sheets, EQUIPMENT).require("Codigo");
        SheetTable operation = SheetTable.of(sheets, OPERATION).require("CodigoM", "CodigoI", "/", "cantidad");
        SheetTable consumption = SheetTable.of(sheets, CONSUMPTION).require("CodigoM", "CodigoI", "/", "cantidad");
        SheetTable standby = SheetTable.of(sheets, STANDBY).require("Cargo", "Espera", "Reserva");
        SheetTable general = SheetTable.findingHeader(sheets, GENERAL, "NOMBRE DE CELDA")
                .require("NOMBRE DE CELDA", "DESCRIPCION", "VALOR");

        parsed.readGeneral(general);
        parsed.readCatalog(catalog);
        parsed.readUnits(units);
        parsed.readFamilies(families);
        parsed.readSuppliers(suppliers);
        parsed.readAnalyses(analyses);
        parsed.readLabor(labor);
        parsed.readEquipment(equipment);
        parsed.readEquipmentLines(operation, parsed.operationLines);
        parsed.readEquipmentLines(consumption, parsed.consumptionLines);
        parsed.readStandby(standby);
        parsed.rowCounts.put(CATALOG, catalog.rows.size());
        parsed.rowCounts.put(ANALYSES, analyses.rows.size());
        parsed.rowCounts.put(LABOR, labor.rows.size());
        parsed.rowCounts.put(EQUIPMENT, equipment.rows.size());
        parsed.rowCounts.put(OPERATION, operation.rows.size());
        parsed.rowCounts.put(CONSUMPTION, consumption.rows.size());
        return parsed;
    }

    private void readGeneral(SheetTable sheet) {
        Map<String, RawRow> byKey = new LinkedHashMap<>();
        for (RawRow row : sheet.rows) {
            for (String column : new String[] {"NOMBRE DE CELDA", "DESCRIPCION"}) {
                String key = sheet.text(row, column);
                if (key != null) {
                    byKey.putIfAbsent(key.toLowerCase(Locale.ROOT), row);
                }
            }
        }
        RawRow currency = byKey.get("primeramoneda");
        if (currency != null) {
            String value = sheet.text(currency, "VALOR");
            if (value != null && !value.equalsIgnoreCase("PESOS") && !value.equalsIgnoreCase("M.N.")) {
                throw sheet.fail("UNSUPPORTED_CURRENCY", currency, "VALOR",
                        "la moneda '" + value + "' no es compatible; solo PESOS (M.N.)");
            }
        }
        BigDecimal minimumWage = generalNumber(sheet, byKey, "salario mínimo", false);
        if (minimumWage == null) {
            minimumWage = generalNumber(sheet, byKey, "salario minimo", false);
        }
        BigDecimal sum = generalNumber(sheet, byKey, "patron + trabajado", false);
        generalFields = new GeneralFields(minimumWage,
                generalNumber(sheet, byKey, "sar", true),
                generalNumber(sheet, byKey, "invonavit", true),
                generalNumber(sheet, byKey, "riesgo de trabajo", true),
                generalNumber(sheet, byKey, "impuesto sobre nomina", true),
                generalNumber(sheet, byKey, "otros cargos", true),
                sum == null ? null : sum.signum() != 0);
    }

    /** A percentage above 1 is the {@code %/100} bug of §5.3: divided by 100 with a warning. */
    private BigDecimal generalNumber(SheetTable sheet, Map<String, RawRow> byKey, String key, boolean percent) {
        RawRow row = byKey.get(key);
        if (row == null) {
            return null;
        }
        BigDecimal value;
        try {
            value = sheet.number(row, "VALOR", "INVALID_NUMBER");
        } catch (ImportFailedException ex) {
            return null;
        }
        if (value != null && percent && value.compareTo(BigDecimal.ONE) > 0) {
            warnings.add("GENERAL_FIELDS_PERCENT_SCALE", GENERAL, row.number(), null,
                    Map.of("field", sheet.text(row, "NOMBRE DE CELDA") == null ? key
                            : sheet.text(row, "NOMBRE DE CELDA"), "raw", value.toPlainString()));
            value = value.movePointLeft(2);
        }
        return value;
    }

    private void readCatalog(SheetTable sheet) {
        for (RawRow row : sheet.rows) {
            String code = sheet.code(row, "Codigo");
            if (code == null) {
                throw sheet.fail("BLANK_CODE", row, "Codigo", "el código está vacío");
            }
            if (code.length() > 40) {
                throw sheet.fail("BLANK_CODE", row, "Codigo", "el código tiene más de 40 caracteres");
            }
            if (resources.containsKey(code)) {
                throw sheet.fail("DUPLICATE_CODE", row, "Codigo", "el código '" + code + "' está repetido");
            }
            ResourceType type = type(sheet, row);
            String unit = sheet.code(row, "Unidad");
            if (unit == null) {
                throw sheet.fail("BLANK_UNIT", row, "Unidad", "el insumo '" + code + "' no tiene unidad");
            }
            BigDecimal rawCost = sheet.nonNegative(row, "Costo0", "INVALID_NUMBER");
            BigDecimal cost = rawCost.setScale(2, RoundingMode.HALF_UP);
            if (rawCost.stripTrailingZeros().scale() > 2) {
                warnings.add("COST_ROUNDED", CATALOG, row.number(), code,
                        Map.of("raw", rawCost.toPlainString(), "rounded", cost.toPlainString()));
            }
            LocalDate costOn = sheet.date(row, "Fecha0");
            boolean percentage = sheet.flag(row, "%");
            Short base = null;
            if (percentage) {
                BigDecimal raw = sheet.number(row, "Tipo %", "UNSUPPORTED_PERCENTAGE_BASE");
                if (raw == null || raw.compareTo(BigDecimal.valueOf(2)) != 0) {
                    throw sheet.fail("UNSUPPORTED_PERCENTAGE_BASE", row, "Tipo %",
                            "solo se admiten porcentajes sobre el importe de mano de obra (Tipo % = 2)");
                }
                base = 2;
            }
            String name = sheet.text(row, "Descripcion");
            String description = sheet.text(row, "Descripcion completa");
            if (name == null) {
                name = description == null ? code : description;
            }
            if (name.length() > NAME_MAX) {
                warnings.add("NAME_TRUNCATED", CATALOG, row.number(), code, Map.of("length", name.length()));
                name = name.substring(0, NAME_MAX).strip();
            }
            String grouper = sheet.text(row, "Agrupador");
            resources.put(code, new FileResource(row.number(), code, type, name, description, cut(unit, 20),
                    zeroIsNone(sheet.code(row, "Familia")), "0".equals(grouper) ? null : cut(grouper, 40),
                    percentage, base, zeroIsNone(sheet.code(row, "Proveedor")),
                    cut(sheet.text(row, "FichaTecnica"), 255), cut(sheet.text(row, "Referencia"), 120),
                    cut(sheet.text(row, "CodigoSap"), 40), sheet.flag(row, "InsumoDescontinuado"),
                    sheet.text(row, "Procedimiento"), sheet.flag(row, "EsCostoHorario"), cost, costOn));
        }
    }

    private static ResourceType type(SheetTable sheet, RawRow row) {
        String raw = sheet.text(row, "Tipo");
        int value;
        try {
            value = raw == null ? 0 : (int) Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            value = 0;
        }
        return switch (value) {
            case 1 -> ResourceType.MATERIAL;
            case 2 -> ResourceType.LABOR;
            case 3 -> ResourceType.EQUIPMENT;
            case 4 -> ResourceType.WORK_ITEM;
            default -> throw sheet.fail("UNSUPPORTED_TYPE", row, "Tipo",
                    "el tipo '" + raw + "' no es 1, 2, 3 ni 4");
        };
    }

    private void readUnits(SheetTable sheet) {
        Set<String> seen = new HashSet<>();
        for (RawRow row : sheet.rows) {
            String code = sheet.code(row, "Unidad");
            if (code == null || code.length() > 20 || !seen.add(code)) {
                continue;
            }
            String description = sheet.text(row, "Descripcion");
            if (description != null && description.equalsIgnoreCase("Sin descripción")) {
                description = null;
            }
            units.add(new FileUnit(code, cut(description, 120)));
        }
    }

    private void readFamilies(SheetTable sheet) {
        Set<String> seen = new HashSet<>();
        for (RawRow row : sheet.rows) {
            String code = sheet.code(row, "Familia");
            if (code == null || code.length() > 40 || !seen.add(code)) {
                continue;
            }
            String name = sheet.text(row, "Descripcion");
            families.add(new FileFamily(code, cut(name == null ? code : name, 160)));
        }
    }

    private void readSuppliers(SheetTable sheet) {
        Set<String> seen = new HashSet<>();
        for (RawRow row : sheet.rows) {
            String code = sheet.code(row, "Proveedor");
            if (code == null || code.length() > 40 || !seen.add(code)) {
                continue;
            }
            String name = sheet.text(row, "RazonSocial");
            suppliers.add(new FileSupplier(row.number(), code, cut(name == null ? code : name, 160),
                    cut(sheet.code(row, "Rfc"), 13), cut(sheet.text(row, "Direccion"), 255),
                    cut(sheet.text(row, "Colonia"), 120), cut(sheet.text(row, "Ciudad"), 160),
                    cut(sheet.text(row, "Estado"), 80), cut(sheet.text(row, "CodigoPostal"), 10),
                    cut(sheet.text(row, "Telefono"), 40), cut(sheet.text(row, "Celular"), 40),
                    cut(sheet.text(row, "Fax"), 40), cut(sheet.text(row, "Mail"), 160),
                    cut(sheet.text(row, "Contacto"), 160), cut(sheet.text(row, "Observaciones"), 500),
                    cut(sheet.text(row, "PaginaWeb"), 255), sheet.flag(row, "ProveedorDefault")));
        }
    }

    private void readAnalyses(SheetTable sheet) {
        for (RawRow row : sheet.rows) {
            String parent = known(sheet, row, "CodigoM");
            String component = known(sheet, row, "CodigoI");
            BigDecimal lineNo = sheet.number(row, "Renglon", "INVALID_NUMBER");
            char operator = operator(sheet, row, "/");
            BigDecimal quantity = quantity(sheet, row, "Cantidad", component);
            lines.add(new FileLine(row.number(), parent, component, lineNo == null ? 0 : lineNo.intValue(), operator,
                    quantity, cut(sheet.text(row, "Expresion"), 120)));
        }
    }

    private void readLabor(SheetTable sheet) {
        for (RawRow row : sheet.rows) {
            String code = known(sheet, row, "Codigo");
            BigDecimal fsr = sheet.nonNegative(row, "FasarM0", "INVALID_NUMBER");
            if (fsr.compareTo(BigDecimal.ONE) < 0) {
                throw sheet.fail("INVALID_NUMBER", row, "FasarM0", "el factor de salario real debe ser al menos 1");
            }
            BigDecimal type = sheet.number(row, "TipoFasarM0", "INVALID_NUMBER");
            labor.add(new FileLabor(row.number(), code, money(sheet, row, "SalarioBaseM0"),
                    fsr.setScale(8, RoundingMode.HALF_UP), optionalMoney(sheet, row, "SalarioRealM0"),
                    factor(sheet, row, "FactorSalarioIntegradoM0"), optionalMoney(sheet, row, "SalarioIntegradoM0"),
                    money(sheet, row, "ViaticosM0"), money(sheet, row, "AlimentacionYHospedajeM0"),
                    money(sheet, row, "GastosDeSepelioM0"), money(sheet, row, "OtrasPrestacionesM0"),
                    type == null ? null : type.shortValue()));
        }
    }

    private void readEquipment(SheetTable sheet) {
        Set<String> seen = new HashSet<>();
        for (RawRow row : sheet.rows) {
            String code = known(sheet, row, "Codigo");
            if (!seen.add(code)) {
                continue;
            }
            Map<String, String> texts = new LinkedHashMap<>();
            EQUIPMENT_TEXTS.forEach((header, column) -> {
                String value = sheet.text(row, header);
                int max = column.endsWith("_code") ? 40 : column.equals("power_unit") ? 10 : 120;
                texts.put(column, cut(value, max));
            });
            Map<String, BigDecimal> numbers = new LinkedHashMap<>();
            EQUIPMENT_NUMBERS.forEach((header, spec) -> {
                String[] parts = spec.split(":");
                BigDecimal value = sheet.number(row, header, "INVALID_NUMBER");
                numbers.put(parts[0], value == null ? null
                        : value.setScale(Integer.parseInt(parts[1]), RoundingMode.HALF_UP));
            });
            equipment.add(new FileEquipment(row.number(), code, texts, numbers));
        }
    }

    private void readEquipmentLines(SheetTable sheet, List<FileEquipmentLine> into) {
        for (RawRow row : sheet.rows) {
            String parent = known(sheet, row, "CodigoM");
            String component = known(sheet, row, "CodigoI");
            char operator = operator(sheet, row, "/");
            BigDecimal quantity = quantity(sheet, row, "cantidad", component);
            into.add(new FileEquipmentLine(row.number(), parent, component, operator, quantity));
        }
    }

    /** Shares above 1 are percentages and become fractions. */
    private void readStandby(SheetTable sheet) {
        Set<String> seen = new HashSet<>();
        for (RawRow row : sheet.rows) {
            String charge = sheet.text(row, "Cargo");
            if (charge == null || !seen.add(charge.toLowerCase(Locale.ROOT))) {
                continue;
            }
            standby.add(new FileStandby(cut(charge, 40), share(sheet, row, "Espera"), share(sheet, row, "Reserva")));
        }
    }

    private static BigDecimal share(SheetTable sheet, RawRow row, String column) {
        BigDecimal value = sheet.number(row, column, "INVALID_NUMBER");
        if (value == null) {
            return BigDecimal.ZERO.setScale(6);
        }
        if (value.signum() < 0) {
            throw sheet.fail("INVALID_NUMBER", row, column, "el número no puede ser negativo");
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = value.movePointLeft(2);
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    /** A code that must name a {@code CatalogoGeneral} row ({@code UNKNOWN_CODE}). */
    private String known(SheetTable sheet, RawRow row, String column) {
        String code = sheet.code(row, column);
        if (code == null) {
            throw sheet.fail("BLANK_CODE", row, column, "el código está vacío");
        }
        if (!resources.containsKey(code)) {
            throw sheet.fail("UNKNOWN_CODE", row, column, "el código '" + code + "' no existe en CatalogoGeneral");
        }
        return code;
    }

    private static char operator(SheetTable sheet, RawRow row, String column) {
        String value = sheet.text(row, column);
        if ("*".equals(value) || "/".equals(value)) {
            return value.charAt(0);
        }
        throw sheet.fail("INVALID_OPERATOR", row, column, "el operador '" + value + "' no es * ni /");
    }

    /** Greater than zero, rounded to six decimals with {@code QUANTITY_ROUNDED}. */
    private BigDecimal quantity(SheetTable sheet, RawRow row, String column, String component) {
        BigDecimal raw = sheet.nonNegative(row, column, "INVALID_NUMBER");
        if (raw.signum() == 0) {
            throw sheet.fail("INVALID_NUMBER", row, column, "la cantidad no puede ser cero");
        }
        BigDecimal rounded = raw.setScale(6, RoundingMode.HALF_UP);
        if (raw.stripTrailingZeros().scale() > 6) {
            warnings.add("QUANTITY_ROUNDED", sheet.name, row.number(), component,
                    Map.of("raw", raw.toPlainString(), "rounded", rounded.toPlainString()));
        }
        if (rounded.signum() == 0) {
            throw sheet.fail("INVALID_NUMBER", row, column, "la cantidad no puede ser cero");
        }
        return rounded;
    }

    private static BigDecimal money(SheetTable sheet, RawRow row, String column) {
        BigDecimal value = optionalMoney(sheet, row, column);
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    private static BigDecimal optionalMoney(SheetTable sheet, RawRow row, String column) {
        BigDecimal value = sheet.number(row, column, "INVALID_NUMBER");
        if (value != null && value.signum() < 0) {
            throw sheet.fail("INVALID_NUMBER", row, column, "el número no puede ser negativo");
        }
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal factor(SheetTable sheet, RawRow row, String column) {
        BigDecimal value = sheet.number(row, column, "INVALID_NUMBER");
        return value == null ? null : value.setScale(8, RoundingMode.HALF_UP);
    }

    private static String zeroIsNone(String code) {
        return code == null || code.equals("0") ? null : code;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max).strip();
    }

    private static Map<String, String> linked(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
