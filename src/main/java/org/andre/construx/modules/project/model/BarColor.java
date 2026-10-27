package org.andre.construx.modules.project.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** The optional #rrggbb color of a phase or task bar. */
final class BarColor {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private BarColor() {
    }

    static String normalize(String color) {
        if (color == null) {
            return null;
        }
        if (!HEX.matcher(color).matches()) {
            throw new IllegalArgumentException("Color must be #rrggbb");
        }
        return color.toLowerCase(Locale.ROOT);
    }
}
