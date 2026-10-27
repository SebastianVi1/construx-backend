package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.pricing.model.Material;
import org.andre.construx.modules.pricing.model.MaterialCategory;
import org.andre.construx.modules.pricing.model.MaterialUnit;
import org.andre.construx.modules.pricing.model.PriceObservation;
import org.andre.construx.modules.pricing.model.PriceSource;
import org.andre.construx.modules.pricing.model.PricingRuleException;
import org.andre.construx.modules.pricing.model.PricingText;
import org.andre.construx.modules.pricing.model.PricingValidationException;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.MaterialRepository;
import org.andre.construx.modules.pricing.repository.PriceObservationRepository;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.modules.pricing.service.CsvParser.CsvFormatException;
import org.andre.construx.modules.pricing.service.CsvParser.CsvRecord;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Imports price observations from a CSV file, creating missing materials and suppliers by the same
 * normalized keys as manual creation. All or nothing: rows are checked in memory first and nothing is
 * written unless every row is valid.
 */
@Service
public class PriceImportService {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_ROWS = 2000;
    public static final List<String> HEADER = List.of(
            "material", "unit", "category", "brand", "supplier", "branch", "price", "observed_on", "sku");

    private static final Pattern PRICE = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Map<String, MaterialUnit> UNITS = labels(MaterialUnit.values(), Map.of(
            "pieza", MaterialUnit.PIECE, "saco", MaterialUnit.BAG, "m2", MaterialUnit.SQUARE_METER,
            "m3", MaterialUnit.CUBIC_METER, "kg", MaterialUnit.KILOGRAM, "litro", MaterialUnit.LITER,
            "metro", MaterialUnit.METER));
    private static final Map<String, MaterialCategory> CATEGORIES = labels(MaterialCategory.values(), Map.of(
            "cemento y agregados", MaterialCategory.CEMENT_AGGREGATES, "acero", MaterialCategory.STEEL,
            "madera", MaterialCategory.WOOD, "electrico", MaterialCategory.ELECTRICAL,
            "plomeria", MaterialCategory.PLUMBING, "acabados", MaterialCategory.FINISHES));

