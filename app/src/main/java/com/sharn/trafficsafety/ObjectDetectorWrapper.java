package com.sharn.trafficsafety;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.1 改進版物體偵測器
 * 
 * 改善項目：
 * 1. 支援更多類別（台灣道路：行人、機車、汽車、公車、卡車、腳踏車）
 * 2. 更精確的距離估算（使用框面積和類型）
 * 3. 場景自適應（白天/夜晚模式自動切換）
 * 4. 更嚴格的靜態物件過濾 
 */
public class ObjectDetectorWrapper {
    
    private static final String TAG = "TrafficSafetyDetector";
    private static final String MODEL_FILE = "yolov8n.tflite";
    
    // 動態偵測的模型參數
    private int inputSize = 320;
    private int numClasses = 80;
    private int numBoxes = 8400;
    
    // 篩選參數
    private float CONFIDENCE_THRESHOLD = 0.30f;
    private static final float IOU_THRESHOLD = 0.35f;
    
    // ROI 參數
    private static final float ROI_TOP_RATIO = 0.20f;
    private static final float ROI_BOTTOM_RATIO = 0.95f;
    private static final float ROI_LEFT_RATIO = 0.02f;
    private static final float ROI_RIGHT_RATIO = 0.98f;
    
    // 場景模式
    enum SceneMode { DAY, NIGHT, BACKLIGHT }
    private SceneMode currentSceneMode = SceneMode.DAY;
    
    private Interpreter interpreter;
    private boolean modelLoaded = false;
    
    private ByteBuffer inputBuffer;
    private float[][][] outputBuffer;
    
    // 類別名稱對應（COCO 格式）
    private static final Map<Integer, String> CLASS_NAMES = new HashMap<Integer, String>() {{
        put(0, "person");      // 行人
        put(1, "bicycle");     // 腳踏車
        put(2, "car");         // 汽車
        put(3, "motorcycle");  // 機車
        put(5, "bus");         // 公車
        put(7, "truck");       // 卡車
    }};
    
    // 類別優先級（台灣道路優先危險類別）
    private static final Map<Integer, Integer> CLASS_PRIORITY = new HashMap<Integer, Integer>() {{
        put(0, 1);   // person - 最高優先級
        put(3, 2);   // motorcycle - 次高（台灣機車多）
        put(2, 3);   // car
        put(1, 4);   // bicycle
        put(5, 5);   // bus
        put(7, 6);   // truck
    }};
    
    public ObjectDetectorWrapper(Context context) {
        try {
            Log.d(TAG, "=== v2.1 初始化偵測器 ===");
            
            AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
            FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
            FileChannel channel = fis.getChannel();
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(),
                fd.getDeclaredLength()
            );
            
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            
            // 使用 NNAPI 加速（如果支援）
            // options.setUseNNAPI(true);
            
            interpreter = new Interpreter(buffer, options);
            
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            
            Log.d(TAG, "模型輸入形狀: " + shapeToString(inputShape));
            Log.d(TAG, "模型輸出形狀: " + shapeToString(outputShape));
            
            if (inputShape.length >= 3) {
                inputSize = inputShape[1];
            }
            
            if (outputShape.length >= 3) {
                int dim1 = outputShape[1];
                int dim2 = outputShape[2];
                
                if (dim2 == 4) {
                    numBoxes = dim1;
                } else {
                    numBoxes = dim2;
                    numClasses = dim1 - 4;
                }
            }
            
            int inputBufferSize = inputSize * inputSize * 3 * 4;
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize);
            inputBuffer.order(ByteOrder.nativeOrder());
            
            if (outputShape[2] == 4) {
                outputBuffer = new float[1][numBoxes][4];
            } else {
                outputBuffer = new float[1][numClasses + 4][numBoxes];
            }
            
