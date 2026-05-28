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
 * v2.6.0 修復版物體偵測器
 * 
 * 修正項目：
 * 1. ✅ 修正 COCO 類別索引 - 使用模型 91 個 score slot 的 COCO id 索引
 * 2. ✅ 依模型實際輸出形狀配置緩衝區，避免 TFLite 輸出 shape mismatch
 * 3. ✅ 修正紅綠燈標籤比對使用英文 "traffic light"
 * 4. ✅ 只將交通相關類別轉成 app 內部使用的 80 類 COCO 英文標籤
 */ 
public class ObjectDetectorWrapper {
    
    private static final String TAG = "TrafficSafetyDetector";
    private static final String MODEL_FILE = "yolov8n.tflite";
    
    // 模型參數 - 根據實際模型結構
    private static final int INPUT_SIZE = 256;  // 模型輸入 256x256
    private static final int BOX_COORDINATES = 4;
    
    // 篩選參數 - 嚴格過濾減少誤報
    private static final float CONFIDENCE_THRESHOLD = 0.40f;  // 提高閾值
    private static final float IOU_THRESHOLD = 0.40f;
    private static final int MAX_DETECTIONS = 50;  // 最大偵測數
    
    // 只保留交通相關類別的 score 索引。
    // 此模型輸出 91 個 score slot，索引對應 TensorFlow Object Detection API COCO id。
    // 0 是背景/未使用，person 從 1 開始，部分 COCO id 會跳號。
    private static final int[] VALID_CLASS_INDICES = {
        1,   // person
        2,   // bicycle
        3,   // car
        4,   // motorcycle
        6,   // bus
        7,   // train
        8,   // truck
        10,  // traffic light
        13   // stop sign
    };

    private static final Map<Integer, String> SCORE_CLASS_LABELS = new HashMap<Integer, String>() {{
        put(1, "person");
        put(2, "bicycle");
        put(3, "car");
        put(4, "motorcycle");
        put(6, "bus");
        put(7, "train");
        put(8, "truck");
        put(10, "traffic light");
        put(13, "stop sign");
    }};
    
    // 中文標籤對應（直接使用英文 label 轉中文）
    private static final Map<String, String> CHINESE_LABELS = new HashMap<String, String>() {{
        put("person", "行人");
        put("bicycle", "腳踏車");
        put("car", "汽車");
        put("motorcycle", "機車");
        put("bus", "公車");
        put("train", "火車");
        put("truck", "卡車");
        put("traffic light", "紅綠燈");
        put("stop sign", "停止標誌");
    }};
    
    // 優先級（交通場景）
    private static final Map<String, Integer> CLASS_PRIORITY = new HashMap<String, Integer>() {{
        put("person", 1);
        put("traffic light", 2);
        put("motorcycle", 3);
        put("car", 4);
        put("bus", 5);
        put("truck", 6);
        put("bicycle", 7);
        put("stop sign", 8);
        put("train", 9);
    }};
    
    private Interpreter interpreter;
    private boolean modelLoaded = false;
    private int numBoxes = 0;
    private int numScoreClasses = 0;
    
    // 輸入緩衝區
    private ByteBuffer inputBuffer;
    
    // 輸出緩衝區 - 多輸出
    private float[][][] boxesOutput;   // [1, numBoxes, 4]
    private float[][][] scoresOutput;  // [1, numBoxes, numScoreClasses]
    
    public ObjectDetectorWrapper(Context context) {
        try {
            Log.d(TAG, "=== v2.6 初始化偵測器（穩定推論版）===");
            
            // 載入模型
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);

            try (AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
                 FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
                FileChannel channel = fis.getChannel();
                MappedByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(),
                    fd.getDeclaredLength()
                );
                interpreter = new Interpreter(buffer, options);
            }
            
            // 驗證模型形狀
            verifyModelShape();
            
