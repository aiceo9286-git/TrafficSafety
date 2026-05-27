package com.sharn.trafficsafety;

/**
 * 集中管理可變應用狀態，對外提供不可變 AppState 快照。
 */
public final class StateManager {
    private AppState state = AppState.defaults();

    public synchronized AppState getState() {
        return state;
    }

    public synchronized void setConfidenceThreshold(float confidenceThreshold) {
        state = state.withConfidenceThreshold(confidenceThreshold);
    }

    public synchronized void setPedestrianDetectionEnabled(boolean enabled) {
        state = state.withPedestrianDetectionEnabled(enabled);
    }

    public synchronized void setTrafficLightDetectionEnabled(boolean enabled) {
        state = state.withTrafficLightDetectionEnabled(enabled);
    }

    public synchronized void setSoundAlertEnabled(boolean enabled) {
        state = state.withSoundAlertEnabled(enabled);
    }

    public synchronized void setVoiceAlertEnabled(boolean enabled) {
        state = state.withVoiceAlertEnabled(enabled);
    }
}
