package com.sharn.trafficsafety;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v2.5 重構版主活動（根據 ChatGPT 分析修正）
 * 
 * 修正項目：
 * 1. ✅ 使用真正多輸出解析 (boxes + scores)
 * 2. ✅ 修正座標轉換（256x256 → 原始尺寸）
 * 3. ✅ 暫時移除 crop/zoom，使用完整畫面
 * 4. ✅ 修正 preview/overlay 座標對齊
 */
public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "TrafficSafety";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    // UI 元件
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvStatus;
    private TextView tvDetections;
    private TextView tvDistance;
    private TextView tvSceneMode;
    private SeekBar seekBarThreshold;
    private Map<String, CheckBox> classCheckboxes = new HashMap<>();
    private CheckBox cbSoundAlert;
    private CheckBox cbVoiceAlert;
    
    // Camera & AI
    private ObjectDetectorWrapper objectDetector;
    private TrafficLightDetector trafficLightDetector;  // v2.3: 專用紅綠燈偵測
    private DetectionTracker tracker;
    private ExecutorService cameraExecutor;
    private Handler mainHandler;
    
    // Alert
    private MediaPlayer mediaPlayer;
    private TextToSpeech textToSpeech;
    
    // 狀態管理 - v2.4 優化
    private long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN = 800;  // 從 1500ms 降到 800ms 更靈敏
    private static final long ALERT_COOLDOWN_DANGER = 400;  // 危險時更快
    private DetectionTracker.TrackedObject lastAlertedObject = null;
    
    // v2.4: FPS 計算
    private long lastFrameTime = 0;
    private float currentFps = 0;
    private int frameCount = 0;
    private long fpsUpdateTime = 0;
    private TextView tvFps;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initAlertSystem();
        tracker = new DetectionTracker();
        
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.CAMERA}, 
                PERMISSION_REQUEST_CODE);
        }
    }
    
    private void initViews() {
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        tvStatus = findViewById(R.id.tvStatus);
        tvDetections = findViewById(R.id.tvDetections);
        tvDistance = findViewById(R.id.tvDistance);
        
        // v2.4: FPS 顯示
        tvFps = new TextView(this);
        tvFps.setTextSize(12);
        tvFps.setTextColor(0xFFAA00FF);
        tvFps.setText("FPS: --");
        // Note: 需要在 layout 加入此 TextView
        
        // v2.1: 場景模式顯示
        tvSceneMode = new TextView(this);
        tvSceneMode.setTextSize(12);
        tvSceneMode.setTextColor(0xFFAAAAAA);
        // Note: 需要在 layout 中加入這個 TextView
        
        seekBarThreshold = findViewById(R.id.seekBarThreshold);
        cbSoundAlert = findViewById(R.id.cbSoundAlert);
        cbVoiceAlert = findViewById(R.id.cbVoiceAlert);
        
        // v2.1: 動態類別選擇
        initClassCheckboxes();
        
        seekBarThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (objectDetector != null) {
                        // v2.5: 暫時禁用閾值調整，使用固定值
                        Log.d(TAG, "閾值調整功能已在 v2.5 中禁用");
                    }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    /**
     * v2.1: 初始化類別選擇 CheckBox
     */
    private void initClassCheckboxes() {
        // 需要在 layout 中為每個類別添加 CheckBox
        // 這裡是預設值
        String[] classes = {"person", "motorcycle", "car", "bus", "truck", "bicycle"};
        boolean[] defaultEnabled = {true, true, true, false, false, false};
        
        // 如果布局中有這些 CheckBox，綁定到 map
        int[] checkboxIds = {R.id.cbPedestrian, R.id.cbTrafficLight};
        // Note: layout 需要更新以支援多類別
    }
    
    private void initAlertSystem() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.TRADITIONAL_CHINESE);
                textToSpeech.setSpeechRate(1.1f);
            }
        });
        
        // v2.1: 使用更明顯的警示音效
        mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI);
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
        }
    }
    
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
    
    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        // v2.2: 提高相機解析度至 1920x1080，並設定 1.5x 光學/數位變焦
        Preview preview = new Preview.Builder()
            .setTargetResolution(new Size(1920, 1080))  // 從 1280x720 提升至 1080p
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(1920, 1080))  // 同步提升分析解析度
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        
        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
        
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        
        try {
            cameraProvider.unbindAll();
            Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            
            // v2.2: 設定 1.5x 變焦（放大畫面）
            CameraControl cameraControl = camera.getCameraControl();
            ZoomState currentZoom = camera.getCameraInfo().getZoomState().getValue();
            if (currentZoom != null) {
                float maxZoom = currentZoom.getMaxZoomRatio();
                float targetZoom = Math.min(1.5f, maxZoom);  // 設定 1.5x 或最大可用變焦
                cameraControl.setZoomRatio(targetZoom);
                Log.d(TAG, "Camera zoom set to: " + targetZoom + "x");
            }
            
            objectDetector = new ObjectDetectorWrapper(this);
            trafficLightDetector = new TrafficLightDetector();  // v2.3: 初始化紅綠燈偵測器
            updateStatus("系統就緒", 0xFF4CAF50, "--", "--");
            
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
        }
    }
    
    /**
     * v2.5: 核心分析流程 - 修正座標處理
     */
    private void analyzeImage(ImageProxy imageProxy) {
        if (objectDetector == null) {
            imageProxy.close();
            return;
        }
        
        Bitmap bitmap = imageProxy.toBitmap();
        if (bitmap == null) {
            imageProxy.close();
            return;
        }
        
        final int rotation = imageProxy.getImageInfo().getRotationDegrees();
        final int originalWidth = bitmap.getWidth();
        final int originalHeight = bitmap.getHeight();
        
        // v2.5: 暫時移除 crop/zoom，使用完整畫面進行偵測
        // 模型會自動縮放到 256x256，然後返回原始座標
        Bitmap processedBitmap = bitmap;
        
        // 如果需要旋轉，在偵測後處理
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            processedBitmap = Bitmap.createBitmap(bitmap, 0, 0, 
                originalWidth, originalHeight, matrix, true);
        }
        
        final int imgWidth = processedBitmap.getWidth();
        final int imgHeight = processedBitmap.getHeight();
        
        // 偵測 - 現在會正確解析 boxes + scores
        List<DetectionResult> rawResults = objectDetector.detect(processedBitmap);
        
        // v2.5: 紅綠燈偵測使用與行人相同的模型（模型已包含 traffic light 類別）
        final List<DetectionResult> trafficLightResults = new ArrayList<>();
        for (DetectionResult result : rawResults) {
            if (result.getLabel().equals("traffic light")) {
                trafficLightResults.add(result);
            }
        }
        
        // 用戶選擇過濾
        List<DetectionResult> filtered = filterByUserSelection(rawResults);
        
        // v2.5: 追蹤
        List<DetectionTracker.TrackedObject> tracked = tracker.update(filtered, imgWidth, imgHeight);
        
        // 轉換為顯示用 DetectionResult - 注意座標已經是正確的原始尺寸
        List<DetectionResult> displayResults = new ArrayList<>();
        for (DetectionTracker.TrackedObject track : tracked) {
            displayResults.add(new DetectionResult(track.label, track.confidence, track.bbox));
        }
        
        // 安全評估
        final SafetyStatus status = evaluateSafetyv21(tracked, imgWidth, imgHeight);
        
        mainHandler.post(() -> {
            // ⚠️ 修復：先設定影像尺寸，再設定偵測結果
            overlayView.setImageSize(imgWidth, imgHeight);
            overlayView.setDetections(displayResults, trafficLightResults);
            updateStatus(status);
            
            if (status.level != SafetyLevel.SAFE) {
                triggerAlertv21(status);
            }
        });
        
        // 釋放資源
        if (processedBitmap != bitmap) {
            processedBitmap.recycle();
        }
        bitmap.recycle();
        
        imageProxy.close();
    }
    
    /**
     * 根據用戶選擇過濾類別
     */
    private List<DetectionResult> filterByUserSelection(List<DetectionResult> results) {
        // 簡化：預設選擇所有
        return results;
    }
    
    /**
     * v2.1: 改進的安全評估（包含 TTC）
     */
    private SafetyStatus evaluateSafetyv21(List<DetectionTracker.TrackedObject> tracks, 
                                          int imgWidth, int imgHeight) {
        if (tracks.isEmpty()) {
            return new SafetyStatus(SafetyLevel.SAFE, "--", 0, "無目標", 
                                   "", 0xFF4CAF50, Float.MAX_VALUE);
        }
        
        // 找出最危險的目標
        DetectionTracker.TrackedObject mostDangerous = null;
        float minTTC = Float.MAX_VALUE;
        String dangerMsg = "";
        
        for (DetectionTracker.TrackedObject track : tracks) {
            float distance = track.estimateDistance(track.label, imgWidth, imgHeight);
            float ttc = track.estimateTTC(imgHeight);
            
            // 優先考慮 TTC，其次考慮距離
            float dangerScore = calculateDangerScore(distance, ttc, track.label);
            
            if (mostDangerous == null || dangerScore < minTTC) {
                minTTC = dangerScore;
                mostDangerous = track;
            }
        }
        
        if (mostDangerous == null) {
            return new SafetyStatus(SafetyLevel.SAFE, "--", tracks.size(), "監控中", 
                                   "", 0xFF66BB6A, Float.MAX_VALUE);
        }
        
        float distance = mostDangerous.estimateDistance(mostDangerous.label, imgWidth, imgHeight);
        float ttc = mostDangerous.estimateTTC(imgHeight);
        
        // v2.1: 更豐富的警示訊息
        StringBuilder msg = new StringBuilder();
        int color;
        SafetyLevel level;
        
        if (distance <= 15f || (ttc < 2f && ttc > 0)) {
            level = SafetyLevel.DANGER;
            color = 0xFFE53935;
            // v2.6: 使用 LabelUtils 取得中文標籤
            msg.append("⚠️ 危險！").append(LabelUtils.getChineseLabel(mostDangerous.label));
            if (ttc < 10) {
                msg.append(String.format(" %.1f秒碰撞", ttc));
            } else {
                msg.append(String.format(" %.0f米", distance));
            }
        } else if (distance <= 40f || (ttc < 4f && ttc > 0)) {
            level = SafetyLevel.WARNING;
            color = 0xFFFFA000;
            msg.append("⚡ 注意 ").append(LabelUtils.getChineseLabel(mostDangerous.label));
            msg.append(String.format(" %.0f米", distance));
            if (ttc < 10) {
                msg.append(String.format(" %.0f秒", ttc));
            }
        } else {
            level = SafetyLevel.SAFE;
            color = 0xFF66BB6A;
            msg.append("✓ ").append(LabelUtils.getChineseLabel(mostDangerous.label));
            msg.append(String.format(" %.0f米", distance));
        }

        return new SafetyStatus(level, String.format("%.0f", distance), tracks.size(), 
                           msg.toString(), LabelUtils.getChineseLabel(mostDangerous.label),
                               color, ttc);
    }
    
    /**
     * 計算危險分數（越低越危險）
     */
    private float calculateDangerScore(float distance, float ttc, String label) {
        // 不同類別的危險權重
        float weight = getClassWeight(label);
        
        if (ttc > 0 && ttc < 100) {
            // 有 TTC 時優先使用
            return ttc / weight;
        } else {
            // 沒有 TTC 使用距離
            return distance / weight;
        }
    }
    
    /**
     * v2.6: 使用 LabelUtils 取得類別權重
     */
    private float getClassWeight(String label) {
        return LabelUtils.getWeight(label);
    }
    
    /**
     * v2.5: 更新狀態（簡化版）
     */
    private void updateStatus(SafetyStatus status) {
        // 計算並顯示 FPS
        calculateFps();
        
        tvStatus.setText(status.message);
        tvStatus.setTextColor(status.color);
        if (status.level == SafetyLevel.DANGER) {
            tvStatus.setTypeface(null, Typeface.BOLD);
            pulsingAlert(tvStatus);
        } else {
            tvStatus.setTypeface(null, Typeface.NORMAL);
            tvStatus.setAlpha(1.0f);
        }
        
        tvDetections.setText(String.format("偵測到 %d 個目標", status.count));
        tvDistance.setText(String.format("距離: %s", status.distance));
    }
    
    /**
     * v2.4: 計算 FPS
     */
    private void calculateFps() {
        long now = System.currentTimeMillis();
        frameCount++;
        
        if (now - fpsUpdateTime >= 1000) {
            currentFps = frameCount * 1000f / (now - fpsUpdateTime);
            frameCount = 0;
            fpsUpdateTime = now;
        }
    }
    
    /**
     * v2.4: 危險閃爍效果
     */
    private void pulsingAlert(final View view) {
        view.animate()
            .alpha(0.5f)
            .setDuration(300)
            .withEndAction(() -> view.animate()
                .alpha(1.0f)
                .setDuration(300)
                .start())
            .start();
    }
    
    private void updateStatus(String message, int color, String distance, String scene) {
        tvStatus.setText(message);
        tvStatus.setTextColor(color);
        tvDistance.setText("距離: " + distance);
    }
    
    /**
     * v2.4: 改進的警示系統（優化冷卻時間）
     */
    private void triggerAlertv21(SafetyStatus status) {
        long now = System.currentTimeMillis();
        
        // v2.4: 根據危險程度調整冷卻時間
        long cooldown = (status.level == SafetyLevel.DANGER) ? ALERT_COOLDOWN_DANGER : ALERT_COOLDOWN;
        
        if (now - lastAlertTime < cooldown) {
            return;
        }
        lastAlertTime = now;
        
        // 音效 - v2.4: 優先級區分
        if (cbSoundAlert.isChecked() && mediaPlayer != null) {
            switch (status.level) {
                case DANGER:
                    // 危險：快速連續三聲
                    mediaPlayer.start();
                    mainHandler.postDelayed(() -> { if (mediaPlayer != null) mediaPlayer.start(); }, 150);
                    mainHandler.postDelayed(() -> { if (mediaPlayer != null) mediaPlayer.start(); }, 300);
                    break;
                case WARNING:
                    // 警告：兩聲，間隔較短
                    mediaPlayer.start();
                    mainHandler.postDelayed(() -> { if (mediaPlayer != null) mediaPlayer.start(); }, 350);
                    break;
            }
        }
        
        // 語音 - v2.4: 優化訊息
        if (cbVoiceAlert.isChecked() && textToSpeech != null) {
            String voiceMsg = status.message
                .replace("⚠️", "注意，危險")
                .replace("⚡", "注意")
                .replace("✓", "")
                .replace("秒碰撞", "秒")
                .replace("米", "公尺") // 統一單位
                .split(",")[0]; // 只播報主要部分
            
            textToSpeech.speak(voiceMsg, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }
    
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * v2.3: 處理紅綠燈警示
     */
    private void handleTrafficLightAlert(List<TrafficLightDetector.TrafficLightResult> trafficLights) {
        for (TrafficLightDetector.TrafficLightResult light : trafficLights) {
            if (light.isLit && TrafficLightDetector.isDangerous(light.state)) {
                // 紅燈或黃燈亮時警示
                String msg = "注意" + TrafficLightDetector.getSimpleLabel(light.state);
                if (textToSpeech != null) {
    textToSpeech.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null);
                }
                break;  // 只警示一次
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相機權限", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        if (tracker != null) {
            tracker.clear();
        }
    }
    
    // 安全狀態類
    enum SafetyLevel { SAFE, WARNING, DANGER }
    
    static class SafetyStatus {
        final SafetyLevel level;
        final String distance;
        final int count;
        final String message;
        final String closestLabel;
        final int color;
        final float ttc;
        
        SafetyStatus(SafetyLevel level, String distance, int count, String message,
                    String closestLabel, int color, float ttc) {
            this.level = level;
            this.distance = distance;
            this.count = count;
            this.message = message;
            this.closestLabel = closestLabel;
            this.color = color;
            this.ttc = ttc;
        }
    }
}