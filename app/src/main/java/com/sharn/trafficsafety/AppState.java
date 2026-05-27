package com.sharn.trafficsafety;

/**
 * 應用程式設定狀態。
 */
public final class AppState {
    private final float confidenceThreshold;
    private final boolean pedestrianDetectionEnabled;
    private final boolean trafficLightDetectionEnabled;
    private final boolean soundAlertEnabled;
    private final boolean voiceAlertEnabled;

    public AppState(float confidenceThreshold,
                    boolean pedestrianDetectionEnabled,
                    boolean trafficLightDetectionEnabled,
                    boolean soundAlertEnabled,
                    boolean voiceAlertEnabled) {
        this.confidenceThreshold = Math.max(0f, Math.min(1f, confidenceThreshold));
        this.pedestrianDetectionEnabled = pedestrianDetectionEnabled;
        this.trafficLightDetectionEnabled = trafficLightDetectionEnabled;
        this.soundAlertEnabled = soundAlertEnabled;
        this.voiceAlertEnabled = voiceAlertEnabled;
    }

    public static AppState defaults() {
        return new AppState(0.50f, true, true, true, true);
    }

    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public boolean isPedestrianDetectionEnabled() {
        return pedestrianDetectionEnabled;
    }

    public boolean isTrafficLightDetectionEnabled() {
        return trafficLightDetectionEnabled;
    }

    public boolean isSoundAlertEnabled() {
        return soundAlertEnabled;
    }

    public boolean isVoiceAlertEnabled() {
        return voiceAlertEnabled;
    }

    public AppState withConfidenceThreshold(float value) {
        return new AppState(value, pedestrianDetectionEnabled, trafficLightDetectionEnabled,
            soundAlertEnabled, voiceAlertEnabled);
    }

    public AppState withPedestrianDetectionEnabled(boolean value) {
        return new AppState(confidenceThreshold, value, trafficLightDetectionEnabled,
            soundAlertEnabled, voiceAlertEnabled);
    }

    public AppState withTrafficLightDetectionEnabled(boolean value) {
        return new AppState(confidenceThreshold, pedestrianDetectionEnabled, value,
            soundAlertEnabled, voiceAlertEnabled);
    }

    public AppState withSoundAlertEnabled(boolean value) {
        return new AppState(confidenceThreshold, pedestrianDetectionEnabled,
            trafficLightDetectionEnabled, value, voiceAlertEnabled);
    }

    public AppState withVoiceAlertEnabled(boolean value) {
        return new AppState(confidenceThreshold, pedestrianDetectionEnabled,
            trafficLightDetectionEnabled, soundAlertEnabled, value);
    }
}
