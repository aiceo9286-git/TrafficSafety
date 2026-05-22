package com.sharn.trafficsafety;

import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.1 偵測追蹤器
 * 
 * 改善項目：
 * 1. 更精確的距離估算（根據類別和框大小）
 * 2. 速度估算（預測碰撞時間 TTC）
 * 3. 更穩定的追蹤（匈牙利算法匹配）
 * 4. 軌跡記錄
 */
public class DetectionTracker {
    
    private static final String TAG = "DetectionTracker";
    
    // 追蹤參數 - v2.4 提高確認門檻減少誤報
    private static final int CONFIRMATION_FRAMES = 4;  // 從 3 提高到 4 更穩定
    private static final int LOST_TOLERANCE = 3;  // 從 5 降到 3 更快清除
    private static final float IOU_THRESHOLD = 0.35f;  // 從 0.30 提高到 0.35 更嚴格匹配
    private static final float MAX_MATCH_DISTANCE = 150f; // 從 200 降到 150 像素，限制跳躍
    
    private Map<Integer, TrackedObject> activeTracks = new HashMap<>();
    private int nextTrackId = 0;
    private int frameCount = 0;
    
    /**
     * v2.1 追蹤中的目標
     */
    public static class TrackedObject {
        public final int id;
        public RectF bbox;
        public String label;
        public float confidence;
        public int age;
        public int lostFrames;
        public boolean isConfirmed;
        public long firstSeenTime;
        public long lastSeenTime;
        
        // 速度追蹤
        private float centerX, centerY;
        private float velocityX, velocityY;
        private List<Float> velocityHistory = new ArrayList<>();
        private static final int VELOCITY_HISTORY_SIZE = 5;
        
        // 距離估算
        private float estimatedDistance = -1;
        
        public TrackedObject(int id, RectF bbox, String label, float confidence) {
            this.id = id;
            this.bbox = new RectF(bbox);
            this.label = label;
            this.confidence = confidence;
            this.age = 1;
            this.lostFrames = 0;
            this.isConfirmed = false;
            this.firstSeenTime = System.currentTimeMillis();
            this.lastSeenTime = this.firstSeenTime;
            
            this.centerX = bbox.centerX();
            this.centerY = bbox.centerY();
            this.velocityX = 0;
            this.velocityY = 0;
        }
        
        /**
         * v2.1: 更新位置並計算速度
         */
        public void update(RectF newBbox, float alpha) {
            float newCenterX = newBbox.centerX();
            float newCenterY = newBbox.centerY();
            
            // 計算瞬時速度
            float instantVx = newCenterX - centerX;
            float instantVy = newCenterY - centerY;
            
            // 加入歷史記錄
            velocityHistory.add((float) Math.sqrt(instantVx * instantVx + instantVy * instantVy));
            if (velocityHistory.size() > VELOCITY_HISTORY_SIZE) {
                velocityHistory.remove(0);
            }
            
            // 指數平滑
            centerX = centerX * (1 - alpha) + newCenterX * alpha;
            centerY = centerY * (1 - alpha) + newCenterY * alpha;
            
            // 平滑框大小
            float newWidth = newBbox.width();
            float newHeight = newBbox.height();
            float currentWidth = bbox.width();
            float currentHeight = bbox.height();
            
            float smoothWidth = currentWidth * (1 - alpha) + newWidth * alpha;
            float smoothHeight = currentHeight * (1 - alpha) + newHeight * alpha;
            
            // 更新 bbox
            bbox.left = centerX - smoothWidth / 2;
            bbox.top = centerY - smoothHeight / 2;
            bbox.right = centerX + smoothWidth / 2;
            bbox.bottom = centerY + smoothHeight / 2;
            
            // 更新速度（使用平均速度更穩定）
            velocityX = velocityX * 0.8f + instantVx * 0.2f;
            velocityY = velocityY * 0.8f + instantVy * 0.2f;
            
            age++;
            lostFrames = 0;
            lastSeenTime = System.currentTimeMillis();
        }
        
        /**
         * 預測位置
         */
        public void predict() {
            centerX += velocityX;
            centerY += velocityY;
            
            float width = bbox.width();
            float height = bbox.height();
            bbox.left = centerX - width / 2;
            bbox.top = centerY - height / 2;
            bbox.right = centerX + width / 2;
            bbox.bottom = centerY + height / 2;
            
            lostFrames++;
        }
        
        /**
         * v2.1: 更精確的距離估算
         */
        public float estimateDistance(String label, int imgWidth, int imgHeight) {
            // 使用框面積估算距離
            float boxArea = bbox.width() * bbox.height();
            float imageArea = imgWidth * imgHeight;
            float areaRatio = boxArea / imageArea;
            
            // 不同類別的參考面積（近距離時佔畫面比例）
            float refAreaRatio;
            switch (label) {
                case "person":
                    refAreaRatio = 0.08f; // 行人近距離佔 8%
                    break;
                case "motorcycle":
                    refAreaRatio = 0.06f;
                    break;
                case "car":
                    refAreaRatio = 0.15f;
                    break;
                case "bus":
                case "truck":
                    refAreaRatio = 0.25f;
                    break;
                case "bicycle":
                    refAreaRatio = 0.04f;
                    break;
                default:
                    refAreaRatio = 0.08f;
            }
            
            // 距離與面積成反比（簡化模型）
            if (areaRatio > 0) {
                estimatedDistance = (float) (10 * Math.sqrt(refAreaRatio / areaRatio));
            } else {
                estimatedDistance = 100f;
            }
            
            // 限制合理範圍
            return Math.max(5f, Math.min(100f, estimatedDistance));
        }
        
