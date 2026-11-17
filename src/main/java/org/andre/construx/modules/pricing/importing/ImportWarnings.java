package org.andre.construx.modules.pricing.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Warnings of a dry run (docs/pricing.md §4.5): counted per code, with at most {@value #SAMPLE_MAX} samples. */
public final class ImportWarnings {
    public static final int SAMPLE_MAX = 200;

    private final Map<String, Integer> byCode = new TreeMap<>();
    private final List<Map<String, Object>> sample = new ArrayList<>();
    private int total;

    void add(String code, String sheet, Integer row, String resourceCode, Map<String, Object> detail) {
        total++;
        byCode.merge(code, 1, Integer::sum);
        if (sample.size() < SAMPLE_MAX) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", code);
            entry.put("sheet", sheet);
            entry.put("row", row);
            entry.put("resourceCode", resourceCode);
            entry.put("detail", detail);
            sample.add(entry);
        }
    }

    public int count(String code) {
        return byCode.getOrDefault(code, 0);
    }

    public int total() {
        return total;
    }

    Map<String, Object> toSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byCode", byCode);
        out.put("sample", sample);
        return out;
    }
}
