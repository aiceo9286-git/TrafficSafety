package com.sharn.trafficsafety;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.3 改進版：同時顯示 YOLO 物體偵測和紅綠燈偵測結果
 */
public class OverlayView extends View {
    
    private List<DetectionResult> detections = new ArrayList<>();
    private List<TrafficLightDetector.TrafficLightResult> trafficLights = new ArrayList<>();  // v2.3
    
    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBgPaint;
    
    // v2.3: 紅綠燈專用畫筆
    private Paint trafficLightBoxPaint;
    private Paint trafficLightTextPaint;
    private Paint trafficLightGlowPaint;
    
    // 影像與畫面的比例
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    
    // 畫布尺寸
    private int canvasWidth = 0;
    private int canvasHeight = 0;
    
    public OverlayView(Context context) {
        super(context);
        init();
    }
    
    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
  init();
    }
    
    public OverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // YOLO 邊界框畫筆
        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        
        // 文字畫筆
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
   textPaint.setTextSize(36f);
        textPaint.setStyle(Paint.Style.FILL);
 textPaint.setAntiAlias(true);
        
        // 文字背景畫筆
        textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setAlpha(200);
        
        // v2.3: 紅綠燈邊界框
        trafficLightBoxPaint = new Paint();
        trafficLightBoxPaint.setStyle(Paint.Style.STROKE);
        trafficLightBoxPaint.setStrokeWidth(8f);
        trafficLightBoxPaint.setColor(Color.WHITE);
        
        // v2.3: 紅綠燈文字（大字醒目）
        trafficLightTextPaint = new Paint();
        trafficLightTextPaint.setColor(Color.WHITE);
        trafficLightTextPaint.setTextSize(56f);
     trafficLightTextPaint.setStyle(Paint.Style.FILL);
        trafficLightTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        trafficLightTextPaint.setAntiAlias(true);
        
        // v2.3: 亮燈發光效果
        trafficLightGlowPaint = new Paint();
     trafficLightGlowPaint.setStyle(Paint.Style.STROKE);
     trafficLightGlowPaint.setStrokeWidth(12f);
    }
    
    /**
     * v2.3: 同時設定物體偵測和紅綠燈結果
     */
    public void setDetections(List<DetectionResult> detections, 
                              List<TrafficLightDetector.TrafficLightResult> trafficLights) {
        this.detections = detections != null ? detections : new ArrayList<>();
   this.trafficLights = trafficLights != null ? trafficLights : new ArrayList<>();
        invalidate(); // 重新繪製
    }
    
    // 向後兼容
    public void setDetections(List<DetectionResult> detections) {
        setDetections(detections, new ArrayList<>());
    }
    
    public void setScale(float scaleX, float scaleY) {
  this.scaleX = scaleX;
        this.scaleY = scaleY;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
    // 記錄畫布尺寸（用於紅綠燈座標轉換）
        canvasWidth = canvas.getWidth();
        canvasHeight = canvas.getHeight();
        
        // 繪製 YOLO 物體偵測框
        for (DetectionResult detection : detections) {
       drawDetection(canvas, detection);
        }
        
        // v2.3: 繪製紅綠燈偵測框
        for (TrafficLightDetector.TrafficLightResult light : trafficLights) {
   drawTrafficLight(canvas, light);
        }
    }
    
    private void drawDetection(Canvas canvas, DetectionResult detection) {
        RectF location = detection.getLocation();
  
        // 根據類型決定顏色
        int color = getColorForType(detection);
        boxPaint.setColor(color);
        textBgPaint.setColor(color);
        
        // 繪製邊界框
        canvas.drawRect(location, boxPaint);
 
        // 繪製標籤
        String label = String.format("%s %.0f%%", 
         detection.getLabel(), 
  detection.getConfidence() * 100);
        
  // 文字背景
        float textWidth = textPaint.measureText(label);
        float textX = Math.max(0, location.left);
        float textY = Math.max(60, location.top);
        
        canvas.drawRect(textX, textY - 50, 
        textX + textWidth + 20, textY, 
          textBgPaint);
        
        // 文字
        canvas.drawText(label, textX + 10, textY - 10, textPaint);
    }
    
    /**
     * v2.3: 繪製紅綠燈
     */
    private void drawTrafficLight(Canvas canvas, TrafficLightDetector.TrafficLightResult light) {
        // 轉換正規化座標到像素座標
        float left = light.boundingBox.left * canvasWidth;
    float top = light.boundingBox.top * canvasHeight;
        float right = light.boundingBox.right * canvasWidth;
        float bottom = light.boundingBox.bottom * canvasHeight;
        
        RectF pixelBox = new RectF(left, top, right, bottom);
        
   // 根據燈號狀態設定顏色
        int stateColor = TrafficLightDetector.getStateColor(light.state);
     trafficLightBoxPaint.setColor(stateColor);
        trafficLightGlowPaint.setColor(stateColor);
        
        // 如果亮燈，繪製發光效果
        if (light.isLit) {
    trafficLightGlowPaint.setAlpha(100);
    trafficLightGlowPaint.setStyle(Paint.Style.STROKE);
     canvas.drawRect(pixelBox.left - 4, pixelBox.top - 4, 
             pixelBox.right + 4, pixelBox.bottom + 4, 
            trafficLightGlowPaint);
  }
        
        // 繪製邊界框（白色粗框）
        trafficLightBoxPaint.setStrokeWidth(8f);
        canvas.drawRect(pixelBox, trafficLightBoxPaint);
        
  // 繪製角標
        float cornerLength = 20f;
 int whiteColor = Color.WHITE;
        
        // 左上角
        canvas.drawLine(pixelBox.left, pixelBox.top, pixelBox.left + cornerLength, pixelBox.top, trafficLightBoxPaint);
        canvas.drawLine(pixelBox.left, pixelBox.top, pixelBox.left, pixelBox.top + cornerLength, trafficLightBoxPaint);
        
   // 右上角
        canvas.drawLine(pixelBox.right - cornerLength, pixelBox.top, pixelBox.right, pixelBox.top, trafficLightBoxPaint);
    canvas.drawLine(pixelBox.right, pixelBox.top, pixelBox.right, pixelBox.top + cornerLength, trafficLightBoxPaint);
      
        // 左下角
       canvas.drawLine(pixelBox.left, pixelBox.bottom - cornerLength, pixelBox.left, pixelBox.bottom, trafficLightBoxPaint);
  canvas.drawLine(pixelBox.left, pixelBox.bottom, pixelBox.left + cornerLength, pixelBox.bottom, trafficLightBoxPaint);
        
        // 右下角
     canvas.drawLine(pixelBox.right, pixelBox.bottom - cornerLength, pixelBox.right, pixelBox.bottom, trafficLightBoxPaint);
        canvas.drawLine(pixelBox.right - cornerLength, pixelBox.bottom, pixelBox.right, pixelBox.bottom, trafficLightBoxPaint);
        
        // 繪製標籤
        String label = light.getStateLabel();
        if (light.confidence < 1.0f) {
 label += String.format(" %.0f%%", light.confidence * 100);
        }
        
        trafficLightTextPaint.setColor(Color.BLACK);
        float textWidth = trafficLightTextPaint.measureText(label);
     
        // 標籤背景
        Paint labelBgPaint = new Paint();
     labelBgPaint.setStyle(Paint.Style.FILL);
        labelBgPaint.setColor(stateColor);
     labelBgPaint.setAlpha(220);
      
        float labelX = pixelBox.left;
     float labelY = Math.max(70, pixelBox.top);
        
        canvas.drawRect(labelX - 4, labelY - 60, 
    labelX + textWidth + 24, labelY + 10, 
       labelBgPaint);
      
   // 標籤文字
        trafficLightTextPaint.setColor(Color.WHITE);
        canvas.drawText(label, labelX + 8, labelY - 10, trafficLightTextPaint);
    }
    
    private int getColorForType(DetectionResult detection) {
        String label = detection.getLabel().toLowerCase();
        
     if (label.contains("person") || label.contains("人") || label.contains("pedestrian")) {
      return Color.RED;      // 行人/人：紅色（高危險）
        } else if (label.contains("motorcycle") || label.contains("機車") || label.contains("摩托")) {
         return Color.MAGENTA;  // 機車：洋紅色（台灣路況常見）
        } else if (label.contains("bicycle") || label.contains("腳踏車") || label.contains("單車")) {
            return Color.CYAN;       // 腳踏車：青色
        } else if (label.contains("truck") || label.contains("卡車") || label.contains("貨車")) {
   return Color.parseColor("#FF8C00");  // 卡車：深橘色
        } else if (label.contains("bus") || label.contains("公車") || label.contains("巴士")) {
    return Color.parseColor("#FFD700");  // 公車：金色
        } else if (label.contains("car") || label.contains("車") || label.contains("汽車")) {
    return Color.GREEN;      // 汽車：綠色
        }
        
     return Color.parseColor("#4CAF50");  // 預設綠色
    }
}
