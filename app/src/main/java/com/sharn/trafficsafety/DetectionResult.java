package com.sharn.trafficsafety;

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
        String chineseLabel = getChineseLabel(label);
        
        if (isPedestrian()) {
            if (confidence > 0.8) {
                return "警告！前方有" + chineseLabel + "，請減速";
            } else {
                return "注意！前方可能有" + chineseLabel;
            }
        } else if (isTrafficLight()) {
            return "注意交通號誌";
        }
        return "注意：" + chineseLabel;
    }
    
    /**
     * 獲取中文標籤（現在 label 是英文，所以正常對應）
     */
    private String getChineseLabel(String label) {
        String lowerLabel = label.toLowerCase();
        switch (lowerLabel) {
            case "person": return "行人";
            case "people": return "行人";
            case "motorcycle": return "機車";
            case "motorbike": return "機車";
            case "car": return "汽車";
            case "bus": return "公車";
            case "truck": return "卡車";
            case "bicycle": return "腳踏車";
            case "bike": return "腳踏車";
            case "traffic light": return "紅綠燈";
            case "trafficlight": return "紅綠燈";
            case "stoplight": return "號誌";
            case "signal": return "號誌";
            case "stop sign": return "停止標誌";
            case "train": return "火車";
            case "airplane": return "飛機";
            default: return label;
        }
    }
    
    @Override
    public String toString() {
        return String.format("%s (%.1f%%)", label, confidence * 100);
    }
}
