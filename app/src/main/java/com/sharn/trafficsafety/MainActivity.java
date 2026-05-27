package com.sharn.trafficsafety;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主活動只負責生命週期、UI 事件與偵測流程協調。
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "TrafficSafety";

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

    private ExecutorService cameraExecutor;
    private Handler mainHandler;
    private CameraManager cameraManager;
    private AlertSystem alertSystem;
    private StateManager stateManager;
    private DetectionPipeline detectionPipeline;

    private int frameCount = 0;
    private long fpsUpdateTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();
        stateManager = new StateManager();

        initViews();
        bindStateControls();

        alertSystem = new AlertSystem(this, mainHandler);
        cameraManager = new CameraManager(this, previewView, cameraExecutor);

        if (cameraManager.allPermissionsGranted()) {
            startCamera();
        } else {
            cameraManager.requestCameraPermission();
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
    }

    private void bindStateControls() {
        AppState state = stateManager.getState();
        seekBarThreshold.setProgress(Math.round(state.getConfidenceThreshold() * 100));
        cbPedestrian.setChecked(state.isPedestrianDetectionEnabled());
        cbTrafficLight.setChecked(state.isTrafficLightDetectionEnabled());
        cbSoundAlert.setChecked(state.isSoundAlertEnabled());
        cbVoiceAlert.setChecked(state.isVoiceAlertEnabled());

        seekBarThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stateManager.setConfidenceThreshold(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        cbPedestrian.setOnCheckedChangeListener((buttonView, isChecked) ->
            stateManager.setPedestrianDetectionEnabled(isChecked));
        cbTrafficLight.setOnCheckedChangeListener((buttonView, isChecked) ->
            stateManager.setTrafficLightDetectionEnabled(isChecked));
        cbSoundAlert.setOnCheckedChangeListener((buttonView, isChecked) ->
            stateManager.setSoundAlertEnabled(isChecked));
        cbVoiceAlert.setOnCheckedChangeListener((buttonView, isChecked) ->
            stateManager.setVoiceAlertEnabled(isChecked));
    }

    private void startCamera() {
        cameraManager.startCamera(
            this::analyzeImage,
            this::onCameraReady,
            error -> updateStatus("相機初始化失敗", 0xFFE53935, "--"));
    }

    private void onCameraReady() {
        detectionPipeline = new DetectionPipeline(this);
        updateStatus("系統就緒", 0xFF4CAF50, "--");
    }

    private void analyzeImage(ImageProxy imageProxy) {
        Bitmap bitmap = null;
        Bitmap processedBitmap = null;
        try {
            if (detectionPipeline == null) {
                return;
            }

            bitmap = imageProxy.toBitmap();
            if (bitmap == null) {
                return;
            }

            processedBitmap = rotateBitmapIfNeeded(bitmap, imageProxy.getImageInfo().getRotationDegrees());
            DetectionFrameResult frameResult =
                detectionPipeline.analyze(processedBitmap, stateManager.getState());

            mainHandler.post(() -> updateUi(frameResult));
        } catch (Exception e) {
            Log.e(TAG, "Image analysis failed", e);
        } finally {
            if (processedBitmap != null && processedBitmap != bitmap) {
                processedBitmap.recycle();
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
            imageProxy.close();
        }
    }

    private Bitmap rotateBitmapIfNeeded(Bitmap bitmap, int rotation) {
        if (rotation == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void updateUi(DetectionFrameResult frameResult) {
        SafetyStatus status = frameResult.getSafetyStatus();
        overlayView.setImageSize(frameResult.getImageWidth(), frameResult.getImageHeight());
        overlayView.setDetections(frameResult.getDisplayResults(),
            frameResult.getTrafficLightResults());
        updateStatus(status);

        if (status.level != SafetyLevel.SAFE) {
            alertSystem.triggerAlert(status, stateManager.getState());
        }
    }

    private void updateStatus(SafetyStatus status) {
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

    private void updateStatus(String message, int color, String distance) {
        tvStatus.setText(message);
        tvStatus.setTextColor(color);
        tvDistance.setText("距離: " + distance);
    }

    private void calculateFps() {
        long now = System.currentTimeMillis();
        frameCount++;

        if (fpsUpdateTime == 0) {
            fpsUpdateTime = now;
            return;
        }

        if (now - fpsUpdateTime >= 1000) {
            float currentFps = frameCount * 1000f / (now - fpsUpdateTime);
            frameCount = 0;
            fpsUpdateTime = now;
            Log.d(TAG, "FPS: " + currentFps);
        }
    }

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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (cameraManager.isCameraPermissionResult(requestCode)) {
            if (cameraManager.allPermissionsGranted()) {
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
        if (cameraManager != null) {
            cameraManager.release();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (alertSystem != null) {
            alertSystem.shutdown();
        }
        if (detectionPipeline != null) {
            detectionPipeline.close();
        }
    }
}
