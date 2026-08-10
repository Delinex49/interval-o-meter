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
import android.widget.ImageButton;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private DrawerLayout drawerLayout;
    private TextView tvStatus, tvDrawerStatus, tvTotalDuration, tvVideoLength, tvEndTime, tvBusyWarning;
    private MaterialButton btnConnectDrawer, btnShoot, btnSingleShot, btnExit;
    private ImageButton btnInstructions;
    private NumberPicker npInterval, npStartDelay, npShutterDuration, npPhotoCount, npFps;
    private MaterialSwitch swAutoReconnect;

    private IntervalService intervalService;
    private boolean isBound = false;

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
        tvDrawerStatus = findViewById(R.id.tvDrawerStatus);
        tvTotalDuration = findViewById(R.id.tvTotalDuration);
        tvVideoLength = findViewById(R.id.tvVideoLength);
        tvEndTime = findViewById(R.id.tvEndTime);
        tvBusyWarning = findViewById(R.id.tvBusyWarning);
        
        btnConnectDrawer = findViewById(R.id.btnConnectDrawer);
        btnShoot = findViewById(R.id.btnShoot);
        btnSingleShot = findViewById(R.id.btnSingleShot);
        btnExit = findViewById(R.id.btnExit);
        btnInstructions = findViewById(R.id.btnInstructions);
        
        npInterval = findViewById(R.id.npInterval);
        npStartDelay = findViewById(R.id.npStartDelay);
        npShutterDuration = findViewById(R.id.npShutterDuration);
        npPhotoCount = findViewById(R.id.npPhotoCount);
        npFps = findViewById(R.id.npFps);
        
        swAutoReconnect = findViewById(R.id.swAutoReconnect);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        btnInstructions.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));
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
        int count = picker.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = picker.getChildAt(i);
            if (child instanceof EditText) {
                child.setFocusable(true);
                child.setFocusableInTouchMode(true);
            }
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, IntervalService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle("Battery Optimization")
                        .setMessage("To keep the intervalometer stable when the screen is off, please set battery usage to 'Unrestricted'.")
                        .setPositiveButton("Configure", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Ignore", null)
                        .show();
            }
        }
    }

    private void setupListeners() {
        btnConnectDrawer.setOnClickListener(v -> {
            if (!isBound) return;
            if (btnConnectDrawer.getText().toString().contains("DISCONNECT")) {
                intervalService.disconnect();
            } else {
                if (hasPermissions()) {
                    intervalService.connect();
                } else {
                    requestBlePermissions();
                }
            }
        });

        btnShoot.setOnClickListener(v -> toggleIntervalometer());

        btnSingleShot.setOnClickListener(v -> {
            if (isBound) intervalService.triggerSingleShot(npShutterDuration.getValue());
        });
        
        btnExit.setOnClickListener(v -> {
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

        tvBusyWarning.setVisibility(interval < 2 ? View.VISIBLE : View.GONE);

        if (count == 0) {
            tvTotalDuration.setText("Shooting Time: Infinite");
            tvVideoLength.setText("Video Length: Unknown");
            tvEndTime.setText("Estimated End: Never");
            return;
        }

        long totalSec = delay + (long) (count - 1) * interval;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        
        tvTotalDuration.setText(String.format(Locale.US, "Shooting Time: %02d:%02d:%02d", hours, mins, secs));

        double videoSec = (double) count / fps;
        tvVideoLength.setText(String.format(Locale.US, "Video Length: %.1fs", videoSec));

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, (int) totalSec);
        tvEndTime.setText(String.format(Locale.US, "Estimated End: %02d:%02d", 
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
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
        btnShoot.setText(isRunning ? "STOP" : "START");
        btnShoot.setBackgroundTintList(ContextCompat.getColorStateList(this, 
                isRunning ? android.R.color.holo_red_dark : android.R.color.holo_green_dark));
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
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startAndBindService();
                checkBatteryOptimization();
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void updateUiState(String statusText, boolean isConnected) {
        runOnUiThread(() -> {
            tvStatus.setText("Status: " + statusText);
            tvDrawerStatus.setText("Status: " + statusText);
            
            btnShoot.setEnabled(isConnected || (isBound && intervalService.isIntervalometerRunning()));
            btnSingleShot.setEnabled(isConnected);
            
            btnConnectDrawer.setText(isConnected ? "DISCONNECT" : "CONNECT / WAKE");
            btnConnectDrawer.setBackgroundTintList(ContextCompat.getColorStateList(this, 
                    isConnected ? android.R.color.holo_red_dark : R.color.primary));
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
                    onBackPressed();
                }
            }
        });
    }
}