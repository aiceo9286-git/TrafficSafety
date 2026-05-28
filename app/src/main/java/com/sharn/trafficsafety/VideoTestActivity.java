package com.sharn.trafficsafety;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual test screen for running YOLO object detection on app/src/main/assets/test_video.mp4.
 */
public class VideoTestActivity extends AppCompatActivity {
    private static final int MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = LinearLayout.LayoutParams.WRAP_CONTENT;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorService executor;
    private VideoProcessor videoProcessor;
    private ImageView previewImage;
    private TextView statusText;
    private TextView frameText;
    private TextView detectionText;
    private TextView summaryText;
    private Button runButton;
    private Button stopButton;
    private Bitmap lastPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        executor = Executors.newSingleThreadExecutor();
        videoProcessor = new VideoProcessor(this);

        setContentView(buildContentView());
        bindActions();
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(16, 18, 20));
        scrollView.addView(root, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        statusText = textView("影片辨識測試尚未開始", 18, Color.WHITE, true);
        root.addView(statusText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        frameText = textView("來源: assets/test_video.mp4", 14, Color.LTGRAY, false);
        frameText.setPadding(0, dp(8), 0, dp(8));
        root.addView(frameText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        previewImage = new ImageView(this);
        previewImage.setAdjustViewBounds(true);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setBackgroundColor(Color.BLACK);
        root.addView(previewImage, new LinearLayout.LayoutParams(MATCH_PARENT, dp(260)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(12), 0, dp(12));
        root.addView(buttons, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        runButton = new Button(this);
        runButton.setText("開始測試");
        buttons.addView(runButton, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setEnabled(false);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        stopParams.setMarginStart(dp(8));
        buttons.addView(stopButton, stopParams);

        detectionText = textView("逐幀偵測結果會顯示在這裡", 14, Color.WHITE, false);
        root.addView(detectionText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        summaryText = textView("統計: -", 14, Color.rgb(180, 220, 255), false);
        summaryText.setPadding(0, dp(12), 0, 0);
        root.addView(summaryText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        return scrollView;
    }

    private void bindActions() {
        runButton.setOnClickListener(v -> startVideoTest());
        stopButton.setOnClickListener(v -> {
            videoProcessor.cancel();
            statusText.setText("正在停止...");
            stopButton.setEnabled(false);
        });
    }

    private void startVideoTest() {
        recycleLastPreview();
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        previewImage.setImageBitmap(null);
        detectionText.setText("");
        summaryText.setText("統計: -");
        statusText.setText("影片辨識測試執行中...");

        executor.execute(() -> videoProcessor.processTestVideo(new VideoProcessor.Callback() {
            @Override
            public void onStarted(long durationMs, long frameIntervalMs) {
                mainHandler.post(() -> frameText.setText(String.format(
                    Locale.TAIWAN,
                    "影片長度: %.1f 秒，抽幀間隔: %.1f 秒",
                    durationMs / 1000f,
                    frameIntervalMs / 1000f
                )));
            }

            @Override
            public void onFrameProcessed(VideoProcessor.VideoFrameResult frameResult) {
                Bitmap annotated = drawDetections(frameResult);
                Bitmap callbackPreview = frameResult.getPreviewBitmap();
                if (callbackPreview != null && callbackPreview != annotated) {
                    callbackPreview.recycle();
                }

                mainHandler.post(() -> {
                    recycleLastPreview();
                    lastPreview = annotated;
                    previewImage.setImageBitmap(lastPreview);
                    statusText.setText(String.format(
                        Locale.TAIWAN,
                        "已處理第 %d 幀，時間 %.1f 秒",
                        frameResult.getFrameIndex(),
                        frameResult.getTimestampMs() / 1000f
                    ));
                    detectionText.setText(formatFrameDetections(frameResult));
                    summaryText.setText(formatClassCounts("累計統計", frameResult.getClassCounts()));
                });
            }

            @Override
            public void onCompleted(VideoProcessor.VideoSummary summary) {
                mainHandler.post(() -> {
                    runButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    statusText.setText(String.format(
                        Locale.TAIWAN,
                        "%s，處理 %d 幀，用時 %.1f 秒",
                        summary.isCancelled() ? "影片辨識已停止" : "影片辨識完成",
                        summary.getProcessedFrames(),
                        summary.getElapsedMs() / 1000f
                    ));
                    summaryText.setText(formatClassCounts("最終統計", summary.getClassCounts()));
                });
            }

            @Override
            public void onError(Exception error) {
                mainHandler.post(() -> {
                    runButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    statusText.setText("影片辨識失敗: " + error.getMessage());
                });
            }
        }));
    }

    private Bitmap drawDetections(VideoProcessor.VideoFrameResult frameResult) {
        Bitmap source = frameResult.getPreviewBitmap();
        Bitmap annotated = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(annotated);

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(Math.max(3f, annotated.getWidth() / 240f));
        boxPaint.setColor(Color.rgb(0, 230, 118));

        Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.argb(190, 0, 0, 0));

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(Math.max(24f, annotated.getWidth() / 26f));

        for (DetectionResult detection : frameResult.getDetections()) {
            RectF location = detection.getLocation();
            if (location == null) {
                continue;
            }
            canvas.drawRect(location, boxPaint);

            String label = String.format(
                Locale.TAIWAN,
                "%s %.0f%%",
                detection.getChineseLabel(),
                detection.getConfidence() * 100f
            );
            float labelWidth = textPaint.measureText(label);
            float labelHeight = textPaint.getTextSize() + dp(6);
            float left = Math.max(0, location.left);
            float top = Math.max(labelHeight, location.top);

            canvas.drawRect(
                left,
                top - labelHeight,
                Math.min(annotated.getWidth(), left + labelWidth + dp(8)),
                top,
                textBgPaint
            );
            canvas.drawText(label, left + dp(4), top - dp(5), textPaint);
        }

        return annotated;
    }

    private String formatFrameDetections(VideoProcessor.VideoFrameResult frameResult) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(
            Locale.TAIWAN,
            "第 %d 幀 @ %.1f 秒，尺寸 %dx%d\n",
            frameResult.getFrameIndex(),
            frameResult.getTimestampMs() / 1000f,
            frameResult.getFrameWidth(),
            frameResult.getFrameHeight()
        ));

        List<DetectionResult> detections = frameResult.getDetections();
        if (detections.isEmpty()) {
            builder.append("本幀未偵測到交通相關物件");
            return builder.toString();
        }

        for (DetectionResult detection : detections) {
            RectF box = detection.getLocation();
            if (box == null) {
                continue;
            }
            builder.append(String.format(
                Locale.TAIWAN,
                "%s (%s)  信心度 %.1f%%  位置 [%.0f, %.0f, %.0f, %.0f]\n",
                detection.getChineseLabel(),
                detection.getLabel(),
                detection.getConfidence() * 100f,
                box.left,
                box.top,
                box.right,
                box.bottom
            ));
        }
        return builder.toString();
    }

    private String formatClassCounts(String title, Map<String, Integer> classCounts) {
        StringBuilder builder = new StringBuilder(title).append(": ");
        if (classCounts.isEmpty()) {
            return builder.append("尚無偵測結果").toString();
        }

        boolean first = true;
        for (Map.Entry<String, Integer> entry : classCounts.entrySet()) {
            if (!first) {
                builder.append("，");
            }
            builder.append(entry.getKey()).append(" ").append(entry.getValue()).append(" 次");
            first = false;
        }
        return builder.toString();
    }

    private TextView textView(String text, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        if (bold) {
            textView.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        return textView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void recycleLastPreview() {
        if (lastPreview != null) {
            lastPreview.recycle();
            lastPreview = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        videoProcessor.close();
        executor.shutdownNow();
        recycleLastPreview();
    }
}
