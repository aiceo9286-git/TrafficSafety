package com.sharn.trafficsafety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result produced for one analyzed camera frame.
 */
public final class DetectionFrameResult {
    private final List<DetectionResult> displayResults;
    private final List<DetectionResult> trafficLightResults;
    private final SafetyStatus safetyStatus;
    private final int imageWidth;
    private final int imageHeight;

    public DetectionFrameResult(List<DetectionResult> displayResults,
                                List<DetectionResult> trafficLightResults,
                                SafetyStatus safetyStatus,
                                int imageWidth,
                                int imageHeight) {
        this.displayResults = immutableCopy(displayResults);
        this.trafficLightResults = immutableCopy(trafficLightResults);
        this.safetyStatus = safetyStatus;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    private static List<DetectionResult> immutableCopy(List<DetectionResult> source) {
        if (source == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public List<DetectionResult> getDisplayResults() {
        return displayResults;
    }

    public List<DetectionResult> getTrafficLightResults() {
        return trafficLightResults;
    }

    public SafetyStatus getSafetyStatus() {
        return safetyStatus;
    }

    public int getImageWidth() {
        return imageWidth;
    }

    public int getImageHeight() {
        return imageHeight;
    }
}
