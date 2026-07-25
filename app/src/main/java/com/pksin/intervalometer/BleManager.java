package com.pksin.intervalometer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic; // <-- ДОБАВЛЕН ИМПОРТ
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BleManager {

    private static final String TAG = "BleManager";

    // UUID из репозитория Canon
    public static final UUID SERVICE_UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821");
    public static final UUID PAIRING_CHAR_UUID = UUID.fromString("00050002-0000-1000-0000-d8492fffa821");
    public static final UUID SHUTTER_CHAR_UUID = UUID.fromString("00050003-0000-1000-0000-d8492fffa821");

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private BleCallback callback;

    public interface BleCallback {
        void onStatusUpdate(String status, boolean isConnected);
    }

    public BleManager(Context context, BleCallback callback) {
        this.context = context;
        this.callback = callback;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    public void startScan() {
        if (bluetoothLeScanner == null || !bluetoothAdapter.isEnabled()) {
            updateStatus("Включите Bluetooth!", false);
            return;
        }
        if (isScanning) return;

        // Фильтруем устройства: ищем только те, что раздают сервис Canon
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        updateStatus("Поиск камеры...", false);
        isScanning = true;
        bluetoothLeScanner.startScan(filters, settings, scanCallback);

        // Таймаут сканирования (15 секунд)
        mainHandler.postDelayed(this::stopScan, 15000);
    }

    public void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        if (bluetoothLeScanner != null && bluetoothAdapter.isEnabled()) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        if (bluetoothGatt == null) {
            updateStatus("Камера не найдена", false);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, "Найдена камера Canon: " + device.getAddress());

            stopScan(); // Как только нашли — прекращаем эфирный мусор
            connectToDevice(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            updateStatus("Ошибка сканирования: " + errorCode, false);
            isScanning = false;
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        updateStatus("Подключение к камере...", false);
        // autoConnect = false для быстрого прямого подключения
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Подключено к камере. Поиск сервисов...");
                    updateStatus("Открытие сервисов...", false);
                    gatt.discoverServices(); // Обязательный шаг для работы с характеристиками
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    updateStatus("Отключено", false);
                    closeGatt();
                }
            } else {
                updateStatus("Ошибка GATT подключения: " + status, false);
                closeGatt();
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    Log.d(TAG, "Главный сервис Canon найден!");
                    updateStatus("Авторизация на камере...", false);

                    // Вызываем метод сопряжения!
                    // Камера увидит этот пульт под именем "AppRemote"
                    pairCamera("AppRemote");
                } else {
                    updateStatus("Сервис Canon не найден", false);
                    closeGatt();
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().equals(PAIRING_CHAR_UUID)) {
                    Log.d(TAG, "Сопряжение успешно завершено!");
                    updateStatus("ПОДКЛЮЧЕНО! Можно снимать.", true);
                } else if (characteristic.getUuid().equals(SHUTTER_CHAR_UUID)) {
                    Log.d(TAG, "Пакет затвора доставлен");
                }
            } else {
                Log.e(TAG, "Ошибка записи характеристики: " + status);
            }
        }
    }; // <-- УБРАНА ЛИШНЯЯ СКОБКА ЗДЕСЬ

    // Метод сопряжения: отправляем имя устройства с префиксом 0x03
    @SuppressLint("MissingPermission")
    private void pairCamera(String deviceName) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic pairChar = service.getCharacteristic(PAIRING_CHAR_UUID);
        if (pairChar == null) return;

        // Логика из C++: строка " ИМЯ_ПУЛЬТА ", где первый пробел заменяется на байт 0x03
        String nameStr = " " + deviceName + " ";
        byte[] payload = nameStr.getBytes();
        payload[0] = 0x03;

        pairChar.setValue(payload);
        bluetoothGatt.writeCharacteristic(pairChar);
        Log.d(TAG, "Отправлена команда сопряжения: " + deviceName);
    }

    // Метод спуска затвора
    @SuppressLint("MissingPermission")
    public void triggerShoot() {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic triggerChar = service.getCharacteristic(SHUTTER_CHAR_UUID);
        if (triggerChar == null) return;

        // Нажатие кнопки (BUTTON_RELEASE 0x80 | MODE_IMMEDIATE 0x0C) = 0x8C
        byte[] pressPayload = {(byte) 0x8C};
        triggerChar.setValue(pressPayload);
        bluetoothGatt.writeCharacteristic(triggerChar);

        // Отпускание кнопки через 200 мс (MODE_IMMEDIATE 0x0C)
        mainHandler.postDelayed(() -> {
            byte[] releasePayload = {(byte) 0x0C};
            triggerChar.setValue(releasePayload);
            bluetoothGatt.writeCharacteristic(triggerChar);
        }, 200);
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    private void closeGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    private void updateStatus(String status, boolean isConnected) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onStatusUpdate(status, isConnected);
            }
        });
    }
}