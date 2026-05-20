package com.sharn.trafficsafety;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v2.3: 專用紅綠燈偵測器
 * 兩步驟法：Step 1 定位紅綠燈位置 → Step 2 識別燈號狀態
 * 解決 YOLO classification-only 的問題
 */
public class TrafficLightDetector {
    private static final String TAG = "TrafficLightDetector";
    
    // HSV 顏色範圍 - 用於偵測紅綠燈外框和燈號
    private final Scalar RED_LOWER1 = new Scalar(0, 100, 100);
    private final Scalar RED_UPPER1 = new Scalar(10, 255, 255);
    private final Scalar RED_LOWER2 = new Scalar(160, 100, 100);
    private final Scalar RED_UPPER2 = new Scalar(180, 255, 255);
    private final Scalar YELLOW_LOWER = new Scalar(15, 100, 100);
    private final Scalar YELLOW_UPPER = new Scalar(35, 255, 255);
    private final Scalar GREEN_LOWER = new Scalar(40, 80, 80);
    private final Scalar GREEN_UPPER = new Scalar(90, 255, 255);
    
    // 燈號外框顏色（通常為黑色或深色）
    private final Scalar DARK_LOWER = new Scalar(0, 0, 0);
    private final Scalar DARK_UPPER = new Scalar(180, 255, 60);
    
    // 燈號狀態
    public enum LightState {
        RED, YELLOW, GREEN, UNKNOWN, OFF
    }
    
    public static class TrafficLightResult {
        public RectF boundingBox;      // 正規化座標 [0,1]
        public Rect pixelBox;            // 像素座標
        public LightState state;         // 燈號狀態
        public float confidence;         // 信心度
        public float centerX, centerY;   // 中心點
        public boolean isLit;            // 是否亮燈
        
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
     * 主要偵測入口 - 兩步驟流程
     */
    public List<TrafficLightResult> detect(Bitmap bitmap) {
        List<TrafficLightResult> results = new ArrayList<>();
        
        try {
            // Step 1: 轉換到 HSV 色彩空間
            Mat src = new Mat();
            Utils.bitmapToMat(bitmap, src);
            
            Mat hsv = new Mat();
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV);
            
            // Step 2: 定位紅綠燈外框（尋找潛在的紅綠燈位置）
            List<TrafficLightCandidate> candidates = locateTrafficLights(src, hsv);
            
            // Step 3: 對每個候選區域識別燈號狀態
            for (TrafficLightCandidate candidate : candidates) {
                TrafficLightResult result = recognizeLightState(src, hsv, candidate);
                if (result != null && result.confidence > 0.5f) {
                    results.add(result);
                }
            }
            
            // Step 4: NMS 去重
            results = applyNMS(results, 0.5f);
            
            // 釋放記憶體
            src.release();
            hsv.release();
            
            Log.d(TAG, "Detected " + results.size() + " traffic lights");
            
        } catch (Exception e) {
            Log.e(TAG, "Traffic light detection failed", e);
        }
        
        return results;
    }
    
    /**
     * Step 1: 定位紅綠燈位置
     * 尋找畫面中可能是紅綠燈的區域
     */
    private List<TrafficLightCandidate> locateTrafficLights(Mat src, Mat hsv) {
        List<TrafficLightCandidate> candidates = new ArrayList<>();
        
        // 策略1: 尋找紅色燈號（最明顯）
        candidates.addAll(findColorLights(src, hsv, RED_LOWER1, RED_UPPER1, RED_LOWER2, RED_UPPER2));
        
        // 策略2: 尋找綠色燈號
        candidates.addAll(findColorLights(src, hsv, GREEN_LOWER, GREEN_UPPER, null, null));
        
        // 策略3: 尋找黃色燈號
        candidates.addAll(findColorLights(src, hsv, YELLOW_LOWER, YELLOW_UPPER, null, null));
        
        // 策略4: 尋找直立黑框（紅綠燈外殼）
        candidates.addAll(findDarkHousings(src, hsv));
        
        return candidates;
    }
    
