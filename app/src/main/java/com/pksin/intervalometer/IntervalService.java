package com.pksin.intervalometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class IntervalService extends Service implements BleManager.BleCallback {

    private static final String CHANNEL_ID = "IntervalometerChannel";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private BleManager bleManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private int intervalSec;
    private int photoCount;
    private int shutterDurationMs;
    private boolean autoReconnect;
    
    private int currentShot = 0;
    private boolean isIntervalometerRunning = false;
    private boolean isWaitingForReconnect = false;
    private boolean pendingShot = false;
    private String lastStatus = "Idle";
    private boolean isConnected = false;
    private ServiceCallback uiCallback;

    public interface ServiceCallback {
        void onStatusUpdate(String status, boolean isConnected);
        void onIntervalometerStopped();
    }

    public class LocalBinder extends Binder {
        IntervalService getService() {
            return IntervalService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Intervalometer:WakeLock");
        bleManager = new BleManager(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification("Service Active"));
        return START_STICKY;
    }

    public void setUiCallback(ServiceCallback callback) {
        this.uiCallback = callback;
    }

    public void connect() {
        if (bleManager != null) bleManager.connectToCamera();
    }

    public void disconnect() {
        if (bleManager != null) bleManager.disconnect();
    }

    public void triggerSingleShot(long durationMs) {
        if (bleManager != null) bleManager.triggerShoot(durationMs);
    }

    public void startIntervalometer(int interval, int count, int shutter, int delay, boolean reconnect) {
        if (isIntervalometerRunning) return;

        this.intervalSec = interval;
        this.photoCount = count;
        this.shutterDurationMs = shutter;
        this.autoReconnect = reconnect;

        isIntervalometerRunning = true;
        currentShot = 0;
        updateNotification("Starting in " + delay + "s...");
        
        if (!wakeLock.isHeld()) wakeLock.acquire(3600000);
        
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::takeNextShot, delay * 1000L);
    }

    public void stopIntervalometer() {
        isIntervalometerRunning = false;
        isWaitingForReconnect = false;
        pendingShot = false;
        handler.removeCallbacksAndMessages(null);
        if (wakeLock.isHeld()) wakeLock.release();
        updateNotification("Intervalometer Stopped");
        if (uiCallback != null) uiCallback.onIntervalometerStopped();
    }

    public void stopServiceCompletely() {
        stopIntervalometer();
        if (bleManager != null) bleManager.disconnect();
        stopForeground(true);
        stopSelf();
    }

    private void takeNextShot() {
        if (!isIntervalometerRunning) return;

        if (bleManager != null && bleManager.isConnected()) {
            isWaitingForReconnect = false;
            pendingShot = false;
            bleManager.triggerShoot(shutterDurationMs);
        } else {
            if (autoReconnect) {
                if (!isWaitingForReconnect) {
                    isWaitingForReconnect = true;
                    pendingShot = true;
                    updateNotification("Connection lost. Searching...");
                    bleManager.connectToCamera();
                }
            } else {
                stopSelfWithNotification("Connection lost. Session stopped.");
            }
        }
    }

    @Override
    public void onShutterDelivered() {
        if (!isIntervalometerRunning) return;
        
        currentShot++;
        updateNotification("Shot " + currentShot + (photoCount > 0 ? " / " + photoCount : ""));

        if (photoCount > 0 && currentShot >= photoCount) {
            stopSelfWithNotification("Session complete! " + currentShot + " photos taken.");
        } else {
            handler.postDelayed(this::takeNextShot, intervalSec * 1000L);
        }
    }

    @Override
    public void onStatusUpdate(String status, boolean isConnected) {
        this.lastStatus = status;
        this.isConnected = isConnected;
        if (uiCallback != null) uiCallback.onStatusUpdate(status, isConnected);
        
        if (isConnected && pendingShot) {
            isWaitingForReconnect = false;
            pendingShot = false;
            updateNotification("Reconnected! Firing shot...");
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::takeNextShot, 500);
        } else if (isWaitingForReconnect && isConnected) {
            isWaitingForReconnect = false;
            updateNotification("Reconnected! Resuming...");
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::takeNextShot, 1000);
        }
    }

    @Override
    public void onError(int code, String message) {
        if (isIntervalometerRunning && !autoReconnect) {
            stopSelfWithNotification("Error: " + message);
        }
    }

    private void stopSelfWithNotification(String message) {
        isIntervalometerRunning = false;
        isWaitingForReconnect = false;
        if (wakeLock.isHeld()) wakeLock.release();
        handler.removeCallbacksAndMessages(null);
        
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Session Finished")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID + 1, notification);
        
        if (uiCallback != null) uiCallback.onIntervalometerStopped();
        updateNotification("Ready");
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            this.lastStatus = text;
            manager.notify(NOTIFICATION_ID, createNotification(text));
            if (uiCallback != null) uiCallback.onStatusUpdate(text, isConnected);
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Canon Intervalometer")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(isIntervalometerRunning)
                .setPriority(isIntervalometerRunning ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Intervalometer Status",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        isIntervalometerRunning = false;
        if (wakeLock.isHeld()) wakeLock.release();
        if (bleManager != null) bleManager.onDestroy();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public boolean isIntervalometerRunning() {
        return isIntervalometerRunning;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public boolean isConnected() {
        return isConnected;
    }
}