package com.sharn.trafficsafety;

import java.util.List;

/**
 * Converts tracked objects into a user-facing safety status.
 */
public final class SafetyEvaluator {
    public SafetyStatus evaluate(List<DetectionTracker.TrackedObject> tracks,
                                 int imageWidth,
                                 int imageHeight) {
        if (tracks.isEmpty()) {
            return new SafetyStatus(SafetyLevel.SAFE, "--", 0, "無目標",
                "", 0xFF4CAF50, Float.MAX_VALUE);
        }

        DetectionTracker.TrackedObject mostDangerous = null;
        float lowestDangerScore = Float.MAX_VALUE;

        for (DetectionTracker.TrackedObject track : tracks) {
            float distance = track.estimateDistance(track.label, imageWidth, imageHeight);
            float ttc = track.estimateTTC(imageHeight);
            float dangerScore = calculateDangerScore(distance, ttc, track.label);

            if (mostDangerous == null || dangerScore < lowestDangerScore) {
                lowestDangerScore = dangerScore;
                mostDangerous = track;
            }
        }

        if (mostDangerous == null) {
            return new SafetyStatus(SafetyLevel.SAFE, "--", tracks.size(), "監控中",
                "", 0xFF66BB6A, Float.MAX_VALUE);
        }

        float distance = mostDangerous.estimateDistance(mostDangerous.label, imageWidth, imageHeight);
        float ttc = mostDangerous.estimateTTC(imageHeight);
        String label = LabelUtils.getChineseLabel(mostDangerous.label);
        StringBuilder message = new StringBuilder();
        int color;
        SafetyLevel level;

        if (distance <= 15f || (ttc < 2f && ttc > 0)) {
            level = SafetyLevel.DANGER;
            color = 0xFFE53935;
            message.append("⚠️ 危險！").append(label);
            if (ttc < 10) {
                message.append(String.format(" %.1f秒碰撞", ttc));
            } else {
                message.append(String.format(" %.0f米", distance));
            }
        } else if (distance <= 40f || (ttc < 4f && ttc > 0)) {
            level = SafetyLevel.WARNING;
            color = 0xFFFFA000;
            message.append("⚡ 注意 ").append(label);
            message.append(String.format(" %.0f米", distance));
            if (ttc < 10) {
                message.append(String.format(" %.0f秒", ttc));
            }
        } else {
            level = SafetyLevel.SAFE;
            color = 0xFF66BB6A;
            message.append("✓ ").append(label);
            message.append(String.format(" %.0f米", distance));
        }

        return new SafetyStatus(level, String.format("%.0f", distance), tracks.size(),
            message.toString(), label, color, ttc);
    }

    private float calculateDangerScore(float distance, float ttc, String label) {
        float weight = LabelUtils.getWeight(label);
        if (ttc > 0 && ttc < 100) {
            return ttc / weight;
        }
        return distance / weight;
    }
}
