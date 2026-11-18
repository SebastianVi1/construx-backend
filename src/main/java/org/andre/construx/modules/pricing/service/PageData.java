package org.andre.construx.modules.pricing.service;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** The paged list shape of docs/pricing.md §0. */
public record PageData<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    static <E, T> PageData<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageData<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
