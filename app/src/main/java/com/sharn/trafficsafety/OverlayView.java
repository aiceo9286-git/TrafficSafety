package com.sharn.trafficsafety;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.5 簡化版 OverlayView
 * 
 * 使用統一的 DetectionResult 顯示所有偵測結果
 */
public class OverlayView extends View {
    
    private List<DetectionResult> detections = new ArrayList<>();
    private List<DetectionResult> trafficLights = new ArrayList<>();
    
    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBgPaint;
    private Paint trafficLightPaint;
    
    // ⚠️ 修正：加入原始影像尺寸（用於座標轉換）
    private int imageWidth = 1;
    private int imageHeight = 1;
    
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
        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        // v2.7.0 修正：從 6f 減小為 4f，減少視覺干擾
        boxPaint.setStrokeWidth(4f);
        
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        // v2.7.0 修正：從 36f 減小為 28f，減少文字佔用的畫面空間
        textPaint.setTextSize(28f);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setAntiAlias(true);
        
        textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);
        // v2.7.0 修正：從 200 減小為 160，降低背景遮擋
        textBgPaint.setAlpha(160);
        
        trafficLightPaint = new Paint();
        trafficLightPaint.setStyle(Paint.Style.STROKE);
        // v2.7.0 修正：從 8f 減小為 6f
        trafficLightPaint.setStrokeWidth(6f);
    }
    
    /**
     * ⚠️ 修正：設定原始影像尺寸（必須在 setDetections 前呼叫）
     */
    public void setImageSize(int width, int height) {
        this.imageWidth = width;
        this.imageHeight = height;
    }
    
    /**
     * v2.5: 設定偵測結果（統一使用 DetectionResult）
     */
    public void setDetections(List<DetectionResult> detections, 
                              List<DetectionResult> trafficLights) {
        this.detections = detections != null ? detections : new ArrayList<>();
        this.trafficLights = trafficLights != null ? trafficLights : new ArrayList<>();
        invalidate();
    }
    
    public void setDetections(List<DetectionResult> detections) {
        setDetections(detections, new ArrayList<>());
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 繪製一般物體偵測框
        for (DetectionResult detection : detections) {
            drawDetection(canvas, detection);
        }
        
        // 繪製紅綠燈（特別標記）
        for (DetectionResult light : trafficLights) {
            drawTrafficLight(canvas, light);
        }
    }
    
    /**
     * ⚠️ 修復：將原始影像座標轉換到畫布座標
     */
    private RectF mapRectToView(RectF src) {
        float viewW = getWidth();
        float viewH = getHeight();
        
        // centerCrop 縮放比值
        float scale = Math.max(viewW / (float)imageWidth, viewH / (float)imageHeight);
        float dx = (viewW - imageWidth * scale) / 2f;
        float dy = (viewH - imageHeight * scale) / 2f;
        
        return new RectF(
            src.left * scale + dx,
            src.top * scale + dy,
            src.right * scale + dx,
            src.bottom * scale + dy
        );
    }
    
    private void drawDetection(Canvas canvas, DetectionResult detection) {
        RectF srcLocation = detection.getLocation();
        if (srcLocation == null) return;
        
        // ⚠️ 修復：座標轉換（原始影像座標 → 畫布座標）
        RectF location = mapRectToView(srcLocation);
        
        // 根據類型決定顏色
        int color = getColorForLabel(detection.getLabel());
        boxPaint.setColor(color);
        textBgPaint.setColor(color);
        
        // 繪製邊界框
        canvas.drawRect(location, boxPaint);
        
        // ⚠️ 修復：標籤使用中文顯示
        String chineseLabel = getChineseLabel(detection.getLabel());
        String label = String.format("%s %.0f%%", 
            chineseLabel, 
            detection.getConfidence() * 100);
        
        float textWidth = textPaint.measureText(label);
        float textX = Math.max(0, location.left);
        float textY = Math.max(60, location.top);
        
        // 文字背景
        canvas.drawRect(textX, textY - 50, 
            textX + textWidth + 20, textY, 
            textBgPaint);
        
        // 文字
        canvas.drawText(label, textX + 10, textY - 10, textPaint);
    }
    
    private void drawTrafficLight(Canvas canvas, DetectionResult light) {
        RectF srcLocation = light.getLocation();
        if (srcLocation == null) return;
        
        // ⚠️ 修復：座標轉換
        RectF location = mapRectToView(srcLocation);
        
        // 紅綠燈使用黃色粗框特別標記
        trafficLightPaint.setColor(Color.YELLOW);
        trafficLightPaint.setStrokeWidth(8f);
        canvas.drawRect(location, trafficLightPaint);
        
        // ⚠️ 修復：標籤使用中文顯示
        String chineseLabel = getChineseLabel(light.getLabel());
        String label = String.format("🚦 %s %.0f%%", 
            chineseLabel, 
            light.getConfidence() * 100);
        
        float textWidth = textPaint.measureText(label);
        float textX = Math.max(0, location.left);
        float textY = Math.max(70, location.top);
        
        // 黃色背景
        Paint yellowBg = new Paint();
        yellowBg.setColor(Color.YELLOW);
        yellowBg.setAlpha(220);
        
        canvas.drawRect(textX, textY - 55, 
            textX + textWidth + 20, textY + 5, 
            yellowBg);
        
        // 黑色文字
        textPaint.setColor(Color.BLACK);
        canvas.drawText(label, textX + 10, textY - 10, textPaint);
        textPaint.setColor(Color.WHITE); // 恢復
    }
    
    /**
     * v2.6: 使用 LabelUtils 取得中文標籤
     */
    private String getChineseLabel(String label) {
        return LabelUtils.getChineseLabel(label);
    }
    
    private int getColorForLabel(String label) {
        if (label == null) return Color.GREEN;
        
        label = label.toLowerCase();
        
        if (label.contains("人") || label.contains("person")) {
            return Color.RED;
        } else if (label.contains("機車") || label.contains("motorcycle") || label.contains("摩托")) {
            return Color.MAGENTA;
        } else if (label.contains("腳踏車") || label.contains("bicycle") || label.contains("單車")) {
            return Color.CYAN;
        } else if (label.contains("卡車") || label.contains("truck") || label.contains("貨車")) {
            return Color.parseColor("#FF8C00");
        } else if (label.contains("公車") || label.contains("bus") || label.contains("巴士")) {
            return Color.parseColor("#FFD700");
        } else if (label.contains("車") || label.contains("car") || label.contains("汽車")) {
            return Color.GREEN;
        } else if (label.contains("紅綠燈") || label.contains("light")) {
            return Color.YELLOW;
        }
        
        return Color.GREEN;
    }
}