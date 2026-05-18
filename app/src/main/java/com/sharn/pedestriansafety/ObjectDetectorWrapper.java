package com.sharn.pedestriansafety;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;
import org.tensorflow.lite.task.vision.detector.ObjectDetectorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TensorFlow Lite 物體偵測器
 * 使用 TFLite Task Vision Library
 */
public class ObjectDetectorWrapper {

    private static final int INPUT_SIZE = 320;
    private static final int NUM_THREADS = 4;
    private static final int MAX_RESULTS = 10;
    
    private ObjectDetector detector;
    private ImageProcessor imageProcessor;
    private float confidenceThreshold = 0.5f;
    private final Context context;
    
    public ObjectDetectorWrapper(Context context) {
        this.context = context;
        initDetector();
        initImageProcessor();
    }
    
    private void initDetector() {
        try {
            // 建立偵測器選項
            ObjectDetectorOptions options = ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setNumThreads(NUM_THREADS)
                        .build()
                )
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(confidenceThreshold)
                .build();
            
            // 嘗試載入模型
            // 注意：實際執行時需要有模型檔案
            detector = ObjectDetector.createFromFileAndOptions(
                context,
                "mobilenetv1.tflite",  // 預設模型名稱
                options
            );
            
        } catch (Exception e) {
            e.printStackTrace();
            // 模型載入失敗，使用模擬模式
            detector = null;
        }
    }
    
    private void initImageProcessor() {
        imageProcessor = new ImageProcessor.Builder()
            .add(new ResizeWithCropOrPadOp(INPUT_SIZE, INPUT_SIZE))
            .add(new ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .build();
    }
    
    /**
     * 執行物體偵測
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (detector == null) {
            return generateMockResults(); // 模擬結果（開發測試用）
        }
        
        try {
            // 轉換影像為模型輸入格式
            TensorImage inputImage = TensorImage.fromBitmap(bitmap);
            inputImage = imageProcessor.process(inputImage);
            
            // 執行偵測
            List<Detection> detections = detector.detect(inputImage);
            
            // 轉換結果
            List<DetectionResult> results = new ArrayList<>();
            for (Detection detection : detections) {
                if (detection.getCategories().isEmpty()) continue;
                
                String label = detection.getCategories().get(0).getLabel();
                float score = detection.getCategories().get(0).getScore();
                RectF bbox = detection.getBoundingBox();
                
                // 只保留行人相關和號誌
                if (isTargetClass(label)) {
                    results.add(new DetectionResult(label, score, bbox));
                }
            }
            
            return results;
            
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
    
    /**
     * 判斷是否為目標類別（行人或號誌）
     */
    private boolean isTargetClass(String label) {
        String lower = label.toLowerCase();
        // COCO 數據集的標籤
        return lower.contains("person") || 
               lower.contains("pedestrian") ||
               lower.contains("traffic light");
    }
    
    /**
     * 設定靈認度閥值
     */
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = threshold;
        if (detector != null) {
            // 重新初始化以套用新閥值
            detector.close();
            initDetector();
        }
    }
    
    /**
     * 產生模擬偵測結果（開發測試用）
     * 實際執行時應該移除或改為備援方案
     */
    private List<DetectionResult> generateMockResults() {
        List<DetectionResult> mockResults = new ArrayList<>();
        
        // 模擬一個行人在畫面中央
        RectF personBox = new RectF(200, 150, 400, 600);
        mockResults.add(new DetectionResult("person", 0.85f, personBox));
        
        return mockResults;
    }
    
    /**
     * 釋放資源
     */
    public void close() {
        if (detector != null) {
            detector.close();
            detector = null;
        }
    }
}
