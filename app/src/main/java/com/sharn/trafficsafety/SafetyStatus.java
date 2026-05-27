package com.sharn.trafficsafety;

public final class SafetyStatus {
    public final SafetyLevel level;
    public final String distance;
    public final int count;
    public final String message;
    public final String closestLabel;
    public final int color;
    public final float ttc;

    public SafetyStatus(SafetyLevel level, String distance, int count, String message,
                        String closestLabel, int color, float ttc) {
        this.level = level;
        this.distance = distance;
        this.count = count;
        this.message = message;
        this.closestLabel = closestLabel;
        this.color = color;
        this.ttc = ttc;
    }
}