    /**
     * 尋找特定顏色的燈號
     */
    private List<TrafficLightCandidate> findColorLights(Mat src, Mat hsv, 
                                                         Scalar lower1, Scalar upper1,
                                                         Scalar lower2, Scalar upper2) {
        List<TrafficLightCandidate> candidates = new ArrayList<>();
        
        // 建立顏色遮罩
        Mat mask = new Mat();
        if (lower2 != null && upper2 != null) {
            Mat mask1 = new Mat();
            Mat mask2 = new Mat();
            Core.inRange(hsv, lower1, upper1, mask1);
            Core.inRange(hsv, lower2, upper2, mask2);
            Core.bitwise_or(mask1, mask2, mask);
            mask1.release();
            mask2.release();
        } else {
            Core.inRange(hsv, lower1, upper1, mask);
        }
        
        // 形態學操作
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(7, 7));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, new Point(-1, -1), 1);
        
        // 尋找輪廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);
            
            // 過濾條件：面積和長寬比
            if (area < 200) continue;
            if (area > src.width() * src.height() * 0.05) continue;
            
            float aspectRatio = (float) rect.width / rect.height;
            
            // 紅綠燈特徵：直立長方形或圓形
            if ((aspectRatio > 0.2f && aspectRatio < 0.8f) ||  // 直立長方形
                (aspectRatio > 0.7f && aspectRatio < 1.3f)) {  // 圓形單燈
                
                candidates.add(new TrafficLightCandidate(rect, area));
            }
        }
        
        // 釋放記憶體
        mask.release();
        kernel.release();
        hierarchy.release();
        
        return candidates;
    }
    
    /**
     * 尋找深色外框（紅綠燈外殼）
     */
    private List<TrafficLightCandidate> findDarkHousings(Mat src, Mat hsv) {
        List<TrafficLightCandidate> candidates = new ArrayList<>();
        
        // 偵測深色區域
        Mat darkMask = new Mat();
        Core.inRange(hsv, DARK_LOWER, DARK_UPPER, darkMask);
        
        // 形態學操作
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 15));
        Imgproc.morphologyEx(darkMask, darkMask, Imgproc.MORPH_CLOSE, kernel);
        
        // 尋找輪廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(darkMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);
            
            if (area < 500) continue;
            if (area > src.width() * src.height() * 0.1) continue;
            
            float aspectRatio = (float) rect.width / rect.height;
            
            // 直立長方形（典型紅綠燈外殼）
            if (aspectRatio > 0.25f && aspectRatio < 0.7f && rect.height > rect.width * 2) {
                candidates.add(new TrafficLightCandidate(rect, area * 0.8)); // 稍低優先級
            }
        }
        
        darkMask.release();
        kernel.release();
        hierarchy.release();
        
        return candidates;
    }
    
    /**
     * Step 2: 識別燈號狀態
     * 對已定位的區域進行紅/黃/綠燈識別
     */
    private TrafficLightResult recognizeLightState(Mat src, Mat hsv, TrafficLightCandidate candidate) {
        Rect region = candidate.rect;
        
        // 計算區域內各顏色強度
        ColorIntensity intensity = analyzeRegionColors(hsv, region);
        
        // 判斷燈號狀態
        LightState state;
        boolean isLit;
        float confidence;
        
        // 判斷哪種顏色最強
        float maxIntensity = Math.max(intensity.red, Math.max(intensity.yellow, intensity.green));
        
        if (maxIntensity < 0.05f) {
            // 都沒有亮，可能是熄燈或逆光
            state = LightState.OFF;
            isLit = false;
            confidence = 0.3f;
        } else {
            isLit = maxIntensity > 0.15f;
            
            if (intensity.red == maxIntensity) {
                state = LightState.RED;
                confidence = intensity.red / (intensity.red + intensity.yellow + intensity.green + 0.001f);
            } else if (intensity.yellow == maxIntensity) {
                state = LightState.YELLOW;
                confidence = intensity.yellow / (intensity.red + intensity.yellow + intensity.green + 0.001f);
            } else {
                state = LightState.GREEN;
                confidence = intensity.green / (intensity.red + intensity.yellow + intensity.green + 0.001f);
            }
        }
        
        // 轉換為正規化座標
        RectF normBox = new RectF(
            (float) region.x / src.cols(),
            (float) region.y / src.rows(),
            (float) (region.x + region.width) / src.cols(),
            (float) (region.y + region.height) / src.rows()
        );
        
        return new TrafficLightResult(normBox, region, state, confidence, isLit);
    }
    
    /**
     * 分析區域內顏色強度
     */
    private ColorIntensity analyzeRegionColors(Mat hsv, Rect region) {
        // 確保區域在範圍內
        region.x = Math.max(0, region.x - 5);
        region.y = Math.max(0, region.y - 5);
        region.width = Math.min(hsv.width() - region.x, region.width + 10);
        region.height = Math.min(hsv.height() - region.y, region.height + 10);
        
        if (region.width <= 0 || region.height <= 0) {
            return new ColorIntensity(0, 0, 0);
        }
        
        Mat roi = new Mat(hsv, region);
        
        // 計算各顏色遮罩
        Mat redMask1 = new Mat();
        Mat redMask2 = new Mat();
        Mat yellowMask = new Mat();
        Mat greenMask = new Mat();
        
        Core.inRange(roi, RED_LOWER1, RED_UPPER1, redMask1);
        Core.inRange(roi, RED_LOWER2, RED_UPPER2, redMask2);
        Core.inRange(roi, YELLOW_LOWER, YELLOW_UPPER, yellowMask);
        Core.inRange(roi, GREEN_LOWER, GREEN_UPPER, greenMask);
        
        Mat redMask = new Mat();
        Core.bitwise_or(redMask1, redMask2, redMask);
        
        // 計算非零像素數
        int totalPixels = region.width * region.height;
        int redPixels = Core.countNonZero(redMask);
        int yellowPixels = Core.countNonZero(yellowMask);
        int greenPixels = Core.countNonZero(greenMask);
        
        // 釋放記憶體
        roi.release();
        redMask1.release();
        redMask2.release();
        redMask.release();
        yellowMask.release();
        greenMask.release();
        
        return new ColorIntensity(
            (float) redPixels / totalPixels,
            (float) yellowPixels / totalPixels,
            (float) greenPixels / totalPixels
        );
    }
    
    /**
     * 非極大值抑制
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
    private static class TrafficLightCandidate {
        Rect rect;
        double area;
        
        TrafficLightCandidate(Rect rect, double area) {
            this.rect = rect;
            this.area = area;
        }
    }
    
    private static class ColorIntensity {
        float red, yellow, green;
        
        ColorIntensity(float r, float y, float g) {
            this.red = r;
            this.yellow = y;
            this.green = g;
        }
    }
    
    // 靜態工具方法
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