        /**
         * v2.1: 估算碰撞時間 (Time To Collision)
         */
        public float estimateTTC(int imgHeight) {
            if (velocityHistory.isEmpty()) return Float.MAX_VALUE;
            
            // 計算平均速度
            float avgVelocity = 0;
            for (float v : velocityHistory) {
                avgVelocity += v;
            }
            avgVelocity /= velocityHistory.size();
            
            // 如果目標在遠離（向上移動），不計算 TTC
            if (velocityY < 0) return Float.MAX_VALUE;
            
            // 估算到畫面底部的距離
            float distanceToBottom = imgHeight - bbox.bottom;
            
            // TTC = 距離 / 速度（假設速度維持）
            if (avgVelocity > 1) {
                return distanceToBottom / avgVelocity;
            }
            
            return Float.MAX_VALUE;
        }
        
        /**
         * 計算與另一個框的距離
         */
        public float distanceTo(RectF other) {
            float dx = centerX - other.centerX();
            float dy = centerY - other.centerY();
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
        
        public boolean shouldConfirm() {
            return age >= CONFIRMATION_FRAMES;
        }
        
        public boolean isLost() {
            return lostFrames > LOST_TOLERANCE;
        }
        
        public long getTrackingDuration() {
            return System.currentTimeMillis() - firstSeenTime;
        }
    }
    
    /**
     * v2.1: 使用匈牙利算法改進匹配
     */
    public List<TrackedObject> update(List<DetectionResult> newDetections, int imgWidth, int imgHeight) {
        frameCount++;
        
        // Step 1: 預測現有追蹤
        for (TrackedObject track : activeTracks.values()) {
            track.predict();
        }
        
        // Step 2: 匈牙利算法匹配（簡化版）
        List<TrackedObject> matchedTracks = new ArrayList<>();
        List<DetectionResult> matchedDetections = new ArrayList<>();
        
        // 計算 cost matrix
        for (DetectionResult det : newDetections) {
            float bestScore = IOU_THRESHOLD;
            TrackedObject bestTrack = null;
            
            for (TrackedObject track : activeTracks.values()) {
                if (track.isLost()) continue;
                if (!track.label.equals(det.getLabel())) continue;
                
                float iou = calculateIoU(track.bbox, det.getLocation());
                float dist = track.distanceTo(det.getLocation());
                
                // 綜合分數：IOU + 距離
                float score = iou - (dist / MAX_MATCH_DISTANCE) * 0.1f;
                
                if (score > bestScore && dist < MAX_MATCH_DISTANCE) {
                    bestScore = score;
                    bestTrack = track;
                }
            }
            
            if (bestTrack != null) {
                bestTrack.update(det.getLocation(), 0.7f);
                bestTrack.confidence = det.getConfidence();
                matchedTracks.add(bestTrack);
                matchedDetections.add(det);
            }
        }
        
        // Step 3: 未匹配的創建新追蹤
        for (DetectionResult det : newDetections) {
            if (!matchedDetections.contains(det)) {
                // 檢查是否有已丟失的同類別追蹤可以復活
                boolean revived = false;
                for (TrackedObject track : activeTracks.values()) {
                    if (track.isLost() && track.label.equals(det.getLabel())) {
                        float dist = track.distanceTo(det.getLocation());
                        if (dist < MAX_MATCH_DISTANCE * 1.5f) {
                            track.bbox = new RectF(det.getLocation());
                            track.centerX = track.bbox.centerX();
                            track.centerY = track.bbox.centerY();
                            track.lostFrames = 0;
                            track.confidence = det.getConfidence();
                            revived = true;
                            break;
                        }
                    }
                }
                
                if (!revived) {
                    TrackedObject newTrack = new TrackedObject(
                        nextTrackId++, det.getLocation(), det.getLabel(), det.getConfidence()
                    );
                    activeTracks.put(newTrack.id, newTrack);
                }
            }
        }
        
        // Step 4: 移除真正丟失的追蹤
        activeTracks.entrySet().removeIf(entry -> {
            TrackedObject track = entry.getValue();
            return track.isLost() && track.getTrackingDuration() > 5000; // 追蹤超過5秒才刪除
        });
        
        // Step 5: 確認新追蹤
        for (TrackedObject track : activeTracks.values()) {
            if (!track.isConfirmed && track.shouldConfirm()) {
                track.isConfirmed = true;
                Log.d(TAG, "追蹤 " + track.id + " [" + track.label + "] 已確認（" + track.age + " 幀）");
            }
        }
        
        // 返回已確認的追蹤
        List<TrackedObject> confirmedTracks = new ArrayList<>();
        for (TrackedObject track : activeTracks.values()) {
            if (track.isConfirmed) {
                // 更新距離估算
                track.estimatedDistance = track.estimateDistance(track.label, imgWidth, imgHeight);
                confirmedTracks.add(track);
            }
        }
        
        return confirmedTracks;
    }
    
    /**
     * 獲取指定 ID 的追蹤
     */
    public TrackedObject getTrack(int id) {
        return activeTracks.get(id);
    }
    
    /**
     * 清除所有追蹤
     */
    public void clear() {
        activeTracks.clear();
        nextTrackId = 0;
        frameCount = 0;
    }
    
    /**
     * 獲取追蹤統計
     */
    public String getStats() {
        int confirmed = 0;
        int pending = 0;
        for (TrackedObject track : activeTracks.values()) {
            if (track.isConfirmed) confirmed++;
            else pending++;
        }
        return " frame: " + frameCount + ", confirmed: " + confirmed + ", pending: " + pending;
    }
    
    private float calculateIoU(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        
        if (right < left || bottom < top) return 0;
        
        float intersection = (right - left) * (bottom - top);
        float areaA = a.width() * a.height();
        float areaB = b.width() * b.height();
        return intersection / (areaA + areaB - intersection);
    }
}