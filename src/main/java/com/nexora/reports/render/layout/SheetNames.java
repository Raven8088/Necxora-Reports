package com.nexora.reports.render.layout;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Valid, unique sheet names (EX-01): no {@code []:*?/\}, at most 31 characters, not starting or ending with an
 * apostrophe, not the name Excel reserves ({@code History}) and unique ignoring case; «Reporte» and «Metodología»
 * are taken first. A name that becomes empty falls back to the dataset id; a repeated name gets a counter.
 */
public final class SheetNames {

    public static final int MAX = 31;
    private final Set<String> taken = new HashSet<>();

    public SheetNames(String... reserved) {
        for (String name : reserved) {
            taken.add(key(name));
        }
        taken.add(key("History"));
    }

    public String unique(String wanted, String fallback) {
        String base = clean(wanted);
        if (base.isEmpty()) {
            base = clean(fallback);
        }
        if (base.isEmpty()) {
            base = "Hoja";
        }
        String name = base;
        for (int n = 2; taken.contains(key(name)); n++) {
            String suffix = " (" + n + ")";
            name = cut(base, MAX - suffix.length()) + suffix;
        }
        taken.add(key(name));
        return name;
    }

    static String clean(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        name.codePoints().forEach(cp -> b.appendCodePoint("[]:*?/\\".indexOf(cp) >= 0 || Character.isISOControl(cp)
                ? ' ' : cp));
        String s = b.toString().strip().replaceAll(" {2,}", " ");
        while (s.startsWith("'")) {
            s = s.substring(1).strip();
        }
        s = cut(s, MAX);
        while (s.endsWith("'")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end).strip();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
