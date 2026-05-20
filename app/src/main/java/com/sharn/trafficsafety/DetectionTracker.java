package com.sharn.trafficsafety;

import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 偵測追蹤器 - 解決誤判和閃爍問題
 * 功能：
 * 1. 連續幀確認機制（避免瞬間誤報）
 * 2. 卡爾曼濾波平滑（減少框跳動）
 * 3. IOU 追蹤匹配（穩定追蹤同一目標）
 * 4. 丟失追蹤（短暫遮擋不失追）
 */
public class DetectionTracker {
    
    private static final String TAG = "DetectionTracker";
    
    // 確認參數
    private static final int CONFIRMATION_FRAMES = 3;      // 需連續 3 幀才確認
    private static final int LOST_TOLERANCE = 5;           // 最多允許丟失 5 幀
    private static final float IOU_THRESHOLD = 0.3f;       // 追蹤匹配閾值
    
    // 追蹤狀態
    private Map<Integer, TrackedObject> activeTracks = new HashMap<>();
    private Map<Integer, TrackedObject> pendingDetections = new HashMap<>();
    private int nextTrackId = 0;
    
    /**
     * 追蹤中的目標
     */
    public static class TrackedObject {
        public final int id;
        public RectF bbox;
        public String label;
        public float confidence;
        public int age;              // 追蹤幀數
        public int lostFrames;       // 連續丟失幀數
        public boolean isConfirmed;  // 是否已確認
        
        // 平滑位置（卡爾曼濾波簡化版）
        private float smoothX, smoothY, smoothWidth, smoothHeight;
        private float velocityX, velocityY;  // 速度估計
        
        public TrackedObject(int id, RectF bbox, String label, float confidence) {
            this.id = id;
            this.bbox = new RectF(bbox);
            this.label = label;
            this.confidence = confidence;
            this.age = 1;
            this.lostFrames = 0;
            this.isConfirmed = false;
            
            // 初始化平滑位置
            this.smoothX = bbox.centerX();
            this.smoothY = bbox.centerY();
            this.smoothWidth = bbox.width();
            this.smoothHeight = bbox.height();
            this.velocityX = 0;
            this.velocityY = 0;
        }
        
        /**
         * 更新位置（平滑處理）
         */
        public void update(RectF newBbox, float alpha) {
            float newX = newBbox.centerX();
            float newY = newBbox.centerY();
            
            // 計算新速度
            float newVx = newX - smoothX;
            float newVy = newY - smoothY;
            
            // 更新平滑位置（指數平滑）
            smoothX = smoothX * (1 - alpha) + newX * alpha;
            smoothY = smoothY * (1 - alpha) + newY * alpha;
            smoothWidth = smoothWidth * (1 - alpha) + newBbox.width() * alpha;
            smoothHeight = smoothHeight * (1 - alpha) + newBbox.height() * alpha;
            
            // 更新速度（低通濾波）
            velocityX = velocityX * 0.7f + newVx * 0.3f;
            velocityY = velocityY * 0.7f + newVy * 0.3f;
            
            // 更新 bbox
            float halfW = smoothWidth / 2;
            float halfH = smoothHeight / 2;
            bbox.left = smoothX - halfW;
            bbox.top = smoothY - halfH;
            bbox.right = smoothX + halfW;
            bbox.bottom = smoothY + halfH;
            
            age++;
            lostFrames = 0;
        }
        
        /**
         * 預測位置（目標暫時未檢測到時）
         */
        public void predict() {
            smoothX += velocityX;
            smoothY += velocityY;
            
            float halfW = smoothWidth / 2;
            float halfH = smoothHeight / 2;
            bbox.left = smoothX - halfW;
            bbox.top = smoothY - halfH;
            bbox.right = smoothX + halfW;
            bbox.bottom = smoothY + halfH;
            
            lostFrames++;
        }
        
        public boolean shouldConfirm() {
            return age >= CONFIRMATION_FRAMES;
        }
        
        public boolean isLost() {
            return lostFrames > LOST_TOLERANCE;
        }
        
        /**
         * 估算距離（簡易版）
         */
        public float estimateDistance() {
            // 框越大離越近
            float boxArea = bbox.width() * bbox.height();
            return 100f / Math.max(boxArea * 100, 0.1f);  // 正規化面積
        }
    }
    
    /**
     * 更新追蹤
     */
    public List<TrackedObject> update(List<DetectionResult> newDetections) {
        // Step 1: 將現有追蹤預測下一位置
        for (TrackedObject track : activeTracks.values()) {
            track.predict();
        }
        
        // Step 2: IOU 匹配新偵測與現有追蹤
        List<DetectionResult> matchedDetections = new ArrayList<>();
        List<Integer> matchedTrackIds = new ArrayList<>();
        
        for (DetectionResult det : newDetections) {
            float bestIou = IOU_THRESHOLD;
            Integer bestTrackId = null;
            
            for (Map.Entry<Integer, TrackedObject> entry : activeTracks.entrySet()) {
                TrackedObject track = entry.getValue();
                if (!track.label.equals(det.getLabel())) continue;
                if (track.isLost()) continue;
                
                float iou = calculateIoU(track.bbox, det.getLocation());
                if (iou > bestIou) {
                    bestIou = iou;
                    bestTrackId = entry.getKey();
                }
            }
            
            if (bestTrackId != null) {
                // 匹配成功，更新追蹤
                TrackedObject track = activeTracks.get(bestTrackId);
                track.update(det.getLocation(), 0.7f);  // alpha = 0.7，較平滑
                track.confidence = det.getConfidence();
                matchedDetections.add(det);
                matchedTrackIds.add(bestTrackId);
            }
        }
        
        // Step 3: 未匹配的新偵測，創建新追蹤
        for (DetectionResult det : newDetections) {
            if (!matchedDetections.contains(det)) {
                TrackedObject newTrack = new TrackedObject(
                    nextTrackId++, det.getLocation(), det.getLabel(), det.getConfidence()
                );
                activeTracks.put(newTrack.id, newTrack);
            }
        }
        
        // Step 4: 移除丟失太久的追蹤
        activeTracks.entrySet().removeIf(entry -> entry.getValue().isLost());
        
        // Step 5: 確認新追蹤
        for (TrackedObject track : activeTracks.values()) {
            if (!track.isConfirmed && track.shouldConfirm()) {
                track.isConfirmed = true;
                Log.d(TAG, "追蹤 " + track.id + " 已確認（連續 " + track.age + " 幀）");
            }
        }
        
        // 返回已確認的追蹤
        List<TrackedObject> confirmedTracks = new ArrayList<>();
        for (TrackedObject track : activeTracks.values()) {
            if (track.isConfirmed) {
                confirmedTracks.add(track);
            }
        }
        
        return confirmedTracks;
    }
    
    /**
     * 獲取所有活躍追蹤（包括未確認）
     */
    public List<TrackedObject> getAllActiveTracks() {
        return new ArrayList<>(activeTracks.values());
    }
    
    /**
     * 清除所有追蹤
     */
    public void clear() {
        activeTracks.clear();
        nextTrackId = 0;
    }
    
    /**
     * 計算 IOU
     */
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