            modelLoaded = true;
            Log.d(TAG, "✅ v2.1 模型載入成功! 支援類別: " + CLASS_NAMES.values());
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 模型載入失敗: " + e.getMessage());
            e.printStackTrace();
            modelLoaded = false;
        }
    }
    
    private String shapeToString(int[] shape) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < shape.length; i++) {
            sb.append(shape[i]);
            if (i < shape.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * v2.1: 場景自適應偵測
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (!modelLoaded || interpreter == null) {
            return Collections.emptyList();
        }
        
        try {
            // 場景分析
            currentSceneMode = analyzeScene(bitmap);
            
            // 根據場景調整參數
            adjustThresholdForScene();
            
            prepareInput(bitmap);
            interpreter.run(inputBuffer, outputBuffer);
            
            List<DetectionResult> results = parseOutputs(bitmap.getWidth(), bitmap.getHeight());
            results = filterByROI(results, bitmap.getWidth(), bitmap.getHeight());
            
            // 按優先級排序
            return sortByPriority(results);
            
        } catch (Exception e) {
            Log.e(TAG, "偵測錯誤: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 場景分析（簡化版亮度分析）
     */
    private SceneMode analyzeScene(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampleSize = 10; // 取樣間隔
        
        long totalBrightness = 0;
        int sampleCount = 0;
        
        for (int y = 0; y < height; y += sampleSize) {
            for (int x = 0; x < width; x += sampleSize) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // 轉換為亮度
                int brightness = (int)(0.299 * r + 0.587 * g + 0.114 * b);
                totalBrightness += brightness;
                sampleCount++;
            }
        }
        
        float avgBrightness = totalBrightness / (float) sampleCount;
        
        if (avgBrightness < 40) {
            return SceneMode.NIGHT;
        } else if (avgBrightness > 200) {
            return SceneMode.BACKLIGHT;
        } else {
            return SceneMode.DAY;
        }
    }
    
    /**
     * 根據場景調整閾值
     */
    private void adjustThresholdForScene() {
        switch (currentSceneMode) {
            case NIGHT:
                CONFIDENCE_THRESHOLD = 0.25f; // 夜間降低閾值
                break;
            case BACKLIGHT:
                CONFIDENCE_THRESHOLD = 0.35f; // 逆光提高閾值
                break;
            case DAY:
            default:
                CONFIDENCE_THRESHOLD = 0.30f; // 正常白天
        }
    }
    
    /**
     * ROI 過濾（改進版：更智能的動態 ROI）
     */
    private List<DetectionResult> filterByROI(List<DetectionResult> detections, int imgWidth, int imgHeight) {
        List<DetectionResult> filtered = new ArrayList<>();
        
        // 動態調整 ROI（根據場景）
        float topRatio = (currentSceneMode == SceneMode.NIGHT) ? 0.15f : ROI_TOP_RATIO;
        
        float roiTop = imgHeight * topRatio;
        float roiBottom = imgHeight * ROI_BOTTOM_RATIO;
        float roiLeft = imgWidth * ROI_LEFT_RATIO;
        float roiRight = imgWidth * ROI_RIGHT_RATIO;
        
        for (DetectionResult det : detections) {
            RectF loc = det.getLocation();
            float centerX = loc.centerX();
            float centerY = loc.centerY();
            
            // 檢查中心點是否在 ROI 內
            if (centerX >= roiLeft && centerX <= roiRight &&
                centerY >= roiTop && centerY <= roiBottom) {
                
                // 更嚴格的靜態物體過濾
                float topPosRatio = loc.top / imgHeight;
                float heightRatio = loc.height() / imgHeight;
                
                // 如果物體在畫面上方且高度很小，可能是遠處建築
                if (topPosRatio < 0.20f && heightRatio < 0.1f && det.getConfidence() < 0.5f) {
                    Log.d(TAG, "過濾違處小物體: " + det.getLabel());
                    continue;
                }
                
                // 檢查長寬比是否符合該類別
                float aspectRatio = loc.height() / (loc.width() + 1e-6f);
                if (!isValidAspectRatio(det.getLabel(), aspectRatio)) {
                    continue;
                }
                
                filtered.add(det);
            }
        }
        
        return filtered;
    }
    
    /**
     * 檢查長寬比是否有效
     */
    private boolean isValidAspectRatio(String label, float aspectRatio) {
        switch (label) {
            case "person":
                return aspectRatio >= 1.2f && aspectRatio <= 3.5f;
            case "motorcycle":
                return aspectRatio >= 0.8f && aspectRatio <= 2.0f;
            case "car":
            case "bus":
            case "truck":
                return aspectRatio >= 0.3f && aspectRatio <= 1.5f;
            case "bicycle":
                return aspectRatio >= 0.8f && aspectRatio <= 2.0f;
            default:
                return aspectRatio >= 0.5f && aspectRatio <= 3.0f;
        }
    }
    
    /**
     * 按優先級排序
     */
    private List<DetectionResult> sortByPriority(List<DetectionResult> detections) {
        Collections.sort(detections, new Comparator<DetectionResult>() {
            @Override
            public int compare(DetectionResult r1, DetectionResult r2) {
                int p1 = CLASS_PRIORITY.getOrDefault(getClassId(r1.getLabel()), 99);
                int p2 = CLASS_PRIORITY.getOrDefault(getClassId(r2.getLabel()), 99);
                
                if (p1 != p2) {
                    return Integer.compare(p1, p2); // 優先級高的在前
                }
                // 同優先級按置信度排序
                return Float.compare(r2.getConfidence(), r1.getConfidence());
            }
        });
        return detections;
    }
    
    private int getClassId(String label) {
        for (Map.Entry<Integer, String> entry : CLASS_NAMES.entrySet()) {
            if (entry.getValue().equals(label)) {
                return entry.getKey();
            }
        }
        return -1;
    }
    
    private void prepareInput(Bitmap bitmap) {
        inputBuffer.rewind();
        
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        
        int[] pixels = new int[inputSize * inputSize];
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            
            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }
    }
    
    private List<DetectionResult> parseOutputs(int imgWidth, int imgHeight) {
        if (outputBuffer[0].length > 0 && outputBuffer[0][0].length == 4) {
            return parseSimpleFormat(imgWidth, imgHeight);
        } else {
            return parseStandardFormat(imgWidth, imgHeight);
        }
    }
    
    private List<DetectionResult> parseStandardFormat(int imgWidth, int imgHeight) {
        List<DetectionResult> allDetections = new ArrayList<>();
        
        // 目標類別（台灣道路常見）
        int[] targetClasses = {0, 1, 2, 3, 5, 7}; // person, bicycle, car, motorcycle, bus, truck
        
        for (int i = 0; i < numBoxes && i < outputBuffer[0][0].length; i++) {
            float xCenter = outputBuffer[0][0][i];
            float yCenter = outputBuffer[0][1][i];
            float width = outputBuffer[0][2][i];
            float height = outputBuffer[0][3][i];
            
            // 獲取最高分的類別
            float maxConfidence = 0;
            int bestClass = -1;
            
            for (int c = 0; c < numClasses && (4 + c) < outputBuffer[0].length; c++) {
                float confidence = outputBuffer[0][4 + c][i];
                if (confidence > maxConfidence) {
                    maxConfidence = confidence;
                    bestClass = c;
                }
            }
            
            // 只保留目標類別
            boolean isTarget = false;
            for (int target : targetClasses) {
                if (bestClass == target) {
                    isTarget = true;
                    break;
                }
            }
            if (!isTarget) continue;
            if (maxConfidence < CONFIDENCE_THRESHOLD) continue;
            
            // 轉換為像素座標
            float x1 = (xCenter - width / 2) * imgWidth;
            float y1 = (yCenter - height / 2) * imgHeight;
            float x2 = (xCenter + width / 2) * imgWidth;
            float y2 = (yCenter + height / 2) * imgHeight;
            
            // 邊界檢查
            x1 = Math.max(0, Math.min(x1, imgWidth));
            y1 = Math.max(0, Math.min(y1, imgHeight));
            x2 = Math.max(0, Math.min(x2, imgWidth));
            y2 = Math.max(0, Math.min(y2, imgHeight));
            
            if (x2 <= x1 || y2 <= y1) continue;
            
            String label = CLASS_NAMES.getOrDefault(bestClass, "object");
            RectF bbox = new RectF(x1, y1, x2, y2);
            allDetections.add(new DetectionResult(label, maxConfidence, bbox));
        }
        
        return applyNMS(allDetections);
    }
    
    private List<DetectionResult> parseSimpleFormat(int imgWidth, int imgHeight) {
        List<DetectionResult> rawDetections = new ArrayList<>();
        
        for (int i = 0; i < numBoxes && i < outputBuffer[0].length; i++) {
            float x1 = outputBuffer[0][i][0];
            float y1 = outputBuffer[0][i][1];
            float x2 = outputBuffer[0][i][2];
            float y2 = outputBuffer[0][i][3];
            
            if (x2 <= x1 || y2 <= y1) continue;
            if (x1 < 0 || y1 < 0 || x2 > 1 || y2 > 1) continue;
            
            float width = x2 - x1;
            float height = y2 - y1;
            float area = width * height;
            float aspectRatio = height / (width + 1e-6f);
            
            // 基礎過濾
            if (area < 0.01f || area > 0.8f) continue;
            if (width < 0.02f || height < 0.03f) continue;
            
            // 簡化：預設為 person
            float confidence = estimateConfidence(area, aspectRatio);
            if (confidence < CONFIDENCE_THRESHOLD) continue;
            
            float pixelX1 = x1 * imgWidth;
            float pixelY1 = y1 * imgHeight;
            float pixelX2 = x2 * imgWidth;
            float pixelY2 = y2 * imgHeight;
            
            RectF bbox = new RectF(pixelX1, pixelY1, pixelX2, pixelY2);
            rawDetections.add(new DetectionResult("person", confidence, bbox));
        }
        
        return applyNMS(rawDetections);
    }
    
    private float estimateConfidence(float area, float aspectRatio) {
        float idealAspect = 1.8f;
        float aspectScore = 1.0f - Math.min(Math.abs(aspectRatio - idealAspect) / 2.0f, 0.3f);
        
        float areaScore;
        if (area > 0.015f && area < 0.5f) {
            areaScore = 0.9f;
        } else {
            areaScore = 0.6f;
        }
        
        return Math.min(0.95f, Math.max(0.30f, aspectScore * areaScore));
    }
    
    private List<DetectionResult> applyNMS(List<DetectionResult> detections) {
        if (detections.isEmpty()) return detections;
        
        Collections.sort(detections, (r1, r2) -> 
            Float.compare(r2.getConfidence(), r1.getConfidence()));
        
        List<DetectionResult> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];
        
        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            
            DetectionResult current = detections.get(i);
            result.add(current);
            
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                if (!current.getLabel().equals(detections.get(j).getLabel())) continue;
                
                float iou = calculateIoU(current.getLocation(), detections.get(j).getLocation());
                if (iou > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        
        return result;
    }
    
    private float calculateIoU(RectF box1, RectF box2) {
        float intersectionLeft = Math.max(box1.left, box2.left);
        float intersectionTop = Math.max(box1.top, box2.top);
        float intersectionRight = Math.min(box1.right, box2.right);
        float intersectionBottom = Math.min(box1.bottom, box2.bottom);
        
        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0;
        }
        
        float intersectionArea = (intersectionRight - intersectionLeft) *
                                  (intersectionBottom - intersectionTop);
        float box1Area = (box1.right - box1.left) * (box1.bottom - box1.top);
        float box2Area = (box2.right - box2.left) * (box2.bottom - box2.top);
        
        return intersectionArea / (box1Area + box2Area - intersectionArea);
    }
    
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
    
    public boolean isModelLoaded() {
        return modelLoaded;
    }
    
    public SceneMode getCurrentSceneMode() {
        return currentSceneMode;
    }
    
    public void setConfidenceThreshold(float threshold) {
        this.CONFIDENCE_THRESHOLD = threshold;
        Log.d(TAG, "置信度閾值設為: " + threshold);
    }
    
    public String[] getSupportedClasses() {
        return new String[] {"person", "bicycle", "car", "motorcycle", "bus", "truck"};
    }
}