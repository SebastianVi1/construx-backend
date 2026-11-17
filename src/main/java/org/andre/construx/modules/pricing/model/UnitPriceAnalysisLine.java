package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a composite Resource's Unit price analysis (docs/pricing.md §1.7). Written by the import; v1
 * only materializes its {@code amount} after each derivation.
 */
@Entity
@Table(name = "unit_price_analysis_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitPriceAnalysisLine {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "parent_resource_id", nullable = false)
    private UUID parentResourceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_resource_id", nullable = false)
    private Resource component;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 1)
    private Character operator;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(length = 120)
    private String expression;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    /** The line amount of the last derivation; left alone when equal so nothing is written. */
    public void materialize(BigDecimal amount) {
        if (this.amount == null || this.amount.compareTo(amount) != 0) {
            this.amount = amount;
        }
    }

    public DerivedCost.Line toLine() {
        return new DerivedCost.Line(id, parentResourceId, component.getId(), lineNo, operator, quantity);
    }
}
