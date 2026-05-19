package com.sharn.pedestriansafety;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物體偵測器 - 使用正確的 TFLite 模型格式
 */
public class ObjectDetectorWrapper {
    
    private static final String TAG = "CarSafetyDetector";
    private static final String MODEL_FILE = "detection_model.tflite";  // 使用正確的模型
    private static final int INPUT_SIZE = 300;  // MobileNet SSD 標準輸入尺寸
    
    // 設定 - 調低以提高偵測率
    private static final float CONFIDENCE_THRESHOLD = 0.30f;  // 30% 閾值
    
    private Interpreter interpreter;
    private boolean modelLoaded = false;
    
    // 檢測結果數量
    private static final int NUM_DETECTIONS = 10;
    
    // 輸入緩衝區
    private float[][][][] inputBuffer;
    
    // 輸出緩衝區 - 標準 SSD MobileNet 輸出格式
    private float[][][] outputLocations;  // [1, 10, 4] - 邊界框
    private float[][] outputClasses;     // [1, 10] - 類別分數
    private float[] numDetections;       // [1] - 檢測數量
    
    public ObjectDetectorWrapper(Context context) {
        try {
            Log.d(TAG, "=== 初始化偵測器 ===");
            
            // 載入模型
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
            interpreter = new Interpreter(buffer, options);
            
            // 檢查模型輸入輸出形狀
            Log.d(TAG, "輸入 Tensor 數量: " + interpreter.getInputTensorCount());
            Log.d(TAG, "輸出 Tensor 數量: " + interpreter.getOutputTensorCount());
            
            for (int i = 0; i < interpreter.getInputTensorCount(); i++) {
                int[] shape = interpreter.getInputTensor(i).shape();
                Log.d(TAG, "輸入[" + i + "] 形狀: " + shapeToString(shape));
            }
            
            for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
                int[] shape = interpreter.getOutputTensor(i).shape();
                Log.d(TAG, "輸出[" + i + "] 形狀: " + shapeToString(shape));
            }
            
            // 初始化輸入緩衝區
            inputBuffer = new float[1][INPUT_SIZE][INPUT_SIZE][3];
            
            // 初始化輸出緩衝區
            outputLocations = new float[1][NUM_DETECTIONS][4];
            outputClasses = new float[1][NUM_DETECTIONS];
            numDetections = new float[1];
            
            modelLoaded = true;
            Log.d(TAG, "✅ 模型載入成功!");
            
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
     * 執行偵測
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (!modelLoaded || interpreter == null) {
            Log.w(TAG, "模型未載入");
            return Collections.emptyList();
        }
        
        try {
            // 準備輸入
            prepareInput(bitmap);
            
            // 執行推論 - 使用 runForMultipleInputsOutputs
            Map<Integer, Object> outputMap = new HashMap<>();
            outputMap.put(0, outputLocations);
            outputMap.put(1, outputClasses);
            outputMap.put(2, numDetections);
            
            Object[] inputs = new Object[]{inputBuffer};
            interpreter.runForMultipleInputsOutputs(inputs, outputMap);
            
            // 解析結果
            return parseOutputs(bitmap.getWidth(), bitmap.getHeight());
            
        } catch (Exception e) {
            Log.e(TAG, "偵測錯誤: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
    
    /**
     * 準備輸入數據
     */
    private void prepareInput(Bitmap bitmap) {
        // 縮放到 300x300
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        
        // 轉換為 [0, 1] 範圍的 float，並標準化 (MobileNet 使用 [-1, 1])
        for (int i = 0; i < INPUT_SIZE; i++) {
            for (int j = 0; j < INPUT_SIZE; j++) {
                int pixel = pixels[i * INPUT_SIZE + j];
                inputBuffer[0][i][j][0] = (((pixel >> 16) & 0xFF) / 255.0f - 0.5f) * 2.0f;  // R
                inputBuffer[0][i][j][1] = (((pixel >> 8) & 0xFF) / 255.0f - 0.5f) * 2.0f;   // G
                inputBuffer[0][i][j][2] = (((pixel) & 0xFF) / 255.0f - 0.5f) * 2.0f;          // B
            }
        }
    }
    
    /**
     * 解析模型輸出
     */
    private List<DetectionResult> parseOutputs(int imgWidth, int imgHeight) {
        List<DetectionResult> results = new ArrayList<>();
        
        int numDetect = (int) numDetections[0];
        numDetect = Math.min(numDetect, NUM_DETECTIONS);
        
        Log.d(TAG, "偵測到 " + numDetect + " 個候選目標");
        
        for (int i = 0; i < numDetect; i++) {
            float confidence = outputClasses[0][i];
            
            Log.d(TAG, "候選[" + i + "] 信心度=" + String.format("%.3f", confidence));
            
            if (confidence < CONFIDENCE_THRESHOLD) {
                continue;
            }
            
            // 獲取邊界框 (y_min, x_min, y_max, x_max)
            float yMin = outputLocations[0][i][0];
            float xMin = outputLocations[0][i][1];
            float yMax = outputLocations[0][i][2];
            float xMax = outputLocations[0][i][3];
            
            // 轉換為像素座標
            float left = xMin * imgWidth;
            float top = yMin * imgHeight;
            float right = xMax * imgWidth;
            float bottom = yMax * imgHeight;
            
            // 邊界檢查
            left = Math.max(0, Math.min(left, imgWidth));
            top = Math.max(0, Math.min(top, imgHeight));
            right = Math.max(0, Math.min(right, imgWidth));
            bottom = Math.max(0, Math.min(bottom, imgHeight));
            
            if (right <= left || bottom <= top) continue;
            
            String label = "person"; // 簡化為行人
            RectF bbox = new RectF(left, top, right, bottom);
            results.add(new DetectionResult(label, confidence, bbox));
            
            Log.d(TAG, "✅ 有效偵測: " + label + " (" + String.format("%.0f%%", confidence * 100) + ")");
        }
        
        return results;
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
    
    /**
     * 設定信心度閾值
     */
    public void setConfidenceThreshold(float threshold) {
        // 此方法保留供 UI 呼叫，但已經使用內部閾值
        Log.d(TAG, "信心度閾值設為: " + threshold);
    }
}