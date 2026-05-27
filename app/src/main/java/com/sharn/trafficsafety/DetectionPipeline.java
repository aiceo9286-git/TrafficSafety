package com.sharn.trafficsafety;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns AI inference, filtering, tracking, and safety evaluation for camera frames.
 */
public final class DetectionPipeline {
    private final ObjectDetectorWrapper objectDetector;
    private final DetectionTracker tracker;
    private final SafetyEvaluator safetyEvaluator;

    public DetectionPipeline(Context context) {
        objectDetector = new ObjectDetectorWrapper(context);
        tracker = new DetectionTracker();
        safetyEvaluator = new SafetyEvaluator();
    }

    public DetectionFrameResult analyze(Bitmap bitmap, AppState state) {
        int imageWidth = bitmap.getWidth();
        int imageHeight = bitmap.getHeight();

        List<DetectionResult> rawResults = objectDetector.detect(bitmap);
        List<DetectionResult> filtered = filterByState(rawResults, state);
        List<DetectionResult> trafficLightResults = getTrafficLightResults(rawResults, state);
        List<DetectionTracker.TrackedObject> tracked =
            tracker.update(filtered, imageWidth, imageHeight);

        return new DetectionFrameResult(
            toDisplayResults(tracked),
            trafficLightResults,
            safetyEvaluator.evaluate(tracked, imageWidth, imageHeight),
            imageWidth,
            imageHeight);
    }

    private List<DetectionResult> filterByState(List<DetectionResult> results, AppState state) {
        List<DetectionResult> filtered = new ArrayList<>();
        for (DetectionResult result : results) {
            if (result.getConfidence() < state.getConfidenceThreshold()) {
                continue;
            }
            if (result.isPedestrian() && !state.isPedestrianDetectionEnabled()) {
                continue;
            }
            if (result.isTrafficLight() && !state.isTrafficLightDetectionEnabled()) {
                continue;
            }
            filtered.add(result);
        }
        return filtered;
    }

    private List<DetectionResult> getTrafficLightResults(List<DetectionResult> results,
                                                         AppState state) {
        List<DetectionResult> trafficLights = new ArrayList<>();
        if (!state.isTrafficLightDetectionEnabled()) {
            return trafficLights;
        }

        for (DetectionResult result : results) {
            if (result.isTrafficLight()
                && result.getConfidence() >= state.getConfidenceThreshold()) {
                trafficLights.add(result);
            }
        }
        return trafficLights;
    }

    private List<DetectionResult> toDisplayResults(List<DetectionTracker.TrackedObject> tracked) {
        List<DetectionResult> displayResults = new ArrayList<>();
        for (DetectionTracker.TrackedObject track : tracked) {
            displayResults.add(new DetectionResult(track.label, track.confidence,
                track.bbox, track.id));
        }
        return displayResults;
    }

    public void close() {
        tracker.clear();
        objectDetector.close();
    }
}
