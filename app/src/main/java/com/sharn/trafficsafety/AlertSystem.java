package com.sharn.trafficsafety;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * 統一管理警示音效、語音與震動。
 */
public final class AlertSystem {
    private static final String TAG = "AlertSystem";
    private static final long ALERT_COOLDOWN = 800;
    private static final long ALERT_COOLDOWN_DANGER = 400;

    private final Handler mainHandler;
    private final Vibrator vibrator;
    private MediaPlayer mediaPlayer;
    private TextToSpeech textToSpeech;
    private long lastAlertTime = 0;

    public AlertSystem(Context context, Handler mainHandler) {
        this.mainHandler = mainHandler;
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        textToSpeech = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.TRADITIONAL_CHINESE);
                textToSpeech.setSpeechRate(1.1f);
            }
        });

        mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI);
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
        }
    }

    public void triggerAlert(SafetyStatus status, AppState appState) {
        long now = SystemClock.elapsedRealtime();
        long cooldown = status.level == SafetyLevel.DANGER ? ALERT_COOLDOWN_DANGER : ALERT_COOLDOWN;
        if (now - lastAlertTime < cooldown) {
            return;
        }
        lastAlertTime = now;

        if (appState.isSoundAlertEnabled()) {
            playSound(status.level);
        }
        vibrate(status.level);
        if (appState.isVoiceAlertEnabled()) {
            speak(toVoiceMessage(status.message));
        }
    }

    public void speakTrafficLightWarning(String message) {
        speak(message);
    }

    private void playSound(SafetyLevel level) {
        if (mediaPlayer == null) {
            return;
        }

        switch (level) {
            case DANGER:
                startMediaPlayerIfAvailable();
                mainHandler.postDelayed(() -> startMediaPlayerIfAvailable(), 150);
                mainHandler.postDelayed(() -> startMediaPlayerIfAvailable(), 300);
                break;
            case WARNING:
                startMediaPlayerIfAvailable();
                mainHandler.postDelayed(() -> startMediaPlayerIfAvailable(), 350);
                break;
            case SAFE:
                break;
        }
    }

    private void startMediaPlayerIfAvailable() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.start();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Alert sound start failed", e);
            }
        }
    }

    private void vibrate(SafetyLevel level) {
        if (vibrator == null) {
            return;
        }

        long duration = level == SafetyLevel.DANGER ? 180L : 80L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(duration);
        }
    }

    private void speak(String message) {
        if (textToSpeech != null && message != null && !message.isEmpty()) {
            textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private String toVoiceMessage(String message) {
        return message
            .replace("⚠️", "注意，危險")
            .replace("⚡", "注意")
            .replace("✓", "")
            .replace("秒碰撞", "秒")
            .replace("米", "公尺")
            .split(",")[0];
    }

    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
