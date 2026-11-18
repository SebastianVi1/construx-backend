package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.importing.PresupuestoFile.GeneralFields;
import org.andre.construx.modules.pricing.model.FasarConcept;
import org.andre.construx.modules.pricing.model.FasarParameterSet;
import org.andre.construx.modules.pricing.repository.FasarParameterSetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the PARTIAL FASAR set from {@code N_Campos Generales} when an import has no FASAR workbook and the
 * organization has no set yet (docs/pricing.md §5.3), inside the import's transaction. An existing set, partial or
 * complete, is left alone, and Labor categories keep their imported FSR.
 */
@Component
class ImportedFasarFields {
    private final FasarParameterSetRepository sets;

    ImportedFasarFields(FasarParameterSetRepository sets) {
        this.sets = sets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void createPartialIfAbsent(UUID organizationId, GeneralFields fields, Instant at) {
        if (fields == null || sets.findByOrganizationId(organizationId).isPresent()) {
            return;
        }
        Map<FasarConcept, BigDecimal> rates = new EnumMap<>(FasarConcept.class);
        rates.put(FasarConcept.RETIREMENT, fields.retirement());
        rates.put(FasarConcept.INFONAVIT, fields.infonavit());
        rates.put(FasarConcept.RISK, fields.risk());
        rates.put(FasarConcept.PAYROLL_TAX, fields.payrollTax());
        sets.save(FasarParameterSet.fromGeneralFields(organizationId, fields.minimumWage(), rates,
                fields.otherChargesPct(), fields.sumEmployerAndWorker(), at));
    }
}
