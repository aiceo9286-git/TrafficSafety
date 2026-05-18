package com.sharn.pedestriansafety;

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
 * 自定義視圖：在相機預覽上繪製偵測框
 */
public class OverlayView extends View {
    
    private List<DetectionResult> detections = new ArrayList<>();
    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBgPaint;
    
    // 影像與畫面的比例
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    
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
        // 邊界框畫筆
        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);
        
        // 文字畫筆
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48f);
        textPaint.setStyle(Paint.Style.FILL);
        
        // 文字背景畫筆
        textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);
    }
    
    public void setDetections(List<DetectionResult> detections) {
        this.detections = detections != null ? detections : new ArrayList<>();
        invalidate(); // 重新繪製
    }
    
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        for (DetectionResult detection : detections) {
            drawDetection(canvas, detection);
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
        String label = String.format("%s %.1f%%", 
            detection.getLabel(), 
            detection.getConfidence() * 100);
        
        // 文字背景
        float textWidth = textPaint.measureText(label);
        canvas.drawRect(location.left, location.top - 60, 
                       location.left + textWidth + 20, location.top, 
                       textBgPaint);
        
        // 文字
        canvas.drawText(label, location.left + 10, location.top - 15, textPaint);
    }
    
    private int getColorForType(DetectionResult detection) {
        if (detection.isPedestrian()) {
            return Color.RED;      // 行人：紅色
        } else if (detection.isTrafficLight()) {
            return Color.YELLOW; // 號誌：黃色
        }
        return Color.GREEN;      // 其他：綠色
    }
}
