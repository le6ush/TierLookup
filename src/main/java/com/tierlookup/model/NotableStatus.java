package com.tierlookup.model;

/** UUID-bound reputation/enrichment metadata derived from authoritative loaded sources. */
public record NotableStatus(Type type, int rank, String source, long updatedAt) {
    public enum Type {
        WORLD_LEGEND, CIS_LEGEND, CREATOR
    }
}
