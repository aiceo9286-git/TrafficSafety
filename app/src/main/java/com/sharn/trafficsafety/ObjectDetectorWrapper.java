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
import java.util.List;

/**
 * 改進版物體偵測器
 * 改善項目：
 * 1. ROI 過濾（只檢測畫面下方 60%，避免建築誤判）
 * 2. 改進的 NMS 參數
 * 3. 整合追蹤器
 */
public class ObjectDetectorWrapper {
    
    private static final String TAG = "CarSafetyDetector";
    private static final String MODEL_FILE = "yolov8n.tflite";
    
    // 動態偵測的模型參數
    private int inputSize = 320;
    private int numClasses = 80;
    private int numBoxes = 8400;
    
    // 篩選參數 - 提高閾值減少誤報
    private float CONFIDENCE_THRESHOLD = 0.35f;  // 提高從 0.25
    private static final float IOU_THRESHOLD = 0.4f;      // 從 0.45 降低
    
    // ROI 參數 - 只檢查畫面下方（路面區域）
    private static final float ROI_TOP_RATIO = 0.25f;     // 忽略上方 25%
    private static final float ROI_BOTTOM_RATIO = 0.95f;  // 檢查到下方 95%
    private static final float ROI_LEFT_RATIO = 0.05f;    // 忽略左側 5%
    private static final float ROI_RIGHT_RATIO = 0.95f;   // 忽略右側 5%
    
    private Interpreter interpreter;
    private boolean modelLoaded = false;
    
    private ByteBuffer inputBuffer;
    private float[][][] outputBuffer;
    
