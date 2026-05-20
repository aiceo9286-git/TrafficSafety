package com.sharn.trafficsafety;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
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
 * 改進版主活動
 * 主要改善：
 * 1. 整合 DetectionTracker 進行連續幀確認
 * 2. 實現距離估算和分級警示
 * 3. 統一UI資料來源（避免頂部/底部不一致）
 * 4. 移除中央放大，改為全畫面ROI過濾
 */
public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "PedestrianSafety";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    // 分級警示閾值
    private static final float DISTANCE_DANGER = 20.0f;    // 公尺 - 危險
    private static final float DISTANCE_WARNING = 50.0f;   // 公尺 - 警告
    
    // UI 元件
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvStatus;
    private TextView tvDetections;
    private TextView tvDistance;
    private SeekBar seekBarThreshold;
    private CheckBox cbPedestrian;
    private CheckBox cbTrafficLight;
    private CheckBox cbSoundAlert;
    private CheckBox cbVoiceAlert;
    
    // Camera & AI
    private ObjectDetectorWrapper objectDetector;
    private DetectionTracker tracker;           // 新增：追蹤器
    private ExecutorService cameraExecutor;
    private Handler mainHandler;
    
    // Alert
    private MediaPlayer mediaPlayer;
    private TextToSpeech textToSpeech;
    
    // 狀態管理
    private long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN = 2000; // 2秒內不重複警示
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initAlertSystem();
        tracker = new DetectionTracker();  // 初始化追蹤器
        
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
        seekBarThreshold = findViewById(R.id.seekBarThreshold);
        cbPedestrian = findViewById(R.id.cbPedestrian);
        cbTrafficLight = findViewById(R.id.cbTrafficLight);
        cbSoundAlert = findViewById(R.id.cbSoundAlert);
        cbVoiceAlert = findViewById(R.id.cbVoiceAlert);
        
        seekBarThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (objectDetector != null) {
                    objectDetector.setConfidenceThreshold(progress / 100.0f);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void initAlertSystem() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.TRADITIONAL_CHINESE);
                textToSpeech.setSpeechRate(1.0f);
            }
        });
        
        mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
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
        Preview preview = new Preview.Builder()
            .setTargetResolution(new Size(1280, 720))
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        
        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
        
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            
            objectDetector = new ObjectDetectorWrapper(this);
            updateStatus("偵測系統就緒", 0xFF4CAF50, "--");
            
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    
    }
    
    /**
     * 核心分析流程 - 使用追蹤器和分級警示
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
        
        // 旋轉處理
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, 
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }
        
        // Step 1: 原始偵測
        List<DetectionResult> rawResults = objectDetector.detect(bitmap);
        
        // Step 2: 用戶選擇過濾
        List<DetectionResult> filtered = new ArrayList<>();
        for (DetectionResult r : rawResults) {
            if (r.isPedestrian() && cbPedestrian.isChecked()) {
                filtered.add(r);
            } else if (r.isTrafficLight() && cbTrafficLight.isChecked()) {
                filtered.add(r);
            }
        }
        
        // Step 3: 追蹤（連續幀確認）
        List<DetectionTracker.TrackedObject> tracked = tracker.update(filtered);
        
        // Step 4: 轉換為顯示用的 DetectionResult
        List<DetectionResult> displayResults = new ArrayList<>();
        for (DetectionTracker.TrackedObject track : tracked) {
            displayResults.add(new DetectionResult(track.label, track.confidence, track.bbox));
        }
        
        // Step 5: UI更新和警示
        final List<DetectionResult> finalResults = displayResults;
        final int activeCount = tracked.size();
        final SafetyStatus status = evaluateSafety(tracked);
        
        mainHandler.post(() -> {
            // 統一更新UI
            overlayView.setDetections(finalResults);
            updateStatus(status);
            
            // 觸發警示
            if (status.level >= 1) { // WARNING or DANGER
                triggerAlert(status);
            }
        });
        
        imageProxy.close();
    }
    
    /**
     * 安全狀態評估
     */
    private SafetyStatus evaluateSafety(List<DetectionTracker.TrackedObject> tracks) {
        if (tracks.isEmpty()) {
            return new SafetyStatus(SafetyLevel.SAFE, "--", 0, "無偵測目標", 0xFF4CAF50);
        }
        
        // 找最近的目標
        float minDistance = Float.MAX_VALUE;
        String closestLabel = "";
        DetectionTracker.TrackedObject closestObject = null;
        
        for (DetectionTracker.TrackedObject track : tracks) {
            float dist = track.estimateDistance();
            if (dist < minDistance) {
                minDistance = dist;
                closestLabel = track.label;
                closestObject = track;
            }
        }
        
        // 分級
        if (minDistance <= DISTANCE_DANGER) {
            String msg = "⚠️ 危險！" + closestLabel + " 極近 " + String.format("%.0f", minDistance) + "m";
            return new SafetyStatus(SafetyLevel.DANGER, 
                                   String.format("%.0f", minDistance) + "m", 
                                   activeCount, msg, 0xFFE53935);
        } else if (minDistance <= DISTANCE_WARNING) {
            String msg = "⚡ 注意 " + closestLabel + " " + String.format("%.0f", minDistance) + "m";
            return new SafetyStatus(SafetyLevel.WARNING, 
                                   String.format("%.0f", minDistance) + "m", 
                                   activeCount, msg, 0xFFFFA000);
        } else {
            String msg = "✓ 監控中 " + closestLabel + " " + String.format("%.0f", minDistance) + "m";
            return new SafetyStatus(SafetyLevel.SAFE, 
                                   String.format("%.0f", minDistance) + "m", 
                                   activeCount, msg, 0xFF66BB6A);
        }
    }
    
    private void updateStatus(SafetyStatus status) {
        tvStatus.setText(status.message);
        tvStatus.setTextColor(status.color);
        tvDetections.setText("偵測到 " + status.count + " 個目標");
        tvDistance.setText("距離: " + status.distance);
    }
    
    private void updateStatus(String message, int color, String distance) {
        tvStatus.setText(message);
        tvStatus.setTextColor(color);
        tvDistance.setText("距離: " + distance);
    }
    
    /**
     * 觸發警示（有冷卻時間）
     */
    private void triggerAlert(SafetyStatus status) {
        long now = System.currentTimeMillis();
        if (now - lastAlertTime < ALERT_COOLDOWN) {
            return; // 冷卻中
        }
        lastAlertTime = now;
        
        // 音效
        if (cbSoundAlert.isChecked() && mediaPlayer != null) {
            if (status.level == SafetyLevel.DANGER) {
                // 危險級別播三次
                mediaPlayer.start();
                mainHandler.postDelayed(() -> mediaPlayer.start(), 300);
                mainHandler.postDelayed(() -> mediaPlayer.start(), 600);
            } else {
                mediaPlayer.start();
            }
        }
        
        // 語音
        if (cbVoiceAlert.isChecked() && textToSpeech != null) {
            String voiceMsg = status.message.replace("⚠️", "").replace("⚡", "").replace("✓", "").replace("--", ""));
            textToSpeech.speak(voiceMsg, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }
    
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED;
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
    }
    
    // 安全狀態類
    enum SafetyLevel { SAFE, WARNING, DANGER }
    
    static class SafetyStatus {
        final SafetyLevel level;
        final String distance;
        final int count;
        final String message;
        final int color;
        
        SafetyStatus(SafetyLevel level, String distance, int count, String message, int color) {
            this.level = level;
            this.distance = distance;
            this.count = count;
            this.message = message;
            this.color = color;
        }
    }
}