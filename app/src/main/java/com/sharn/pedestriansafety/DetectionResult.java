package com.sharn.pedestriansafety;

import android.graphics.RectF;

/**
 * 偵測結果資料類別
 */
public class DetectionResult {
    private final String label;
    private final float confidence;
    private final RectF location;
    private final int trackingId;
    
    public DetectionResult(String label, float confidence, RectF location) {
        this(label, confidence, location, -1);
    }
    
    public DetectionResult(String label, float confidence, RectF location, int trackingId) {
        this.label = label;
        this.confidence = confidence;
        this.location = location;
        this.trackingId = trackingId;
    }
    
    public String getLabel() { 
        return label; 
    }
    
    public float getConfidence() { 
        return confidence; 
    }
    
    public RectF getLocation() { 
        return location; 
    }
    
    public int getTrackingId() {
        return trackingId;
    }
    
    /**
     * 是否為行人
     */
    public boolean isPedestrian() {
        String lowerLabel = label.toLowerCase();
        return lowerLabel.contains("person") || 
               lowerLabel.contains("pedestrian") ||
               lowerLabel.contains("people") ||
               lowerLabel.contains("man") ||
               lowerLabel.contains("woman");
    }
    
    /**
     * 是否為交通號誌
     */
    public boolean isTrafficLight() {
        String lowerLabel = label.toLowerCase();
        return lowerLabel.contains("traffic light") ||
               lowerLabel.contains("traffic_light") ||
               lowerLabel.contains("trafficlight") ||
               lowerLabel.contains("stoplight") ||
               lowerLabel.contains("signal");
    }
    
    /**
     * 獲取警示訊息
     */
    public String getAlertMessage() {
        if (isPedestrian()) {
            if (confidence > 0.8) {
                return "警告！前方有行人，請減速";
            } else {
                return "注意！前方可能有行人";
            }
        } else if (isTrafficLight()) {
            return "注意交通號誌";
        }
        return "注意：" + label;
    }
    
    @Override
    public String toString() {
        return String.format("%s (%.1f%%)", label, confidence * 100);
    }
}