            // 初始化輸入緩衝區
            int inputBufferSize = INPUT_SIZE * INPUT_SIZE * 3 * 4;  // float32 RGB
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize);
            inputBuffer.order(ByteOrder.nativeOrder());
            
            // 初始化輸出緩衝區 - 多輸出，必須符合模型實際 shape
            boxesOutput = new float[1][numBoxes][BOX_COORDINATES];   // [ymin, xmin, ymax, xmax]
            scoresOutput = new float[1][numBoxes][numScoreClasses];
            
            modelLoaded = true;
            Log.d(TAG, "✅ v2.6 模型載入成功!");
            Log.d(TAG, "   輸入: [1, " + INPUT_SIZE + ", " + INPUT_SIZE + ", 3]");
            Log.d(TAG, "   輸出 boxes: [1, " + numBoxes + ", 4]");
            Log.d(TAG, "   輸出 scores: [1, " + numBoxes + ", " + numScoreClasses + "]");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 模型載入失敗: " + e.getMessage());
            e.printStackTrace();
            modelLoaded = false;
        }
    }
    
    /**
     * 驗證模型輸入/輸出形狀
     */
    private void verifyModelShape() {
        // 檢查輸入
        int[] inputShape = interpreter.getInputTensor(0).shape();
        Log.d(TAG, "模型輸入形狀: " + shapeToString(inputShape));
        if (inputShape.length != 4 || inputShape[1] != INPUT_SIZE
            || inputShape[2] != INPUT_SIZE || inputShape[3] != 3) {
            throw new IllegalStateException("不支援的模型輸入形狀: " + shapeToString(inputShape));
        }
        
        // 檢查輸出數量
        int outputCount = interpreter.getOutputTensorCount();
        Log.d(TAG, "模型輸出數量: " + outputCount);
        if (outputCount < 2) {
            throw new IllegalStateException("模型至少需要 boxes 和 scores 兩個輸出");
        }
        
        for (int i = 0; i < outputCount; i++) {
            int[] outputShape = interpreter.getOutputTensor(i).shape();
            Log.d(TAG, "輸出 " + i + " 形狀: " + shapeToString(outputShape));
        }

        int[] boxesShape = interpreter.getOutputTensor(0).shape();
        int[] scoresShape = interpreter.getOutputTensor(1).shape();
        if (boxesShape.length != 3 || scoresShape.length != 3
            || boxesShape[0] != 1 || scoresShape[0] != 1
            || boxesShape[2] != BOX_COORDINATES
            || boxesShape[1] != scoresShape[1]) {
            throw new IllegalStateException("不支援的模型輸出形狀 boxes="
                + shapeToString(boxesShape) + ", scores=" + shapeToString(scoresShape));
        }

        numBoxes = boxesShape[1];
        numScoreClasses = scoresShape[2];
        for (int classIdx : VALID_CLASS_INDICES) {
            if (classIdx >= numScoreClasses) {
                throw new IllegalStateException("模型 score 類別數不足: " + numScoreClasses);
            }
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
     * 主要偵測函數
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (!modelLoaded || interpreter == null) {
            return Collections.emptyList();
        }
        
        try {
            // 預處理圖像
            preprocess(bitmap);
            
            // 執行推論 - 多輸出
            runInference();
            
            // 解析輸出
            List<DetectionResult> results = parseOutputs(bitmap.getWidth(), bitmap.getHeight());
            
            // 應用 NMS
            results = applyNMS(results);
            
            // 排序並限制數量
            results = sortAndLimit(results);
            
            Log.d(TAG, "偵測到 " + results.size() + " 個目標");
            return results;
            
        } catch (Exception e) {
            Log.e(TAG, "偵測錯誤: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
    
    /**
     * 預處理圖像至 256x256
     */
    private void preprocess(Bitmap bitmap) {
        inputBuffer.rewind();
        
        // 縮放至模型輸入大小
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        if (resized != bitmap) {
            resized.recycle();
        }
        
        // 轉換為 float32 RGB，歸一化至 [0, 1]
        for (int pixel : pixels) {
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            
            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }
    }
    
    /**
     * 執行推論 - 多輸出
     */
    private void runInference() {
        // 使用 runForMultipleInputsOutputs 處理多輸出
        Object[] inputs = new Object[]{inputBuffer};
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, boxesOutput);
        outputs.put(1, scoresOutput);
        
        interpreter.runForMultipleInputsOutputs(inputs, outputs);
    }
    
    /**
     * 解析輸出 - 正確處理 scores 和 boxes
     */
    private List<DetectionResult> parseOutputs(int imgWidth, int imgHeight) {
        List<DetectionResult> results = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            // 取得分數陣列
            float[] scores = scoresOutput[0][i];
            
            // 找到最大分數的類別
            float maxScore = 0;
            int bestClassIdx = -1;
            
            for (int classIdx : VALID_CLASS_INDICES) {
                if (classIdx < numScoreClasses && scores[classIdx] > maxScore) {
                    maxScore = scores[classIdx];
                    bestClassIdx = classIdx;
                }
            }
            
            // 如果沒有找到有效類別或分數太低，跳過
            if (bestClassIdx == -1 || maxScore < CONFIDENCE_THRESHOLD) {
                continue;
            }
            
            // 取得框座標 - 格式是 [ymin, xmin, ymax, xmax]
            float ymin = boxesOutput[0][i][0];  // 0
            float xmin = boxesOutput[0][i][1];  // 1
            float ymax = boxesOutput[0][i][2];  // 2
            float xmax = boxesOutput[0][i][3];  // 3
            
            // 轉換到原始圖像尺寸
            float y1 = ymin * imgHeight;
            float x1 = xmin * imgWidth;
            float y2 = ymax * imgHeight;
            float x2 = xmax * imgWidth;
            
            // 邊界檢查
            x1 = Math.max(0, Math.min(x1, imgWidth));
            y1 = Math.max(0, Math.min(y1, imgHeight));
            x2 = Math.max(0, Math.min(x2, imgWidth));
            y2 = Math.max(0, Math.min(y2, imgHeight));
            
            // 跳過無效框
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            
            // 檢查框的大小（過濾太小或太大的）
            float boxWidth = x2 - x1;
            float boxHeight = y2 - y1;
            float boxArea = boxWidth * boxHeight;
            float imageArea = imgWidth * imgHeight;
            float areaRatio = boxArea / imageArea;
            
            // 過濾極小或極大的框
            if (areaRatio < 0.001f || areaRatio > 0.5f) {
                continue;
            }
            
            String label = SCORE_CLASS_LABELS.get(bestClassIdx);
            if (label == null) {
                continue;  // 跳過無效類別索引
            }
            
            // ⚠️ 修正：內部保留英文 label，只在顯示時轉中文
            // 這樣後面邏輯（priority, tracker）可以用英文判斷
            RectF bbox = new RectF(x1, y1, x2, y2);
            results.add(new DetectionResult(label, maxScore, bbox));  // 用英文 label
            
            Log.v(TAG, String.format("偵測: %s (%.2f) [%.1f, %.1f, %.1f, %.1f]",
                label, maxScore, x1, y1, x2, y2));
        }
        
        return results;
    }
    
    /**
     * 應用非極大值抑制 (NMS)
     */
    private List<DetectionResult> applyNMS(List<DetectionResult> detections) {
        if (detections.isEmpty()) {
            return detections;
        }
        
        // 按信心度排序
        Collections.sort(detections, (a, b) -> 
            Float.compare(b.getConfidence(), a.getConfidence()));
        
        List<DetectionResult> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];
        
        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            
            DetectionResult current = detections.get(i);
            result.add(current);
            
            // 抑制與當前框重疊度高的其他框
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                
                DetectionResult other = detections.get(j);
                
                // 只對相同類別應用 NMS（現在都是英文標籤）
                if (!current.getLabel().equals(other.getLabel())) {
                    continue;
                }
                
                float iou = calculateIoU(current.getLocation(), other.getLocation());
                if (iou > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        
        return result;
    }
    
    /**
     * 排序並限制數量
     */
    private List<DetectionResult> sortAndLimit(List<DetectionResult> detections) {
        // 按優先級和信心度排序
        Collections.sort(detections, (r1, r2) -> {
            int p1 = CLASS_PRIORITY.getOrDefault(r1.getLabel(), 99);
            int p2 = CLASS_PRIORITY.getOrDefault(r2.getLabel(), 99);
            
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
            return Float.compare(r2.getConfidence(), r1.getConfidence());
        });
        
        // 限制數量
        if (detections.size() > MAX_DETECTIONS) {
            return detections.subList(0, MAX_DETECTIONS);
        }
        return detections;
    }
    
    /**
     * 計算 IoU
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
    
    /**
     * 釋放資源
     */
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        modelLoaded = false;
    }
    
    public boolean isModelLoaded() {
        return modelLoaded;
    }
    
    /**
     * 取得支援的類別列表
     */
    public String[] getSupportedClasses() {
        return new String[]{"行人", "腳踏車", "汽車", "機車", "公車", "卡車", "紅綠燈", "停止標誌", "火車"};
    }
}
