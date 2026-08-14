package com.pksin.intervalometer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private DrawerLayout drawerLayout;
    private TextView tvStatus, tvTotalDuration, tvVideoLength, tvEndTime, tvSecondaryStatus;
    private MaterialButton btnConnectDrawer, btnShoot, btnSingleShot;
    private NumberPicker npInterval, npStartDelay, npShutterDuration, npPhotoCount, npFps;
    private MaterialSwitch swAutoReconnect;

    private IntervalService intervalService;
    private boolean isBound = false;
    private boolean lastConnectionFailed = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IntervalService.LocalBinder binder = (IntervalService.LocalBinder) service;
            intervalService = binder.getService();
            isBound = true;
            
            intervalService.setUiCallback(new IntervalService.ServiceCallback() {
                @Override
                public void onStatusUpdate(String status, boolean isConnected) {
                    updateUiState(status, isConnected);
                }

                @Override
                public void onIntervalometerStopped() {
                    runOnUiThread(() -> updateIntervalometerButton(false));
                }
            });
            
            runOnUiThread(() -> {
                updateIntervalometerButton(intervalService.isIntervalometerRunning());
                updateUiState(intervalService.getLastStatus(), intervalService.isConnected());
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupPickers();
        setupListeners();
        setupBackHandler();

        if (hasPermissions()) {
            startAndBindService();
            checkBatteryOptimization();
        } else {
            requestBlePermissions();
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        tvStatus = findViewById(R.id.tvStatus);
        tvTotalDuration = findViewById(R.id.tvTotalDuration);
        tvVideoLength = findViewById(R.id.tvVideoLength);
        tvEndTime = findViewById(R.id.tvEndTime);
        tvSecondaryStatus = findViewById(R.id.tvSecondaryStatus);
        
        btnConnectDrawer = findViewById(R.id.btnConnectDrawer);
        btnShoot = findViewById(R.id.btnShoot);
        btnSingleShot = findViewById(R.id.btnSingleShot);
        
        npInterval = findViewById(R.id.npInterval);
        npStartDelay = findViewById(R.id.npStartDelay);
        npShutterDuration = findViewById(R.id.npShutterDuration);
        npPhotoCount = findViewById(R.id.npPhotoCount);
        npFps = findViewById(R.id.npFps);
        
        swAutoReconnect = findViewById(R.id.swAutoReconnect);

        findViewById(R.id.toolbar).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.btnInstructions).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));
    }

    private void setupPickers() {
        npInterval.setMinValue(1);
        npInterval.setMaxValue(3600);
        npInterval.setValue(5);
        
        npStartDelay.setMinValue(0);
        npStartDelay.setMaxValue(300);
        npStartDelay.setValue(5);
        
        npShutterDuration.setMinValue(100);
        npShutterDuration.setMaxValue(5000);
        npShutterDuration.setValue(200);
        
        npPhotoCount.setMinValue(0);
        npPhotoCount.setMaxValue(9999);
        npPhotoCount.setValue(100);
        
        npFps.setMinValue(1);
        npFps.setMaxValue(120);
        npFps.setValue(30);

        NumberPicker.OnValueChangeListener calcListener = (picker, oldVal, newVal) -> calculateResults();
        npInterval.setOnValueChangedListener(calcListener);
        npStartDelay.setOnValueChangedListener(calcListener);
        npPhotoCount.setOnValueChangedListener(calcListener);
        npFps.setOnValueChangedListener(calcListener);

        enableDirectInput(npInterval);
        enableDirectInput(npStartDelay);
        enableDirectInput(npShutterDuration);
        enableDirectInput(npPhotoCount);
        enableDirectInput(npFps);
    }

    private void enableDirectInput(NumberPicker picker) {
        picker.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        int totalChildrenCount = picker.getChildCount();
        for (int i = 0; i < totalChildrenCount; i++) {
            View child = picker.getChildAt(i);
            if (child instanceof EditText) {
                child.setFocusable(true);
                child.setFocusableInTouchMode(true);
            }
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, IntervalService.class);
        startForegroundService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void checkBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.battery_title)
                    .setMessage(R.string.battery_msg)
                    .setPositiveButton(R.string.btn_configure, (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.btn_ignore, null)
                    .show();
        }
    }

    private void setupListeners() {
        btnConnectDrawer.setOnClickListener(v -> {
            if (!isBound) return;
            if (btnConnectDrawer.getText().toString().contains(getString(R.string.btn_disconnect))) {
                intervalService.disconnect();
            } else {
                if (hasPermissions()) {
                    btnConnectDrawer.setText(R.string.btn_connecting);
                    intervalService.connect();
                    lastConnectionFailed = false;
                } else {
                    requestBlePermissions();
                }
            }
        });

        btnShoot.setOnClickListener(v -> toggleIntervalometer());

        btnSingleShot.setOnClickListener(v -> {
            if (isBound) intervalService.triggerSingleShot(npShutterDuration.getValue());
        });
        
        findViewById(R.id.btnExit).setOnClickListener(v -> {
            if (isBound) intervalService.stopServiceCompletely();
            finishAndRemoveTask();
        });
        
        calculateResults();
    }

    private void calculateResults() {
        int interval = npInterval.getValue();
        int delay = npStartDelay.getValue();
        int count = npPhotoCount.getValue();
        int fps = npFps.getValue();

        if (interval < 2) {
            tvSecondaryStatus.setText(R.string.msg_busy_warning);
        } else {
            tvSecondaryStatus.setText(R.string.label_ready);
        }

        if (count == 0) {
            tvTotalDuration.setText(R.string.label_time_infinite);
            tvVideoLength.setText(R.string.label_video_unknown);
            tvEndTime.setText(R.string.label_est_never);
            return;
        }

        long totalSec = delay + (long) (count - 1) * interval;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        
        tvTotalDuration.setText(getString(R.string.label_time, hours, mins, secs));

        double videoSec = (double) count / fps;
        tvVideoLength.setText(getString(R.string.label_video_length, videoSec));

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, (int) totalSec);
        tvEndTime.setText(getString(R.string.label_est_end, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
    }

    private void toggleIntervalometer() {
        if (!isBound) return;
        
        if (intervalService.isIntervalometerRunning()) {
            intervalService.stopIntervalometer();
            updateIntervalometerButton(false);
        } else {
            intervalService.startIntervalometer(
                    npInterval.getValue(),
                    npPhotoCount.getValue(),
                    npShutterDuration.getValue(),
                    npStartDelay.getValue(),
                    swAutoReconnect.isChecked()
            );
            updateIntervalometerButton(true);
        }
    }

    private void updateIntervalometerButton(boolean isRunning) {
        btnShoot.setText(isRunning ? R.string.btn_stop : R.string.btn_start);
        btnShoot.setBackgroundTintList(ContextCompat.getColorStateList(this, 
                isRunning ? R.color.bogart_burgundy : R.color.muted_green));
    }

    private boolean hasPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestBlePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGrantedResult = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGrantedResult = false;
                    break;
                }
            }
            if (allGrantedResult) {
                startAndBindService();
                checkBatteryOptimization();
            } else {
                Toast.makeText(this, R.string.msg_permissions_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    public void updateUiState(String statusText, boolean isConnected) {
        runOnUiThread(() -> {
            tvStatus.setText(getString(R.string.label_status, statusText));
            
            // Handle RETRY logic
            if (!isConnected && (statusText.toLowerCase().contains("error") || statusText.toLowerCase().contains("not found"))) {
                lastConnectionFailed = true;
            }

            boolean isRunning = isBound && intervalService.isIntervalometerRunning();
            btnShoot.setEnabled(isConnected || isRunning);
            
            // BLOCK Photo button while intervalometer is running
            btnSingleShot.setEnabled(isConnected && !isRunning);
            
            updateIntervalometerButton(isRunning);

            String connectBtnText = isConnected ? getString(R.string.btn_disconnect) : 
                                   (lastConnectionFailed ? getString(R.string.btn_retry) : getString(R.string.btn_connect));
            
            // If currently connecting (and not yet connected), don't overwrite the "CONNECTING..." text yet
            if (btnConnectDrawer.getText().toString().equals(getString(R.string.btn_connecting)) && !isConnected && !lastConnectionFailed) {
                connectBtnText = getString(R.string.btn_connecting);
            }

            btnConnectDrawer.setText(connectBtnText);
            btnConnectDrawer.setBackgroundTintList(ContextCompat.getColorStateList(this, 
                    isConnected ? R.color.bogart_burgundy : R.color.bogart_tan));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }

    private void setupBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END);
                } else {
                    setEnabled(false);
                    MainActivity.super.onBackPressed();
                }
            }
        });
    }
}