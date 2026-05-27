package com.sharn.trafficsafety;

import android.graphics.RectF;

/**
 * 不可變偵測結果資料類別。
 */
public final class DetectionResult {
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
        this.location = location != null ? new RectF(location) : null;
        this.trackingId = trackingId;
    }
    
    public String getLabel() { 
        return label; 
    }
    
    public float getConfidence() { 
        return confidence; 
    }
    
    public RectF getLocation() { 
        return location != null ? new RectF(location) : null;
    }
    
    public int getTrackingId() {
        return trackingId;
    }
    
    /**
     * 是否為行人
     */
    public boolean isPedestrian() {
        return LabelUtils.isPedestrian(label);
    }
    
    /**
     * 是否為交通號誌
     */
    public boolean isTrafficLight() {
        return LabelUtils.isTrafficLight(label);
    }
    
    /**
     * v2.6: 使用 LabelUtils 取得警示訊息
     */
    public String getAlertMessage() {
        String chineseLabel = LabelUtils.getChineseLabel(label);
        
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
     * 獲取中文標籤
     */
    public String getChineseLabel() {
        return LabelUtils.getChineseLabel(label);
    }

    @Override
    public String toString() {
        return String.format("%s (%.1f%%)", label, confidence * 100);
    }
}
