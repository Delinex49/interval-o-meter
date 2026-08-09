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
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvStatus, tvTotalDuration, tvVideoLength, tvEndTime;
    private Button btnConnect, btnShoot, btnSingleShot, btnInstructions;
    private EditText etInterval, etStartDelay, etShutterDuration, etPhotoCount, etFps;
    private CheckBox cbAutoReconnect;

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
            
            // Sync UI state immediately upon connection
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
        setupListeners();

        if (hasPermissions()) {
            startAndBindService();
            checkBatteryOptimization();
        } else {
            requestBlePermissions();
        }
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvTotalDuration = findViewById(R.id.tvTotalDuration);
        tvVideoLength = findViewById(R.id.tvVideoLength);
        tvEndTime = findViewById(R.id.tvEndTime);
        
        btnConnect = findViewById(R.id.btnConnect);
        btnShoot = findViewById(R.id.btnShoot);
        btnSingleShot = findViewById(R.id.btnSingleShot);
        btnInstructions = findViewById(R.id.btnInstructions);
        
        etInterval = findViewById(R.id.etInterval);
        etStartDelay = findViewById(R.id.etStartDelay);
        etShutterDuration = findViewById(R.id.etShutterDuration);
        etPhotoCount = findViewById(R.id.etPhotoCount);
        etFps = findViewById(R.id.etFps);
        
        cbAutoReconnect = findViewById(R.id.cbAutoReconnect);
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
                        .setMessage("To keep the intervalometer stable when the screen is off, please set battery usage to 'Unrestricted' in the next screen.")
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
        btnConnect.setOnClickListener(v -> {
            if (!isBound) return;
            if (btnConnect.getText().toString().contains("DISCONNECT")) {
                intervalService.disconnect();
            } else {
                if (hasPermissions()) {
                    intervalService.connect();
                } else {
                    requestBlePermissions();
                }
            }
        });

        btnConnect.setOnLongClickListener(v -> {
            if (isBound && !btnConnect.getText().toString().contains("DISCONNECT")) {
                new BleManager(this, null).forgetCamera();
                Toast.makeText(this, "Camera forgotten.", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        btnShoot.setOnClickListener(v -> toggleIntervalometer());

        btnSingleShot.setOnClickListener(v -> {
            if (!isBound) return;
            try {
                long duration = Long.parseLong(etShutterDuration.getText().toString());
                intervalService.triggerSingleShot(duration);
            } catch (Exception e) {
                intervalService.triggerSingleShot(200);
            }
        });

        btnInstructions.setOnClickListener(v -> showInstructions());

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                calculateResults();
            }
        };

        etInterval.addTextChangedListener(watcher);
        etStartDelay.addTextChangedListener(watcher);
        etPhotoCount.addTextChangedListener(watcher);
        etFps.addTextChangedListener(watcher);
        
        calculateResults();
    }

    private void calculateResults() {
        try {
            double interval = Double.parseDouble(etInterval.getText().toString());
            int delay = Integer.parseInt(etStartDelay.getText().toString());
            int count = Integer.parseInt(etPhotoCount.getText().toString());
            int fps = Integer.parseInt(etFps.getText().toString());

            if (count == 0) {
                tvTotalDuration.setText("Time: Infinite");
                tvVideoLength.setText("Video: Unknown");
                tvEndTime.setText("Est. End: Never");
                return;
            }

            long totalSec = (long) (delay + (count - 1) * interval);
            long hours = totalSec / 3600;
            long mins = (totalSec % 3600) / 60;
            long secs = totalSec % 60;
            
            tvTotalDuration.setText(String.format(Locale.US, "Time: %02d:%02d:%02d", hours, mins, secs));

            double videoSec = (double) count / fps;
            tvVideoLength.setText(String.format(Locale.US, "Video: %.1fs", videoSec));

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.SECOND, (int) totalSec);
            tvEndTime.setText(String.format(Locale.US, "Est. End: %02d:%02d", 
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));

        } catch (NumberFormatException e) {
            // Ignore
        }
    }

    private void toggleIntervalometer() {
        if (!isBound) return;
        
        if (intervalService.isIntervalometerRunning()) {
            intervalService.stopIntervalometer();
            updateIntervalometerButton(false);
        } else {
            try {
                int interval = (int) Double.parseDouble(etInterval.getText().toString());
                int count = Integer.parseInt(etPhotoCount.getText().toString());
                int shutter = Integer.parseInt(etShutterDuration.getText().toString());
                int delay = Integer.parseInt(etStartDelay.getText().toString());
                boolean reconnect = cbAutoReconnect.isChecked();

                intervalService.startIntervalometer(interval, count, shutter, delay, reconnect);
                updateIntervalometerButton(true);
            } catch (Exception e) {
                Toast.makeText(this, "Invalid settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateIntervalometerButton(boolean isRunning) {
        btnShoot.setText(isRunning ? "STOP" : "START");
        btnShoot.setBackgroundTintList(ContextCompat.getColorStateList(this, 
                isRunning ? android.R.color.holo_red_dark : android.R.color.holo_green_dark));
    }

    private void showInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("PRE-FLIGHT CHECK")
                .setMessage("SETUP STEPS:\n" +
                           "1. Camera Menu: Go to Wireless Settings and PAIR with this phone.\n" +
                           "2. Camera Drive Mode: MUST be set to 'Remote Control' or 'Self-timer: 2s/remote'.\n" +
                           "3. Lens: Switch to Manual Focus (MF) for 100% reliable shooting.\n\n" +
                           "TIPS:\n" +
                           "• Shutter Duration: Increase to 500ms+ if you must use Autofocus.\n" +
                           "• Auto-Reconnect: Keeps the session alive if you restart the camera.")
                .setPositiveButton("Got it!", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
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
            } else {
                Toast.makeText(this, "Permissions required for intervalometer", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void updateUiState(String statusText, boolean isConnected) {
        runOnUiThread(() -> {
            tvStatus.setText("Status: " + statusText);
            btnShoot.setEnabled(isConnected || (isBound && intervalService.isIntervalometerRunning()));
            btnSingleShot.setEnabled(isConnected);
            btnConnect.setText(isConnected ? "DISCONNECT" : "CONNECT / WAKE");
        });
    }
}