    public ObjectDetectorWrapper(Context context) {
        try {
            Log.d(TAG, "=== 初始化改進版偵測器 ===");
            
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
            
            // 自動偵測模型形狀
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            
            Log.d(TAG, "輸入形狀: " + shapeToString(inputShape));
            Log.d(TAG, "輸出形狀: " + shapeToString(outputShape));
            
            if (inputShape.length >= 3) {
                inputSize = inputShape[1];
            }
            
            if (outputShape.length >= 3) {
                int dim1 = outputShape[1];
                int dim2 = outputShape[2];
                
                if (dim2 == 4) {
                    numBoxes = dim1;
                    numClasses = 80;
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
            Log.d(TAG, "✅ 模型載入成功! ROI已啟用");
            
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
     * 改進版：加入 ROI 過濾
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (!modelLoaded || interpreter == null) {
            return Collections.emptyList();
        }
        
        try {
            prepareInput(bitmap);
            interpreter.run(inputBuffer, outputBuffer);
            
            List<DetectionResult> results = parseOutputs(bitmap.getWidth(), bitmap.getHeight());
            
            // ROI 過濾 - 只保留畫面下方的目標
            results = filterByROI(results, bitmap.getWidth(), bitmap.getHeight());
            
            return results;
            
        } catch (Exception e) {
            Log.e(TAG, "偵測錯誤: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * ROI 過濾 - 只保留感興趣區域內的檢測
     */
    private List<DetectionResult> filterByROI(List<DetectionResult> detections, int imgWidth, int imgHeight) {
        List<DetectionResult> filtered = new ArrayList<>();
        
        float roiTop = imgHeight * ROI_TOP_RATIO;
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
                
                // 額外檢查：目標不能大部分在天空/背景區域
                float topRatio = loc.top / imgHeight;
                if (topRatio < 0.15f && det.getConfidence() < 0.6f) {
                    // 太靠上且置信度不足，可能是建築物
                    Log.d(TAG, "過濾可疑目標（位置過高）: " + det.getLabel());
                    continue;
                }
                
                filtered.add(det);
            } else {
                Log.d(TAG, "過濾 ROI 外目標: " + det.getLabel());
            }
        }
        
        return filtered;
    }
    
    private void prepareInput(Bitmap bitmap) {
        inputBuffer.rewind();
        
        // 移除中央放大，直接縮放到模型輸入尺寸
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
    
    private List<DetectionResult> parseSimpleFormat(int imgWidth, int imgHeight) {
        List<DetectionResult> rawDetections = new ArrayList<>();
        
        // 調整過濾參數
        final float MIN_AREA = 0.02f;       // 降低最小面積
        final float MAX_AREA = 0.7f;        // 提高最大面積
        final float MIN_ASPECT = 0.8f;    // 放寬長寬比
        final float MAX_ASPECT = 4.0f;
        final float MIN_CONFIDENCE = 0.35f;
        
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
            
            if (area < MIN_AREA || area > MAX_AREA) continue;
            if (aspectRatio < MIN_ASPECT || aspectRatio > MAX_ASPECT) continue;
            if (width < 0.03f || height < 0.05f) continue;
            
            float confidence = estimateConfidence(area, aspectRatio);
            if (confidence < MIN_CONFIDENCE) continue;
            
            // 修正座標轉換
            float pixelX1 = x1 * imgWidth;
            float pixelY1 = y1 * imgHeight;
            float pixelX2 = x2 * imgWidth;
            float pixelY2 = y2 * imgHeight;
            
            RectF bbox = new RectF(pixelX1, pixelY1, pixelX2, pixelY2);
            rawDetections.add(new DetectionResult("person", confidence, bbox));
        }
        
        // NMS
        return applyNMS(rawDetections, IOU_THRESHOLD);
    }
    
    private List<DetectionResult> parseStandardFormat(int imgWidth, int imgHeight) {
        List<DetectionResult> allDetections = new ArrayList<>();
        
        int[] targetClasses = {0, 1, 2, 3, 5, 7}; // person, bicycle, car, motorcycle, bus, truck
        
        for (int i = 0; i < numBoxes; i++) {
            float xCenter = outputBuffer[0][0][i];
            float yCenter = outputBuffer[0][1][i];
            float width = outputBuffer[0][2][i];
            float height = outputBuffer[0][3][i];
            
            float maxConfidence = 0;
            int bestClass = -1;
            
            for (int c = 0; c < numClasses && (4 + c) < outputBuffer[0].length; c++) {
                float confidence = outputBuffer[0][4 + c][i];
                if (confidence > maxConfidence) {
                    maxConfidence = confidence;
                    bestClass = c;
                }
            }
            
            boolean isTarget = false;
            for (int target : targetClasses) {
                if (bestClass == target) {
                    isTarget = true;
                    break;
                }
            }
            if (!isTarget) continue;
            if (maxConfidence < CONFIDENCE_THRESHOLD) continue;
            
            float x1 = (xCenter - width / 2) * imgWidth;
            float y1 = (yCenter - height / 2) * imgHeight;
            float x2 = (xCenter + width / 2) * imgWidth;
            float y2 = (yCenter + height / 2) * imgHeight;
            
            x1 = Math.max(0, Math.min(x1, imgWidth));
            y1 = Math.max(0, Math.min(y1, imgHeight));
            x2 = Math.max(0, Math.min(x2, imgWidth));
            y2 = Math.max(0, Math.min(y2, imgHeight));
            
            if (x2 <= x1 || y2 <= y1) continue;
            
            String label = getLabelForClass(bestClass);
            RectF bbox = new RectF(x1, y1, x2, y2);
            allDetections.add(new DetectionResult(label, maxConfidence, bbox));
        }
        
        return applyNMS(allDetections, IOU_THRESHOLD);
    }
    
    private String getLabelForClass(int classId) {
        String[] labels = {"person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck"};
        if (classId >= 0 && classId < labels.length) {
            return labels[classId];
        }
        return "object";
    }
    
    private float estimateConfidence(float area, float aspectRatio) {
        float idealAspect = 1.8f;
        float aspectScore = 1.0f - Math.min(Math.abs(aspectRatio - idealAspect) / 2.0f, 0.3f);
        
        float areaScore;
        if (area > 0.015f && area < 0.5f) {
            areaScore = 0.9f;
        } else if (area >= 0.5f && area < 0.7f) {
            areaScore = 0.7f;
        } else {
            areaScore = 0.5f;
        }
        
        return Math.min(0.95f, Math.max(0.35f, aspectScore * areaScore));
    }
    
    private List<DetectionResult> applyNMS(List<DetectionResult> detections, float iouThreshold) {
        if (detections.isEmpty()) return detections;
        
        Collections.sort(detections, new Comparator<DetectionResult>() {
            @Override
            public int compare(DetectionResult r1, DetectionResult r2) {
                return Float.compare(r2.getConfidence(), r1.getConfidence());
            }
        });
        
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
                if (iou > iouThreshold) {
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
    
    public void setConfidenceThreshold(float threshold) {
        this.CONFIDENCE_THRESHOLD = threshold;
        Log.d(TAG, "置信度閾值設為: " + threshold);
    }
}
