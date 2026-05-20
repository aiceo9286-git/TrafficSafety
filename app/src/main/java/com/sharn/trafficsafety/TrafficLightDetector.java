package com.sharn.trafficsafety;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v2.3: 專用紅綠燈偵測器
 * 使用純 Java HSV 色彩分析，無需 OpenCV
 * 兩步驟法：先定位 → 再識別
 */
public class TrafficLightDetector {
    private static final String TAG = "TrafficLightDetector";
    
    // 燈號狀態
    public enum LightState {
        RED, YELLOW, GREEN, UNKNOWN, OFF
    }
    
    public static class TrafficLightResult {
        public RectF boundingBox;      // 正規化座標 [0,1]
        public Rect pixelBox;          // 像素座標
        public LightState state;       // 燈號狀態
        public float confidence;       // 信心度
        public float centerX, centerY; // 中心點
        public boolean isLit;          // 是否亮燈
        
        public TrafficLightResult(RectF box, Rect pixelBox, LightState state, float conf, boolean isLit) {
            this.boundingBox = box;
            this.pixelBox = pixelBox;
            this.state = state;
            this.confidence = conf;
            this.centerX = box.centerX();
            this.centerY = box.centerY();
            this.isLit = isLit;
        }
        
        public String getStateLabel() {
            switch (state) {
                case RED: return isLit ? "🔴 紅燈" : "⚫ 紅燈(暗)";
                case YELLOW: return isLit ? "🟡 黃燈" : "⚫ 黃燈(暗)";
                case GREEN: return isLit ? "🟢 綠燈" : "⚫ 綠燈(暗)";
                case OFF: return "⚪ 熄燈";
                default: return "❓ 未知";
            }
        }
    }
    
    /**
     * 主要偵測入口
     */
    public List<TrafficLightResult> detect(Bitmap bitmap) {
        List<TrafficLightResult> results = new ArrayList<>();
        
        try {
            // Step 1: 定位潛在的燈號區域（快速掃描）
            List<CandidateRegion> candidates = locateCandidateRegions(bitmap);
            
            // Step 2: 對每個候選區域進行顏色分析
            for (CandidateRegion candidate : candidates) {
                TrafficLightResult result = analyzeRegion(bitmap, candidate);
                if (result != null && result.confidence > 0.3f) {
                    results.add(result);
                }
            }
            
            // Step 3: NMS 去重
            results = applyNMS(results, 0.5f);
            
            Log.d(TAG, "Detected " + results.size() + " traffic lights");
            
        } catch (Exception e) {
            Log.e(TAG, "Traffic light detection failed", e);
        }
        
        return results;
    }
    
