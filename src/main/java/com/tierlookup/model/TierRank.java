package com.tierlookup.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes tier syntax without assigning additional ranking semantics. */
public final class TierRank {
    private static final Pattern TIER = Pattern.compile("(?i)\\bR?([HML])T\\s*([1-5])\\b");
    private TierRank() {
    }
    public static String normalize(String value) {
        if (value == null) return null;
        Matcher m = TIER.matcher(value.trim());
        if (!m.find()) return null;
        return m.group(1).toUpperCase(Locale.ROOT) + "T" + m.group(2);
    }
}
