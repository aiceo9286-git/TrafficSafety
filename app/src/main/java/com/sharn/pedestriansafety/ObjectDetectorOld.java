package com.sharn.pedestriansafety;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;
import org.tensorflow.lite.task.vision.detector.ObjectDetectorOptions;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ObjectDetectorOld {

    private static final int INPUT_SIZE = 300;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    
    private Interpreter interpreter;
    private List<String> labels;
    private ImageProcessor imageProcessor;
    
    // 使用 TFLite Task Library 的方式
    private ObjectDetector detector;
    
    public ObjectDetectorOld(Context context) {
        try {
            // 建立 ObjectDetector 選項
            ObjectDetectorOptions options = ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                .setMaxResults(10)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build();
            
            // 載入預設模型或者使用內建方式
            // 這裡使用 COCO SSD MobileNet
            detector = ObjectDetector.createFromFileAndOptions(
                context, 
                "ssd_mobilenet_v1_1_metadata_1.tflite", 
                options
            );
            
        } catch (IOException e) {
            e.printStackTrace();
            // 如果模型載入失敗，使用簡易備援方案
            initFallbackDetector(context);
        }
        
        imageProcessor = new ImageProcessor.Builder()
            .add(new ResizeWithCropOrPadOp(INPUT_SIZE, INPUT_SIZE))
            .add(new ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .add(new NormalizeOp(0f, 255f))
            .build();
    }
    
    private void initFallbackDetector(Context context) {
        // 備援：使用簡易方法
        // 實際開發時應該包含模型檔案
    }
    
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (detector == null) {
            return Collections.emptyList();
        }
        
        // 轉換影像
        TensorImage inputImage = TensorImage.fromBitmap(bitmap);
        inputImage = imageProcessor.process(inputImage);
        
        // 執行偵測
        List<Detection> results = detector.detect(inputImage);
        
        // 轉換結果
        List<DetectionResult> output = new ArrayList<>();
        for (Detection detection : results) {
            String label = detection.getCategories().get(0).getLabel();
            float score = detection.getCategories().get(0).getScore();
            RectF location = detection.getBoundingBox();
            
            output.add(new DetectionResult(label, score, location));
        }
        
        return output;
    }
    
    public void setConfidenceThreshold(float threshold) {
        // 動態調整閥值
    }
    
    public void close() {
        if (detector != null) {
            detector.close();
        }
    }
    
    // 偵測結果類別
    public static class DetectionResult {
        private final String label;
        private final float confidence;
        private final RectF location;
        
        public DetectionResult(String label, float confidence, RectF location) {
            this.label = label;
            this.confidence = confidence;
            this.location = location;
        }
        
        public String getLabel() { return label; }
        public float getConfidence() { return confidence; }
        public RectF getLocation() { return location; }
        
        public boolean isPedestrian() {
            return label.equalsIgnoreCase("person") || 
                   label.equalsIgnoreCase("pedestrian");
        }
        
        public boolean isTrafficLight() {
            return label.toLowerCase().contains("traffic light") ||
                   label.toLowerCase().contains("traffic_light");
        }
        
        public String getAlertMessage() {
            if (isPedestrian()) {
                return "注意！前方有行人";
            } else if (isTrafficLight()) {
                return "注意交通號誌";
            }
            return "注意" + label;
        }
    }
}
