package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.PricingValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;

/** Checks the {@code page}, {@code size} and {@code sort} query parameters of a paged list (docs/pricing.md §0). */
final class PageQuery {
    static final int MAX_SIZE = 200;

    private PageQuery() {
    }

    /**
     * {@code sort} is {@code <field>} or {@code <field>,<asc|desc>} with a field of {@code fields} (API name to
     * entity path), or null for {@code fallback}. {@code tieBreak} keeps pages stable when the sort field repeats.
     */
    static PageRequest of(int page, int size, String sort, Map<String, String> fields, Sort fallback, Sort tieBreak) {
        if (page < 0) {
            throw new PricingValidationException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new PricingValidationException("size must be between 1 and " + MAX_SIZE);
        }
        Sort order = sort == null ? fallback : parse(sort, fields);
        return PageRequest.of(page, size, order.and(tieBreak));
    }

    private static Sort parse(String sort, Map<String, String> fields) {
        String[] parts = sort.split(",", -1);
        String path = fields.get(parts[0].strip());
        if (parts.length > 2 || path == null) {
            throw new PricingValidationException("sort must be one of " + fields.keySet() + " with asc or desc");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String value = parts[1].strip().toLowerCase(Locale.ROOT);
            if (!value.equals("asc") && !value.equals("desc")) {
                throw new PricingValidationException("sort direction must be asc or desc");
            }
            direction = Sort.Direction.fromString(value);
        }
        Sort.Order order = new Sort.Order(direction, path).nullsLast();
        return Sort.by(path.equals("name") ? order.ignoreCase() : order);
    }
}
