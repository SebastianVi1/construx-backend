package org.andre.construx.modules.pricing.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.pricing.service.PriceObservationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Observations are immutable: there is deliberately no PUT or PATCH. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/price-observations")
public class PriceObservationController {
    private final PriceObservationService observations;

    public PriceObservationController(PriceObservationService observations) {
        this.observations = observations;
    }

    /** {@code price} accepts a JSON number or a decimal string. */
    public record ObservationCreate(@NotNull UUID materialId, @NotNull UUID supplierId, @NotNull BigDecimal price,
                                    @NotNull LocalDate observedOn, @Size(max = 64) String sku) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PriceObservationService.PriceData record(@PathVariable UUID organizationId,
                                                    @Valid @RequestBody ObservationCreate body,
                                                    Authentication authentication) {
        return observations.record(userId(authentication), organizationId, body.materialId(), body.supplierId(),
                body.price(), body.observedOn(), body.sku());
    }

    @DeleteMapping("/{observationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID observationId,
                       Authentication authentication) {
        observations.delete(userId(authentication), organizationId, observationId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