    /**
     * Step 1: 定位候選區域
     * 掃描畫面，尋找具有紅/黃/綠色的區域
     */
    private List<CandidateRegion> locateCandidateRegions(Bitmap bitmap) {
        List<CandidateRegion> candidates = new ArrayList<>();
        
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // 使用網格掃描，避免逐像素處理
        int gridSize = 20; // 每 20x20 像素掃描一次
        int minRegionSize = 30;
        int maxRegionSize = Math.min(width, height) / 3;
        
        // 尋找具有鮮豔顏色的區塊
        for (int y = 0; y < height - gridSize; y += gridSize) {
            for (int x = 0; x < width - gridSize; x += gridSize) {
                // 採樣中心點
                int cx = x + gridSize / 2;
                int cy = y + gridSize / 2;
                
                ColorPoint point = analyzePoint(bitmap, cx, cy);
                
                if (point.isTrafficLightColor && point.saturation > 0.5f) {
                    // 找到潛在的燈號區域
                    CandidateRegion candidate = new CandidateRegion(
                        cx - minRegionSize/2, cy - minRegionSize,
                        minRegionSize, minRegionSize * 2,
                        point.colorType
                    );
                    
                    // 調整區域大小
                    candidate = expandRegion(bitmap, candidate, point.colorType);
                    
                    if (candidate.width > 20 && candidate.height > 40) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        
        return mergeNearbyCandidates(candidates);
    }
    
    /**
     * 分析單一像素點
     */
    private ColorPoint analyzePoint(Bitmap bitmap, int x, int y) {
        int pixel = bitmap.getPixel(x, y);
        
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        
        // 轉換到 HSV
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];
        
        ColorPoint point = new ColorPoint();
        point.saturation = s;
        point.value = v;
        
        // 判斷顏色類型
        if (s > 0.3f && v > 0.3f) {
            if ((h >= 0 && h < 20) || (h >= 340 && h <= 360)) {
                point.colorType = ColorType.RED;
                point.isTrafficLightColor = true;
            } else if (h >= 40 && h < 80) {
                point.colorType = ColorType.GREEN;
                point.isTrafficLightColor = true;
            } else if (h >= 20 && h < 40) {
                point.colorType = ColorType.YELLOW;
                point.isTrafficLightColor = true;
            }
        }
        
        return point;
    }
    
    /**
     * 擴展區域以包含完整的燈號
     */
    private CandidateRegion expandRegion(Bitmap bitmap, CandidateRegion seed, ColorType targetColor) {
        int x = seed.x;
        int y = seed.y;
        int w = seed.width;
        int h = seed.height;
        
        // 向外搜索相同顏色的像素
        boolean expanded = true;
        int maxIterations = 50;
        int iterations = 0;
        
        while (expanded && iterations < maxIterations) {
            expanded = false;
            iterations++;
            
            // 向上擴展
            if (y > 0 && hasColorInRow(bitmap, x, y - 1, w, targetColor)) {
                y--;
                h++;
                expanded = true;
            }
            // 向下擴展
            if (y + h < bitmap.getHeight() && hasColorInRow(bitmap, x, y + h, w, targetColor)) {
                h++;
                expanded = true;
            }
            // 向左擴展
            if (x > 0 && hasColorInColumn(bitmap, x - 1, y, h, targetColor)) {
                x--;
                w++;
                expanded = true;
            }
            // 向右擴展
            if (x + w < bitmap.getWidth() && hasColorInColumn(bitmap, x + w, y, h, targetColor)) {
                w++;
                expanded = true;
            }
        }
        
        return new CandidateRegion(x, y, w, h, targetColor);
    }
    
    /**
     * 檢查行是否存在目標顏色
     */
    private boolean hasColorInRow(Bitmap bitmap, int x, int y, int width, ColorType targetColor) {
        for (int i = x; i < Math.min(x + width, bitmap.getWidth()); i++) {
            ColorPoint point = analyzePoint(bitmap, i, y);
            if (point.colorType == targetColor && point.saturation > 0.3f) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 檢查列是否存在目標顏色
     */
    private boolean hasColorInColumn(Bitmap bitmap, int x, int y, int height, ColorType targetColor) {
        for (int i = y; i < Math.min(y + height, bitmap.getHeight()); i++) {
            ColorPoint point = analyzePoint(bitmap, x, i);
            if (point.colorType == targetColor && point.saturation > 0.3f) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Step 2: 分析區域內的燈號狀態
     */
    private TrafficLightResult analyzeRegion(Bitmap bitmap, CandidateRegion region) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // 計算整個區域的顏色分佈
        int[] colorCounts = new int[3]; // 0=red, 1=yellow, 2=green
        int totalPixels = 0;
        
        for (int y = region.y; y < Math.min(region.y + region.height, height); y += 2) {
            for (int x = region.x; x < Math.min(region.x + region.width, width); x += 2) {
                ColorPoint point = analyzePoint(bitmap, x, y);
                if (point.isTrafficLightColor) {
                    totalPixels++;
                    switch (point.colorType) {
                        case RED: colorCounts[0]++; break;
                        case YELLOW: colorCounts[1]++; break;
                        case GREEN: colorCounts[2]++; break;
                    }
                }
            }
        }
        
        if (totalPixels < 50) return null;
        
        // 判斷哪種顏色佔主導
        LightState state;
        float confidence;
        boolean isLit;
        
        int maxCount = Math.max(colorCounts[0], Math.max(colorCounts[1], colorCounts[2]));
        float density = (float) maxCount / (region.width * region.height / 4);
        
        isLit = density > 0.05f;
        
        if (colorCounts[0] == maxCount) {
            state = LightState.RED;
            confidence = (float) colorCounts[0] / totalPixels;
        } else if (colorCounts[1] == maxCount) {
            state = LightState.YELLOW;
            confidence = (float) colorCounts[1] / totalPixels;
        } else {
            state = LightState.GREEN;
            confidence = (float) colorCounts[2] / totalPixels;
        }

        // 轉換為正規化座標
        RectF normBox = new RectF(
            (float) region.x / width,
            (float) region.y / height,
            (float) (region.x + region.width) / width,
            (float) (region.y + region.height) / height
        );
        
        Rect pixelBox = new Rect(region.x, region.y, 
            region.x + region.width, region.y + region.height);
        
        return new TrafficLightResult(normBox, pixelBox, state, confidence, isLit);
    }
    
    /**
     * 合併相近的候選區域
     */
    private List<CandidateRegion> mergeNearbyCandidates(List<CandidateRegion> candidates) {
        List<CandidateRegion> merged = new ArrayList<>();
        boolean[] used = new boolean[candidates.size()];
        
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) continue;
            
            CandidateRegion current = candidates.get(i);
            used[i] = true;
            
            // 尋找相近的區域
            for (int j = i + 1; j < candidates.size(); j++) {
                if (used[j]) continue;
                
                CandidateRegion other = candidates.get(j);
                if (current.colorType == other.colorType && 
                    distance(current, other) < 50) {
                    // 合併
                    current = mergeRegions(current, other);
                    used[j] = true;
                }
            }
            
            merged.add(current);
        }
        
        return merged;
    }
    
    /**
     * 計算兩個區域的中心距離
     */
    private float distance(CandidateRegion a, CandidateRegion b) {
        float cx1 = a.x + a.width / 2f;
        float cy1 = a.y + a.height / 2f;
        float cx2 = b.x + b.width / 2f;
        float cy2 = b.y + b.height / 2f;
        return (float) Math.sqrt(Math.pow(cx1 - cx2, 2) + Math.pow(cy1 - cy2, 2));
    }
    
    /**
     * 合併兩個區域
     */
    private CandidateRegion mergeRegions(CandidateRegion a, CandidateRegion b) {
        int minX = Math.min(a.x, b.x);
        int minY = Math.min(a.y, b.y);
        int maxX = Math.max(a.x + a.width, b.x + b.width);
        int maxY = Math.max(a.y + a.height, b.y + b.height);
        
        return new CandidateRegion(minX, minY, maxX - minX, maxY - minY, a.colorType);
    }
    
    /**
     * NMS 去重
     */
    private List<TrafficLightResult> applyNMS(List<TrafficLightResult> results, float threshold) {
        Collections.sort(results, (a, b) -> Float.compare(b.confidence, a.confidence));
        
        List<TrafficLightResult> filtered = new ArrayList<>();
        boolean[] suppressed = new boolean[results.size()];
        
        for (int i = 0; i < results.size(); i++) {
            if (suppressed[i]) continue;
            
            filtered.add(results.get(i));
            
            for (int j = i + 1; j < results.size(); j++) {
                if (suppressed[j]) continue;
                
                float iou = calculateIoU(results.get(i).boundingBox, results.get(j).boundingBox);
                if (iou > threshold) {
                    suppressed[j] = true;
                }
            }
        }
        
        return filtered;
    }
    
    /**
     * 計算 IOU
     */
    private float calculateIoU(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        
        if (right <= left || bottom <= top) return 0;
        
        float intersection = (right - left) * (bottom - top);
        float areaA = a.width() * a.height();
        float areaB = b.width() * b.height();
        float union = areaA + areaB - intersection;
        
        return intersection / union;
    }
    
    // 輔助類別
    private enum ColorType { RED, YELLOW, GREEN, NONE }
    
    private static class ColorPoint {
        ColorType colorType = ColorType.NONE;
        boolean isTrafficLightColor = false;
        float saturation;
        float value;
    }
    
    private static class CandidateRegion {
        int x, y, width, height;
        ColorType colorType;
        
        CandidateRegion(int x, int y, int width, int height, ColorType colorType) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.colorType = colorType;
        }
    }
    
    // 工具方法
    public static int getStateColor(LightState state) {
        switch (state) {
            case RED: return Color.RED;
            case YELLOW: return Color.YELLOW;
            case GREEN: return Color.GREEN;
            case OFF: return Color.GRAY;
            default: return Color.WHITE;
        }
    }
    
    public static String getSimpleLabel(LightState state) {
        switch (state) {
            case RED: return "紅燈";
            case YELLOW: return "黃燈";
            case GREEN: return "綠燈";
            case OFF: return "熄燈";
            default: return "未知";
        }
    }
    
    public static boolean isDangerous(LightState state) {
        return state == LightState.RED || state == LightState.YELLOW;
    }
}
