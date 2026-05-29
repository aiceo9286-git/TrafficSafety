package com.sharn.trafficsafety;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
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
    private Paint boxFillPaint;
    private Paint textPaint;
    private Paint textBgPaint;
    private Paint badgeTextPaint;
    private Paint badgeBgPaint;
    private Paint trafficLightPaint;
    private Paint trafficLightFillPaint;
    private Paint summaryBgPaint;

    private float labelPaddingH;
    private float labelPaddingV;
    private float labelCornerRadius;
    private float badgeRadius;
    private float summaryMargin;
    private float summaryTopOffset;
    private float summaryLineHeight;
    private int maxSummaryItems = 6;
    private boolean useSideSummary = true;
    
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
        float boxStrokeWidth = dp(2);
        labelPaddingH = dp(4);
        labelPaddingV = dp(2);
        labelCornerRadius = dp(4);
        badgeRadius = dp(9);
        summaryMargin = dp(8);
        summaryTopOffset = dp(64);
        summaryLineHeight = sp(18);

        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(boxStrokeWidth);
        boxPaint.setAntiAlias(true);

        boxFillPaint = new Paint();
        boxFillPaint.setStyle(Paint.Style.FILL);
        boxFillPaint.setAlpha(28);
        
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(sp(12));
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setAntiAlias(true);
        
        textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setAlpha(145);

        badgeTextPaint = new Paint();
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(sp(10));
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setStyle(Paint.Style.FILL);
        badgeTextPaint.setAntiAlias(true);

        badgeBgPaint = new Paint();
        badgeBgPaint.setStyle(Paint.Style.FILL);
        badgeBgPaint.setAlpha(190);
        
        trafficLightPaint = new Paint();
        trafficLightPaint.setStyle(Paint.Style.STROKE);
        trafficLightPaint.setStrokeWidth(boxStrokeWidth);
        trafficLightPaint.setAntiAlias(true);

        trafficLightFillPaint = new Paint();
        trafficLightFillPaint.setStyle(Paint.Style.FILL);
        trafficLightFillPaint.setColor(Color.YELLOW);
        trafficLightFillPaint.setAlpha(36);

        summaryBgPaint = new Paint();
        summaryBgPaint.setColor(Color.BLACK);
        summaryBgPaint.setAlpha(115);
        summaryBgPaint.setStyle(Paint.Style.FILL);
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
    
    /**
     * true: boxes show only compact index badges; labels are summarized at the side.
     * false: each box gets a small label above the box.
     */
    public void setUseSideSummary(boolean useSideSummary) {
        this.useSideSummary = useSideSummary;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 繪製一般物體偵測框
        int index = 1;
        for (DetectionResult detection : detections) {
            drawDetection(canvas, detection, index++);
        }
        
        // 繪製紅綠燈（特別標記）
        for (DetectionResult light : trafficLights) {
            drawTrafficLight(canvas, light, index++);
        }

        if (useSideSummary) {
            drawSideSummary(canvas);
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
    
    private void drawDetection(Canvas canvas, DetectionResult detection, int index) {
        RectF srcLocation = detection.getLocation();
        if (srcLocation == null) return;
        
        // ⚠️ 修復：座標轉換（原始影像座標 → 畫布座標）
        RectF location = mapRectToView(srcLocation);
        
        // 根據類型決定顏色
        int color = getColorForLabel(detection.getLabel());
        boxPaint.setColor(color);
        boxFillPaint.setColor(color);
        textBgPaint.setColor(color);
        
        // 繪製低干擾邊界框：淡色填滿 + 2dp 細框
        canvas.drawRect(location, boxFillPaint);
        canvas.drawRect(location, boxPaint);
        
        if (useSideSummary) {
            drawBadge(canvas, location, index, color);
        } else {
            drawLabelAboveBox(canvas, location, formatLabel(detection), color, false);
        }
    }
    
    private void drawTrafficLight(Canvas canvas, DetectionResult light, int index) {
        RectF srcLocation = light.getLocation();
        if (srcLocation == null) return;
        
        // ⚠️ 修復：座標轉換
        RectF location = mapRectToView(srcLocation);
        
        // 紅綠燈用同樣細框呈現，靠顏色和摘要標示區分
        trafficLightPaint.setColor(Color.YELLOW);
        canvas.drawRect(location, trafficLightFillPaint);
        canvas.drawRect(location, trafficLightPaint);
        
        if (useSideSummary) {
            drawBadge(canvas, location, index, Color.YELLOW);
        } else {
            drawLabelAboveBox(canvas, location, formatLabel(light), Color.YELLOW, true);
        }
    }

    private void drawBadge(Canvas canvas, RectF location, int index, int color) {
        float cx = clamp(location.left + badgeRadius, badgeRadius, getWidth() - badgeRadius);
        float cy = clamp(location.top + badgeRadius, badgeRadius, getHeight() - badgeRadius);

        badgeBgPaint.setColor(color);
        canvas.drawCircle(cx, cy, badgeRadius, badgeBgPaint);

        Paint.FontMetrics metrics = badgeTextPaint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(String.valueOf(index), cx, baseline, badgeTextPaint);
    }

    private void drawLabelAboveBox(Canvas canvas, RectF location, String label, int color, boolean darkText) {
        float textWidth = textPaint.measureText(label);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float labelHeight = (metrics.descent - metrics.ascent) + labelPaddingV * 2;
        float labelWidth = textWidth + labelPaddingH * 2;
        float left = clamp(location.left, 0, Math.max(0, getWidth() - labelWidth));
        float top = location.top - labelHeight - dp(2);

        if (top < 0) {
            top = Math.min(getHeight() - labelHeight, location.bottom + dp(2));
        }

        RectF bg = new RectF(left, top, left + labelWidth, top + labelHeight);
        textBgPaint.setColor(color);
        canvas.drawRoundRect(bg, labelCornerRadius, labelCornerRadius, textBgPaint);

        textPaint.setColor(darkText ? Color.BLACK : Color.WHITE);
        canvas.drawText(label, left + labelPaddingH, top + labelPaddingV - metrics.ascent, textPaint);
        textPaint.setColor(Color.WHITE);
    }

    private void drawSideSummary(Canvas canvas) {
        int totalCount = detections.size() + trafficLights.size();
        if (totalCount == 0) return;

        float maxWidth = 0;
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (DetectionResult detection : detections) {
            lines.add(index++ + " " + formatLabel(detection));
        }
        for (DetectionResult light : trafficLights) {
            lines.add(index++ + " " + formatLabel(light));
        }

        int hiddenCount = Math.max(0, lines.size() - maxSummaryItems);
        if (hiddenCount > 0) {
            lines = new ArrayList<>(lines.subList(0, maxSummaryItems));
            lines.add("+" + hiddenCount);
        }

        for (String line : lines) {
            maxWidth = Math.max(maxWidth, textPaint.measureText(line));
        }
        
        float width = Math.min(maxWidth + labelPaddingH * 2, getWidth() * 0.42f);
        float height = lines.size() * summaryLineHeight + labelPaddingV * 2;
        float left = getWidth() - width - summaryMargin;
        float top = Math.min(summaryTopOffset, Math.max(summaryMargin, getHeight() - height - summaryMargin));
        RectF bg = new RectF(left, top, left + width, top + height);
        
        canvas.drawRoundRect(bg, labelCornerRadius, labelCornerRadius, summaryBgPaint);
        
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = top + labelPaddingV - metrics.ascent;
        for (String line : lines) {
            canvas.drawText(line, left + labelPaddingH, baseline, textPaint);
            baseline += summaryLineHeight;
        }
    }

    private String formatLabel(DetectionResult detection) {
        return String.format("%s %.0f%%",
            getChineseLabel(detection.getLabel()),
            detection.getConfidence() * 100);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            getResources().getDisplayMetrics());
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
