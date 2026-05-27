package com.sharn.trafficsafety;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.Size;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;

/**
 * 封裝 CameraX 初始化、預覽設定與相機權限。
 */
public final class CameraManager {
    public static final int PERMISSION_REQUEST_CODE = 1001;

    private static final String TAG = "TrafficSafetyCamera";
    private static final Size TARGET_RESOLUTION = new Size(1920, 1080);
    private static final float TARGET_ZOOM_RATIO = 1.5f;

    private final AppCompatActivity activity;
    private final PreviewView previewView;
    private final ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    public interface CameraReadyCallback {
        void onCameraReady();
    }

    public interface CameraErrorCallback {
        void onCameraError(Exception error);
    }

    public CameraManager(AppCompatActivity activity,
                         PreviewView previewView,
                         ExecutorService cameraExecutor) {
        this.activity = activity;
        this.previewView = previewView;
        this.cameraExecutor = cameraExecutor;
    }

    public boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    public void requestCameraPermission() {
        ActivityCompat.requestPermissions(activity,
            new String[]{Manifest.permission.CAMERA},
            PERMISSION_REQUEST_CODE);
    }

    public boolean isCameraPermissionResult(int requestCode) {
        return requestCode == PERMISSION_REQUEST_CODE;
    }

    public void startCamera(ImageAnalysis.Analyzer analyzer,
                            CameraReadyCallback readyCallback,
                            CameraErrorCallback errorCallback) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(activity);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                this.cameraProvider = cameraProvider;
                bindCameraUseCases(cameraProvider, analyzer);
                if (readyCallback != null) {
                    readyCallback.onCameraReady();
                }
            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
                if (errorCallback != null) {
                    errorCallback.onCameraError(e);
                }
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider,
                                    ImageAnalysis.Analyzer analyzer) {
        Preview preview = new Preview.Builder()
            .setTargetResolution(TARGET_RESOLUTION)
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(TARGET_RESOLUTION)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        imageAnalysis.setAnalyzer(cameraExecutor, analyzer);

        cameraProvider.unbindAll();
        Camera camera = cameraProvider.bindToLifecycle(
            activity,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis);

        CameraControl cameraControl = camera.getCameraControl();
        ZoomState currentZoom = camera.getCameraInfo().getZoomState().getValue();
        if (currentZoom != null) {
            float targetZoom = Math.min(TARGET_ZOOM_RATIO, currentZoom.getMaxZoomRatio());
            cameraControl.setZoomRatio(targetZoom);
            Log.d(TAG, "Camera zoom set to: " + targetZoom + "x");
        }
    }

    public void release() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
    }
}
