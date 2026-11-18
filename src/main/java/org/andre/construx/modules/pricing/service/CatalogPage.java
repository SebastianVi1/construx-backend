package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.PricingValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** One page of a paged pricing list (docs/pricing.md §0). */
public record CatalogPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    static <E, T> CatalogPage<T> of(Page<E> page, Function<E, T> mapper) {
        return new CatalogPage<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
     * {@code page} from 0, {@code size} 1..200 (default 50), {@code sort} as {@code field} or
     * {@code field,asc|desc} with a field from {@code fields}; anything else, an empty {@code sort=} included,
     * answers 400 VALIDATION_FAILED. Nulls sort last in both directions and {@code name} ignores case.
     * Ties are broken by the {@code tieBreakers} (ascending) so pages never overlap.
     */
    static Pageable request(Integer page, Integer size, String sort, Set<String> fields, Sort defaultSort,
                            String... tieBreakers) {
        int number = page == null ? 0 : page;
        int length = size == null ? DEFAULT_SIZE : size;
        if (number < 0) {
            throw new PricingValidationException("page must be 0 or more");
        }
        if (length < 1 || length > MAX_SIZE) {
            throw new PricingValidationException("size must be between 1 and " + MAX_SIZE);
        }
        Sort order = sort == null ? defaultSort : parse(sort, fields);
        for (String tieBreaker : tieBreakers) {
            if (order.getOrderFor(tieBreaker) == null) {
                order = order.and(Sort.by(tieBreaker));
            }
        }
        return PageRequest.of(number, length, order);
    }

    private static Sort parse(String sort, Set<String> fields) {
        String[] parts = sort.split(",", -1);
        String field = parts[0].strip();
        if (parts.length > 2 || !fields.contains(field)) {
            throw new PricingValidationException("sort must be one of " + fields.stream().sorted().toList()
                    + ", optionally followed by ,asc or ,desc");
        }
        Sort.Direction direction = parts.length == 1 ? Sort.Direction.ASC
                : switch (parts[1].strip().toLowerCase(Locale.ROOT)) {
                    case "asc" -> Sort.Direction.ASC;
                    case "desc" -> Sort.Direction.DESC;
                    default -> throw new PricingValidationException("sort direction must be asc or desc");
                };
        Sort.Order order = new Sort.Order(direction, field).nullsLast();
        return Sort.by(field.equals("name") ? order.ignoreCase() : order);
    }
}
