package org.andre.construx.modules.pricing.importing;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small synthetic PU Win+ Presupuesto workbook built with POI (docs/pricing.md §5.2a: no Neodata data is ever
 * committed). The standard content is a dozen resources whose expected costs are worked out in the comments:
 * a mortar and a work item with analyses, a gang of two labor categories, a {@code %} of labor and an equipment
 * rate. Tests change cells, rows, columns or whole sheets to reach each failure and warning.
 */
public final class SyntheticPresupuesto {

    /** One sheet: its header and its rows as values by header ({@link Number} or {@link String}). */
    public static final class SheetData {
        final List<String> headers;
        final List<Map<String, Object>> rows = new ArrayList<>();
        int headerRow = 0;

        SheetData(String... headers) {
            this.headers = new ArrayList<>(Arrays.asList(headers));
        }

        public SheetData row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                row.put(headers.get(i), values[i]);
            }
            rows.add(row);
            return this;
        }

        /** Sets one cell of the data row whose first column holds {@code key}. */
        public SheetData set(String key, String column, Object value) {
            rows.stream().filter(r -> key.equals(r.get(headers.get(0)))).findFirst().orElseThrow()
                    .put(column, value);
            return this;
        }

        public SheetData dropColumn(String column) {
            headers.remove(column);
            return this;
        }

        public SheetData removeRows(String key) {
            rows.removeIf(r -> key.equals(r.get(headers.get(0))));
            return this;
        }
    }

    public static final String[] CATALOG_HEADERS = {"Codigo", "Descripcion completa", "Descripcion", "Unidad", "Tipo",
            "Familia", "Agrupador", "%", "Tipo %", "Proveedor", "FichaTecnica", "Referencia", "CodigoSap",
            "InsumoDescontinuado", "Procedimiento", "InsumoIntegrado", "EsCostoHorario", "Costo0", "Fecha0"};

    final Map<String, SheetData> sheets = new LinkedHashMap<>();

    private SyntheticPresupuesto() {
    }

    public static SyntheticPresupuesto standard() {
        SyntheticPresupuesto wb = new SyntheticPresupuesto();
        wb.sheets.put("Partidas", new SheetData("PartidaWbs", "Basura").row("1", "not read"));
        SheetData catalog = new SheetData(CATALOG_HEADERS);
        catalog.row("301-ARE-0101", "Arena de mina puesta en obra", "Arena de mina", "M3", 1, "301", 0, 0, 0, "P1",
                null, "VARIOS", null, 0, null, 0, 0, 290, "2026/01/15");
        // SACO is not in Unidades: UNIT_NOT_LISTED
        catalog.row("301-CEM-0001", "Cemento gris", "Cemento gris 50 kg", "SACO", 1, "301", 0, 0, 0, "P1", null,
                null, null, 0, null, 0, 0, 245.5, "15/01/2026");
        catalog.row("MO011", "Peon", "PEON", "JOR", 2, "MO", 0, 0, 0, null, null, null, null, 0, null, 0, 0,
                592.70, 46036);
        catalog.row("MO020", "Albañil", "ALBAÑIL", "JOR", 2, "MO", 0, 0, 0, null, null, null, null, 0, null, 0, 0,
                900, "1900/01/01");
        // Gang: 592.70 + 900.00 = 1492.70
        catalog.row("1A", "Cuadrilla peon + albañil", "CUADRILLA 1A", "JOR", 2, "MO", 0, 0, 0, null, null, null,
                null, 0, null, 1, 0, 1492.70, null);
        catalog.row("%MO1", "Herramienta menor", "HERRAMIENTA MENOR", "%", 1, null, 0, 1, 2, null, null, null, null,
                0, null, 0, 0, 0, null);
        catalog.row("EQ001", "Revolvedora 1 saco", "REVOLVEDORA", "HOR", 3, null, 0, 0, 0, null, null, null, null,
                0, null, 0, 1, 85, null);
        // Mortar: 1.1 x 290.00 = 319.00; 7 x 245.50 = 1718.50; total 2037.50
        catalog.row("MORT-1:4", "Mortero cemento arena 1:4", "MORTERO 1:4", "M3", 1, "301", 0, 0, 0, null, null,
                null, null, 0, null, 1, 0, 2037.5, null);
        // Work item: 0.03 x 2037.50 = 61.13 (61.125); 1492.70 / 20 = 74.64 (74.635); labor subtotal 74.64;
        // 0.03 x 74.64 = 2.24 (2.2392); total 138.01
        catalog.row("10301-001", "Aplanado de mortero", "APLANADO", "M2", 4, null, 0, 0, 0, null, null, null, null,
                0, "Procedimiento", 1, 0, 138.01, null);
        wb.sheets.put("CatalogoGeneral", catalog);
        wb.sheets.put("Unidades", new SheetData("Unidad", "Descripcion")
                .row("M3", "Metro cúbico").row("M2", "Metro cuadrado").row("JOR", "Jornada")
                .row("HOR", "Hora").row("%", "Sin descripción"));
        wb.sheets.put("Familias", new SheetData("Familia", "Descripcion").row("301", "AGREGADOS")
                .row("MO", "MANO DE OBRA"));
        wb.sheets.put("Proveedores", new SheetData("Proveedor", "RazonSocial", "Rfc", "Ciudad", "Mail",
                "ProveedorDefault").row("P1", "VARIOS", "XAXX010101000", "Monterrey", "ventas@varios.mx", 1));
        wb.sheets.put("Matrices", new SheetData("CodigoM", "CodigoI", "Renglon", "/", "Cantidad", "Expresion")
                .row("1A", "MO011", 10, "*", 1, null)
                .row("1A", "MO020", 20, "*", 1, null)
                .row("MORT-1:4", "301-ARE-0101", 10, "*", 1.1, null)
                .row("MORT-1:4", "301-CEM-0001", 20, "*", 7, "350/50")
                .row("10301-001", "MORT-1:4", 10, "*", 0.03, null)
                .row("10301-001", "1A", 20, "/", 20, null)
                .row("10301-001", "%MO1", 30, "*", 0.03, null));
        wb.sheets.put("DetalleManoObra", new SheetData("Codigo", "SalarioBaseM0", "FasarM0", "SalarioRealM0",
                "FactorSalarioIntegradoM0", "SalarioIntegradoM0", "ViaticosM0", "AlimentacionYHospedajeM0",
                "GastosDeSepelioM0", "OtrasPrestacionesM0", "TipoFasarM0")
                .row("MO011", 350, 1.6934, 592.69, 1.0452, 365.82, 0, 0, 0, 0, 1)
                .row("MO020", 500, 1.8, 900, 1.0452, 522.6, 0, 0, 0, 0, 1));
        wb.sheets.put("DetalleMaquinaria", new SheetData("Codigo", "UnidadPotencia", "CargoFijo", "potencia",
                "foper", "Marca", "VidaEconomicaHrs").row("EQ001", "HP", "CF001", 8, 0.8, "Joper", 8000));
        wb.sheets.put("OperacionCH", new SheetData("CodigoM", "CodigoI", "/", "cantidad").row("EQ001", "MO011", "/", 8));
        wb.sheets.put("ConsumosCH", new SheetData("CodigoM", "CodigoI", "/", "cantidad")
                .row("EQ001", "301-ARE-0101", "*", 0.01));
        wb.sheets.put("PorcentajesCh", new SheetData("Cargo", "Espera", "Reserva").row("Depreciacion", 0.5, 0.2));
        SheetData general = new SheetData("NOMBRE DE CELDA", "DESCRIPCION", "VALOR");
        general.headerRow = 3;
        general.row("primeramoneda", "Nombre de la primera moneda", "PESOS")
                .row("Salario Mínimo", "Salario mínimo", 248.93)
                .row("Sar", "Sar", 2)
                .row("Invonavit", "Infonavit", 5)
                .row("Riesgo de trabajo", "Prima de riesgo", 0.0758875)
                .row("Impuesto sobre nomina", "ISN", 3)
                .row("Otros cargos", "Otros", 0)
                .row("Patron + Trabajado", "Suma cuotas", 1);
        wb.sheets.put("N_Campos Generales", general);
        wb.sheets.put("Presupuesto", new SheetData("Codigo").row("NOT-A-CODE"));
        return wb;
    }

    public SheetData sheet(String name) {
        return sheets.get(name);
    }

    public SyntheticPresupuesto without(String sheet) {
        sheets.remove(sheet);
        return this;
    }

    public byte[] bytes() {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, SheetData> entry : sheets.entrySet()) {
                SheetData data = entry.getValue();
                Sheet sheet = book.createSheet(entry.getKey());
                if (data.headerRow > 0) {
                    sheet.createRow(0).createCell(0).setCellValue("DATOS GENERALES");
                }
                Row header = sheet.createRow(data.headerRow);
                for (int i = 0; i < data.headers.size(); i++) {
                    header.createCell(i).setCellValue(data.headers.get(i));
                }
                int r = data.headerRow + 1;
                for (Map<String, Object> values : data.rows) {
                    Row row = sheet.createRow(r++);
                    for (int i = 0; i < data.headers.size(); i++) {
                        Object value = values.get(data.headers.get(i));
                        if (value instanceof Number n) {
                            row.createCell(i).setCellValue(n.doubleValue());
                        } else if (value != null) {
                            row.createCell(i).setCellValue(value.toString());
                        }
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Path file(Path dir) {
        try {
            Path path = Files.createTempFile(dir, "presupuesto", ".xlsx");
            Files.write(path, bytes());
            return path;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