    private final MaterialRepository materials;
    private final SupplierRepository suppliers;
    private final PriceObservationRepository observations;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public PriceImportService(MaterialRepository materials, SupplierRepository suppliers,
                              PriceObservationRepository observations, MembershipGuard guard,
                              DomainAuthorization authorization) {
        this.materials = materials;
        this.suppliers = suppliers;
        this.observations = observations;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** {@code field} is the CSV column name, or {@code row} for the whole line. */
    public record RowError(String field, String code) {}

    /** {@code price} has two decimals when valid and echoes the input otherwise. */
    public record ImportRow(int line, String material, String supplier, String branch, String price,
                            String observedOn, boolean newMaterial, boolean newSupplier, List<RowError> errors) {}

    public record Summary(int rows, int newMaterials, int newSuppliers, int observations) {}

    public record ImportReport(boolean valid, List<ImportRow> rows, Summary summary, boolean imported) {}

    private record Resolved<T>(T entity, boolean isNew) {}

    @Transactional
    public ImportReport importCsv(UUID userId, UUID organizationId, byte[] content, boolean dryRun) {
        Organization organization = guard.requireActive(userId, organizationId).getOrganization();
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        List<CsvRecord> records = read(content);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = Instant.now();
        Map<String, Resolved<Material>> materialCache = new HashMap<>();
        Map<String, Resolved<Supplier>> supplierCache = new HashMap<>();
        Set<Material> newMaterials = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Supplier> newSuppliers = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PriceObservation> pending = new ArrayList<>();
        List<ImportRow> rows = new ArrayList<>();
        for (CsvRecord record : records.subList(1, records.size())) {
            List<String> f = record.fields();
            if (f.size() != HEADER.size()) {
                rows.add(new ImportRow(record.line(), PricingText.clean(at(f, 0)), PricingText.clean(at(f, 4)),
                        PricingText.clean(at(f, 5)), PricingText.clean(at(f, 6)), PricingText.clean(at(f, 7)),
                        false, false, List.of(new RowError("row", "COLUMNS"))));
                continue;
            }
            List<RowError> errors = new ArrayList<>();
            String materialName = required(f.get(0), "material", errors);
            MaterialUnit unit = label(f.get(1), UNITS, "unit", errors);
            MaterialCategory category = label(f.get(2), CATEGORIES, "category", errors);
            String supplierName = required(f.get(4), "supplier", errors);
            Resolved<Material> material = null;
            if (materialName != null && unit != null && category != null) {
                material = resolveMaterial(organization, materialName, unit, category, f.get(3), materialCache,
                        errors);
            }
            Resolved<Supplier> supplier = null;
            if (supplierName != null) {
                supplier = resolveSupplier(organization, supplierName, f.get(5), supplierCache, errors);
            }
            BigDecimal price = price(f.get(6), errors);
            LocalDate observedOn = observedOn(f.get(7), today, errors);
            String sku = rule(() -> PriceObservation.checkSku(f.get(8)), errors);
            errors.sort(Comparator.comparingInt(e -> HEADER.indexOf(e.field())));
            if (errors.isEmpty()) {
                pending.add(new PriceObservation(material.entity(), supplier.entity(), price, observedOn, sku,
                        PriceSource.CSV, today, now));
                if (material.isNew()) {
                    newMaterials.add(material.entity());
                }
                if (supplier.isNew()) {
                    newSuppliers.add(supplier.entity());
                }
            }
            rows.add(new ImportRow(record.line(),
                    material != null ? material.entity().getName() : PricingText.clean(f.get(0)),
                    supplier != null ? supplier.entity().getName() : PricingText.clean(f.get(4)),
                    supplier != null ? supplier.entity().getBranch() : PricingText.clean(f.get(5)),
                    price != null ? price.toPlainString() : PricingText.clean(f.get(6)),
                    observedOn != null ? observedOn.toString() : PricingText.clean(f.get(7)),
                    material != null && material.isNew(), supplier != null && supplier.isNew(),
                    List.copyOf(errors)));
        }
        boolean valid = rows.stream().allMatch(r -> r.errors().isEmpty());
        Summary summary = new Summary(rows.size(), newMaterials.size(), newSuppliers.size(), pending.size());
        boolean imported = false;
        if (valid && !dryRun) {
            materials.saveAll(new LinkedHashSet<>(newMaterials));
            suppliers.saveAll(new LinkedHashSet<>(newSuppliers));
            observations.saveAll(pending);
            observations.flush();
            imported = true;
        }
        return new ImportReport(valid, rows, summary, imported);
    }

    private static List<CsvRecord> read(byte[] content) {
        if (content.length > MAX_BYTES) {
            throw new PricingValidationException("The file is larger than 1 MB.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new PricingValidationException("The file must be UTF-8 text.");
        }
        List<CsvRecord> records;
        try {
            records = CsvParser.parse(text);
        } catch (CsvFormatException e) {
            throw new PricingValidationException("The file is not valid CSV: " + e.getMessage() + ".");
        }
        if (records.isEmpty() || !isHeader(records.get(0).fields())) {
            throw new PricingValidationException("The first line must be the header: " + String.join(",", HEADER));
        }
        if (records.size() == 1) {
            throw new PricingValidationException("The file has no rows after the header.");
        }
        if (records.size() - 1 > MAX_ROWS) {
            throw new PricingValidationException("The file has more than " + MAX_ROWS + " rows.");
        }
        return records;
    }

    private static boolean isHeader(List<String> fields) {
        return fields.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList().equals(HEADER);
    }

    private Resolved<Material> resolveMaterial(Organization organization, String name, MaterialUnit unit,
                                               MaterialCategory category, String brand,
                                               Map<String, Resolved<Material>> cache, List<RowError> errors) {
        Material candidate;
        try {
            candidate = new Material(organization, name, unit, category, brand);
        } catch (PricingRuleException e) {
            errors.add(new RowError("name".equals(e.getField()) ? "material" : e.getField(), e.getCode()));
            return null;
        }
        Resolved<Material> resolved = cache.computeIfAbsent(candidate.getNormalizedKey(), key ->
                materials.findByOrganizationIdAndNormalizedKey(organization.getId(), key)
                        .map(existing -> new Resolved<>(existing, false))
                        .orElseGet(() -> new Resolved<>(candidate, true)));
        if (resolved.entity().isArchived()) {
            errors.add(new RowError("material", "ARCHIVED_MATERIAL"));
        }
        return resolved;
    }

    private Resolved<Supplier> resolveSupplier(Organization organization, String name, String branch,
                                               Map<String, Resolved<Supplier>> cache, List<RowError> errors) {
        Supplier candidate;
        try {
            candidate = new Supplier(organization, name, branch, null);
        } catch (PricingRuleException e) {
            errors.add(new RowError("name".equals(e.getField()) ? "supplier" : e.getField(), e.getCode()));
            return null;
        }
        Resolved<Supplier> resolved = cache.computeIfAbsent(candidate.getNormalizedKey(), key ->
                suppliers.findByOrganizationIdAndNormalizedKey(organization.getId(), key)
                        .map(existing -> new Resolved<>(existing, false))
                        .orElseGet(() -> new Resolved<>(candidate, true)));
        if (resolved.entity().isArchived()) {
            errors.add(new RowError("supplier", "ARCHIVED_SUPPLIER"));
        }
        return resolved;
    }

    private static BigDecimal price(String raw, List<RowError> errors) {
        String value = raw.strip();
        if (value.isEmpty()) {
            errors.add(new RowError("price", "REQUIRED"));
            return null;
        }
        if (!PRICE.matcher(value).matches()) {
            errors.add(new RowError("price", "INVALID"));
            return null;
        }
        return rule(() -> PriceObservation.checkPrice(new BigDecimal(value)), errors);
    }

    private static LocalDate observedOn(String raw, LocalDate today, List<RowError> errors) {
        String value = raw.strip();
        if (value.isEmpty()) {
            errors.add(new RowError("observed_on", "REQUIRED"));
            return null;
        }
        LocalDate date;
        try {
            date = DATE.matcher(value).matches() ? LocalDate.parse(value) : null;
        } catch (DateTimeParseException e) {
            date = null;
        }
        if (date == null) {
            errors.add(new RowError("observed_on", "INVALID"));
            return null;
        }
        LocalDate parsed = date;
        return rule(() -> PriceObservation.checkObservedOn(parsed, today), errors);
    }

    /** Runs one model rule and records its failure under the CSV column name. */
    private static <T> T rule(java.util.function.Supplier<T> check, List<RowError> errors) {
        try {
            return check.get();
        } catch (PricingRuleException e) {
            String field = "observedOn".equals(e.getField()) ? "observed_on" : e.getField();
            errors.add(new RowError(field, e.getCode()));
            return null;
        }
    }

    private static String required(String raw, String field, List<RowError> errors) {
        String value = PricingText.clean(raw);
        if (value == null) {
            errors.add(new RowError(field, "REQUIRED"));
        }
        return value;
    }

    private static <E> E label(String raw, Map<String, E> known, String field, List<RowError> errors) {
        String value = PricingText.clean(raw);
        if (value == null) {
            errors.add(new RowError(field, "REQUIRED"));
            return null;
        }
        E match = known.get(foldLabel(value));
        if (match == null) {
            errors.add(new RowError(field, "INVALID"));
        }
        return match;
    }

    /** Lower case without accents; compatibility decomposition also turns m² and m³ into m2 and m3. */
    private static String foldLabel(String value) {
        String decomposed = Normalizer.normalize(PricingText.fold(value), Normalizer.Form.NFKD);
        return MARKS.matcher(decomposed).replaceAll("");
    }

    private static <E extends Enum<E>> Map<String, E> labels(E[] codes, Map<String, E> spanish) {
        Map<String, E> map = new HashMap<>(spanish);
        for (E code : codes) {
            map.put(code.name().toLowerCase(Locale.ROOT), code);
        }
        return Map.copyOf(map);
    }

    private static String at(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index) : null;
    }
}
