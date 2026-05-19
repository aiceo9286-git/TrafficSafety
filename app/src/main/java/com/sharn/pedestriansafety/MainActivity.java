package com.sharn.pedestriansafety;

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

public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "PedestrianSafety";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    // UI 元件
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvStatus;
    private TextView tvDetections;
    private SeekBar seekBarThreshold;
    private CheckBox cbPedestrian;
    private CheckBox cbTrafficLight;
    private CheckBox cbSoundAlert;
    private CheckBox cbVoiceAlert;
    
    // Camera & AI
    private ObjectDetectorWrapper objectDetector;
    private ExecutorService cameraExecutor;
    private Handler mainHandler;
    
    // Alert
    private MediaPlayer mediaPlayer;
    private TextToSpeech textToSpeech;
    private AlertManager alertManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initAlertSystem();
        
        // 請求權限
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
        alertManager = new AlertManager(this::onAlertTriggered);
        
        // 初始化 TTS
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.TRADITIONAL_CHINESE);
                textToSpeech.setSpeechRate(1.0f);
            }
        });
        
        // 初始化音效
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
        // Preview - 使用高解析度預覽
        Preview preview = new Preview.Builder()
            .setTargetResolution(new Size(1280, 720))
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        
        // Image Analysis - 提高解析度以獲得更好辨識效果
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        
        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
        
        // Select back camera
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        
        // Bind use cases
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            
            // Init detector after camera is ready
            objectDetector = new ObjectDetectorWrapper(this);
            updateStatus("偵測系統就緒", 0xFF4CAF50);
            
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }
    
    private void analyzeImage(ImageProxy imageProxy) {
        if (objectDetector == null) {
            imageProxy.close();
            return;
        }
        
        // Get bitmap from ImageProxy
        Bitmap bitmap = imageProxy.toBitmap();
        if (bitmap == null) {
            imageProxy.close();
            return;
        }
        
        // Rotate bitmap if needed
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, 
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }
        
        // 裁剪畫面中央區域並放大 (模擬數位變焦效果)
        int cropWidth = (int)(bitmap.getWidth() * 0.6);  // 裁剪 60% 寬度
        int cropHeight = (int)(bitmap.getHeight() * 0.6); // 裁剪 60% 高度
        int cropX = (bitmap.getWidth() - cropWidth) / 2;    // 中央開始
        int cropY = (bitmap.getHeight() - cropHeight) / 2;
        
        // 裁剪中央區域
        Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight);
        
        // 放大回原尺寸以獲得更好辨識細節
        bitmap = Bitmap.createScaledBitmap(cropped, bitmap.getWidth(), bitmap.getHeight(), true);
        
        // Run detection
        List<DetectionResult> results = objectDetector.detect(bitmap);
        
        // Filter results based on user selection
        List<DetectionResult> filtered = new ArrayList<>();
        for (DetectionResult r : results) {
            if (r.isPedestrian() && cbPedestrian.isChecked()) {
                filtered.add(r);
            } else if (r.isTrafficLight() && cbTrafficLight.isChecked()) {
                filtered.add(r);
            }
        }
        
        // Update UI
        mainHandler.post(() -> {
            overlayView.setDetections(filtered);
            String detectionText = String.format("偵測到 %d 個目標", filtered.size());
            tvDetections.setText(detectionText);
            
            // Process alerts
            alertManager.processDetections(filtered, 
                cbSoundAlert.isChecked(), 
                cbVoiceAlert.isChecked());
        });
        
        imageProxy.close();
    }
    
    private void onAlertTriggered(DetectionResult detection) {
        runOnUiThread(() -> {
            updateStatus("警告！檢測到 " + detection.getLabel(), 0xFFFF5722);
            
            // Play sound
            if (cbSoundAlert.isChecked() && mediaPlayer != null) {
                mediaPlayer.start();
            }
            
            // Voice alert
            if (cbVoiceAlert.isChecked() && textToSpeech != null) {
                String message = detection.getAlertMessage();
                textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
            }
        });
    }
    
    private void updateStatus(String text, int color) {
        tvStatus.setText(text);
        tvStatus.setTextColor(color);
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
}
