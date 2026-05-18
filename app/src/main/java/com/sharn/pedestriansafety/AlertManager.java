package com.sharn.pedestriansafety;

import android.os.SystemClock;

import java.util.List;

/**
 * 警示管理器
 * 處理偵測結果並觸發適當的警示
 */
public class AlertManager {
    
    private static final int CONSECUTIVE_THRESHOLD = 3;  // 連續幀數閥值
    private static final long COOLDOWN_MS = 5000;        // 冷卻時間（5秒）
    private static final float MIN_CONFIDENCE = 0.5f;    // 最低靈認度
    
    private int consecutivePersonFrames = 0;
    private int consecutiveTrafficFrames = 0;
    private long lastPersonAlertTime = 0;
    private long lastTrafficAlertTime = 0;
    
    private final AlertCallback callback;
    
    public interface AlertCallback {
        void onAlert(DetectionResult detection);
    }
    
    public AlertManager(AlertCallback callback) {
        this.callback = callback;
    }
    
    public void processDetections(List<DetectionResult> detections, 
                                   boolean enableSound, 
                                   boolean enableVoice) {
        
        boolean hasPerson = false;
        boolean hasTraffic = false;
        DetectionResult topPerson = null;
        DetectionResult topTraffic = null;
        
        // 分析偵測結果
        for (DetectionResult detection : detections) {
            if (detection.getConfidence() < MIN_CONFIDENCE) continue;
            
            if (detection.isPedestrian()) {
                hasPerson = true;
                if (topPerson == null || detection.getConfidence() > topPerson.getConfidence()) {
                    topPerson = detection;
                }
            } else if (detection.isTrafficLight()) {
                hasTraffic = true;
                if (topTraffic == null || detection.getConfidence() > topTraffic.getConfidence()) {
                    topTraffic = detection;
                }
            }
        }
        
        long now = SystemClock.elapsedRealtime();
        
        // 處理行人警示
        if (hasPerson) {
            consecutivePersonFrames++;
            if (consecutivePersonFrames >= CONSECUTIVE_THRESHOLD) {
                if (now - lastPersonAlertTime > COOLDOWN_MS) {
                    if (callback != null && topPerson != null) {
                        callback.onAlert(topPerson);
                    }
                    lastPersonAlertTime = now;
                    consecutivePersonFrames = 0; // 重置避免重複
                }
            }
        } else {
            consecutivePersonFrames = Math.max(0, consecutivePersonFrames - 1);
        }
        
        // 處理號誌警示
        if (hasTraffic) {
            consecutiveTrafficFrames++;
            if (consecutiveTrafficFrames >= CONSECUTIVE_THRESHOLD) {
                if (now - lastTrafficAlertTime > COOLDOWN_MS) {
                    if (callback != null && topTraffic != null) {
                        callback.onAlert(topTraffic);
                    }
                    lastTrafficAlertTime = now;
                    consecutiveTrafficFrames = 0;
                }
            }
        } else {
            consecutiveTrafficFrames = Math.max(0, consecutiveTrafficFrames - 1);
        }
    }
    
    public void reset() {
        consecutivePersonFrames = 0;
        consecutiveTrafficFrames = 0;
        lastPersonAlertTime = 0;
        lastTrafficAlertTime = 0;
    }
}
