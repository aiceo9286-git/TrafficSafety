package com.sharn.trafficsafety;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Processes a video asset frame-by-frame and runs object detection on sampled frames.
 */
public class VideoProcessor {
    private static final String TAG = "VideoProcessor";
    private static final long DEFAULT_FRAME_INTERVAL_MS = 500;

    private final Context context;
    private final ObjectDetectorWrapper detector;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public VideoProcessor(Context context) {
        this.context = context.getApplicationContext();
        this.detector = new ObjectDetectorWrapper(this.context);
    }

    public void processTestVideo(Callback callback) {
        processAssetVideo("test_video.mp4", DEFAULT_FRAME_INTERVAL_MS, true, callback);
    }

    public void processAssetVideo(
        String assetName,
        long frameIntervalMs,
        boolean enhanceLowLight,
        Callback callback
    ) {
        if (frameIntervalMs <= 0) {
            notifyError(callback, new IllegalArgumentException("frameIntervalMs must be greater than 0"));
            return;
        }

        cancelled.set(false);

        if (!detector.isModelLoaded()) {
            notifyError(callback, new IllegalStateException("Object detection model is not loaded"));
            return;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Map<String, Integer> classCounts = new LinkedHashMap<>();
        int processedFrames = 0;
        long durationMs = 0;
        long startedAt = System.currentTimeMillis();

        try (AssetFileDescriptor afd = context.getAssets().openFd(assetName)) {
            retriever.setDataSource(
                afd.getFileDescriptor(),
                afd.getStartOffset(),
                afd.getDeclaredLength()
            );

            durationMs = readDurationMs(retriever);
            if (callback != null) {
                callback.onStarted(durationMs, frameIntervalMs);
            }

            for (long positionMs = 0;
                 positionMs <= durationMs && !cancelled.get();
                 positionMs += frameIntervalMs) {
                Bitmap rawFrame = null;
                Bitmap detectionFrame = null;

                try {
                    rawFrame = retriever.getFrameAtTime(
                        positionMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    );
                    if (rawFrame == null) {
                        continue;
                    }

                    detectionFrame = enhanceLowLight ? enhanceForNightScene(rawFrame) : rawFrame;
                    List<DetectionResult> detections = detector.detect(detectionFrame);
                    updateClassCounts(classCounts, detections);
                    processedFrames++;

                    if (callback != null) {
                        callback.onFrameProcessed(new VideoFrameResult(
                            processedFrames,
                            positionMs,
                            rawFrame.getWidth(),
                            rawFrame.getHeight(),
                            detections,
                            detectionFrame.copy(Bitmap.Config.ARGB_8888, false),
                            new LinkedHashMap<>(classCounts)
                        ));
                    }
                } finally {
                    if (detectionFrame != null && detectionFrame != rawFrame) {
                        detectionFrame.recycle();
                    }
                    if (rawFrame != null) {
                        rawFrame.recycle();
                    }
                }
            }

            if (callback != null) {
                long elapsedMs = System.currentTimeMillis() - startedAt;
                callback.onCompleted(new VideoSummary(
                    durationMs,
                    processedFrames,
                    elapsedMs,
                    cancelled.get(),
                    new LinkedHashMap<>(classCounts)
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Video processing failed", e);
            notifyError(callback, e);
        } finally {
            retriever.release();
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void close() {
        cancel();
        detector.close();
    }

    private long readDurationMs(MediaMetadataRetriever retriever) {
        String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration == null) {
            return 0;
        }
        try {
            return Long.parseLong(duration);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Bitmap enhanceForNightScene(Bitmap source) {
        Bitmap enhanced = Bitmap.createBitmap(
            source.getWidth(),
            source.getHeight(),
            Bitmap.Config.ARGB_8888
        );

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.15f);

        ColorMatrix contrast = new ColorMatrix(new float[] {
            1.25f, 0, 0, 0, 18,
            0, 1.25f, 0, 0, 18,
            0, 0, 1.25f, 0, 18,
            0, 0, 0, 1, 0
        });
        saturation.postConcat(contrast);

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(saturation));

        Canvas canvas = new Canvas(enhanced);
        canvas.drawBitmap(source, 0, 0, paint);
        return enhanced;
    }

    private void updateClassCounts(Map<String, Integer> classCounts, List<DetectionResult> detections) {
        for (DetectionResult detection : detections) {
            String label = LabelUtils.getChineseLabel(detection.getLabel());
            Integer count = classCounts.get(label);
            classCounts.put(label, count == null ? 1 : count + 1);
        }
    }

    private void notifyError(Callback callback, Exception e) {
        if (callback != null) {
            callback.onError(e);
        }
    }

    public interface Callback {
        void onStarted(long durationMs, long frameIntervalMs);

        void onFrameProcessed(VideoFrameResult frameResult);

        void onCompleted(VideoSummary summary);

        void onError(Exception error);
    }

    public static final class VideoFrameResult {
        private final int frameIndex;
        private final long timestampMs;
        private final int frameWidth;
        private final int frameHeight;
        private final List<DetectionResult> detections;
        private final Bitmap previewBitmap;
        private final Map<String, Integer> classCounts;

        private VideoFrameResult(
            int frameIndex,
            long timestampMs,
            int frameWidth,
            int frameHeight,
            List<DetectionResult> detections,
            Bitmap previewBitmap,
            Map<String, Integer> classCounts
        ) {
            this.frameIndex = frameIndex;
            this.timestampMs = timestampMs;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.detections = Collections.unmodifiableList(new ArrayList<>(detections));
            this.previewBitmap = previewBitmap;
            this.classCounts = Collections.unmodifiableMap(new LinkedHashMap<>(classCounts));
        }

        public int getFrameIndex() {
            return frameIndex;
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public int getFrameWidth() {
            return frameWidth;
        }

        public int getFrameHeight() {
            return frameHeight;
        }

        public List<DetectionResult> getDetections() {
            return detections;
        }

        public Bitmap getPreviewBitmap() {
            return previewBitmap;
        }

        public Map<String, Integer> getClassCounts() {
            return classCounts;
        }
    }

    public static final class VideoSummary {
        private final long durationMs;
        private final int processedFrames;
        private final long elapsedMs;
        private final boolean cancelled;
        private final Map<String, Integer> classCounts;

        private VideoSummary(
            long durationMs,
            int processedFrames,
            long elapsedMs,
            boolean cancelled,
            Map<String, Integer> classCounts
        ) {
            this.durationMs = durationMs;
            this.processedFrames = processedFrames;
            this.elapsedMs = elapsedMs;
            this.cancelled = cancelled;
            this.classCounts = Collections.unmodifiableMap(new LinkedHashMap<>(classCounts));
        }

        public long getDurationMs() {
            return durationMs;
        }

        public int getProcessedFrames() {
            return processedFrames;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public Map<String, Integer> getClassCounts() {
            return classCounts;
        }
    }
}
