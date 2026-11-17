package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.importing.PresupuestoFile.GeneralFields;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the PARTIAL FASAR set from {@code N_Campos Generales} when an import has no FASAR workbook and the
 * organization has no set yet (docs/pricing.md §5.3), inside the import's transaction.
 */
@Component
class ImportedFasarFields {

    /** Without the FASAR parameter tables (their slice) there is nothing to write yet. */
    @Transactional(propagation = Propagation.MANDATORY)
    void createPartialIfAbsent(UUID organizationId, GeneralFields fields, Instant at) {
        // written once the FASAR parameter set exists in the schema
    }
}
