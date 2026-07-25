package com.pksin.intervalometer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvStatus;
    private Button btnConnect;
    private Button btnShoot;

    private BleManager bleManager; // <-- ДОБАВЛЕНО

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnConnect = findViewById(R.id.btnConnect);
        btnShoot = findViewById(R.id.btnShoot);

        // <-- ДОБАВЛЕНО: Инициализация BleManager
        bleManager = new BleManager(this, (status, isConnected) -> {
            updateUiState(status, isConnected);
        });

        // <-- ОБНОВЛЕНО: Логика кнопки подключения
        btnConnect.setOnClickListener(v -> {
            if (btnConnect.getText().toString().equals("ОТКЛЮЧИТЬ")) {
                bleManager.disconnect();
            } else {
                if (hasPermissions()) {
                    bleManager.startScan();
                } else {
                    requestBlePermissions();
                }
            }
        });

        btnShoot.setOnClickListener(v -> {
            if (bleManager != null) {
                bleManager.triggerShoot();
            }
        });

        if (!hasPermissions()) {
            requestBlePermissions();
        }
    }

    // Проверка наличия всех необходимых разрешений
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    // Запрос разрешений у пользователя
    private void requestBlePermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Для Android 12+ (API 31+)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Для Android 11 и ниже (API 30-)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    // Обработка ответа пользователя на запрос разрешений
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
            if (!allGranted) {
                Toast.makeText(this, "Для работы с камерой необходимы разрешения Bluetooth/Геолокации", Toast.LENGTH_LONG).show();
            }
        }
    }

    // Публичный метод для обновления UI из BleManager
    public void updateUiState(String statusText, boolean isConnected) {
        runOnUiThread(() -> {
            tvStatus.setText(statusText);
            btnShoot.setEnabled(isConnected);
            btnConnect.setText(isConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ / РАЗБУДИТЬ");
        });
    }
}