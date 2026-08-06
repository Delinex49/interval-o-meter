package com.pksin.intervalometer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BleManager {

    private static final String TAG = "BleManager";
    private static final String PREF_NAME = "CanonRemotePrefs";
    private static final String KEY_MAC = "camera_mac";
    private boolean isReconnecting = false;
    private ScanCallback reconnectScanCallback;

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
    private Runnable connectTimeoutRunnable = () -> {
        Log.w(TAG, "Таймаут GATT подключения!");
        updateStatus("Ошибка подключения. Попробуйте еще раз.", false);
        closeGatt();
    };

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

    // НОВЫЙ МЕТОД: Умное подключение
// НОВЫЙ МЕТОД: Умное подключение с предварительным "разогревом" сканера
    public void connectToCamera() {
        if (!bluetoothAdapter.isEnabled()) {
            updateStatus("Включите Bluetooth!", false);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedMac = prefs.getString(KEY_MAC, null);

        if (savedMac != null) {
            updateStatus("Ожидание сигнала от камеры...", false);

            // Защита от двойного запуска
            if (isReconnecting) return;
            isReconnecting = true;

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            reconnectScanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (isReconnecting && result.getDevice().getAddress().equals(savedMac)) {
                        isReconnecting = false;

                        // 1. Останавливаем сканирование
                        bluetoothLeScanner.stopScan(this);
                        updateStatus("Камера найдена, подключаюсь...", false);

                        // 2. ВАЖНО: Даем аппаратному чипу 500 мс на переключение режимов!
                        mainHandler.postDelayed(() -> {
                            BluetoothDevice freshDevice = bluetoothAdapter.getRemoteDevice(savedMac);
                            new Thread(() -> {
                                connectToDevice(freshDevice);
                            }).start();
                        }, 500);
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    isReconnecting = false;
                    updateStatus("Ошибка сканирования: " + errorCode, false);
                }
            };

            // Начинаем слушать эфир
            bluetoothLeScanner.startScan(null, settings, reconnectScanCallback);

            // Таймаут: если через 15 секунд камера так и не появилась в эфире
            mainHandler.postDelayed(() -> {
                if (isReconnecting) {
                    isReconnecting = false;
                    bluetoothLeScanner.stopScan(reconnectScanCallback);
                    updateStatus("Камера не найдена. Включите её и попробуйте снова.", false);
                }
            }, 15000);

        } else {
            // Если MAC нет - запускаем полноценный скан для первого сопряжения
            startScan();
        }
    }

    // НОВЫЙ МЕТОД: Забыть камеру (для подключения другой)
    public void forgetCamera() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_MAC).apply();
    }

    private void startScan() {
        if (bluetoothLeScanner == null || isScanning) return;

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        updateStatus("Поиск камеры (Режим сопряжения)...", false);
        isScanning = true;
        bluetoothLeScanner.startScan(filters, settings, scanCallback);

        mainHandler.postDelayed(this::stopScan, 15000);
    }

    private void stopScan() {
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
            Log.d(TAG, "Найдена камера: " + device.getAddress());
            stopScan();
            connectToDevice(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            updateStatus("Ошибка сканирования: " + errorCode, false);
            isScanning = false;
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        // Обязательно закрываем прошлые зависшие соединения
        closeGatt();

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MAC, device.getAddress()).apply();

        // ВСЕГДА false, так как мы вызвались СРАЗУ ПОСЛЕ обнаружения сканером
        boolean autoConnect = false;

        mainHandler.removeCallbacks(connectTimeoutRunnable);
        // Запускаем страховочный таймер на 8 секунд
        mainHandler.postDelayed(connectTimeoutRunnable, 8000);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            mainHandler.removeCallbacks(connectTimeoutRunnable); // Снимаем страховочный таймер

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Подключено к камере. Поиск сервисов...");
                    updateStatus("Открытие сервисов...", false);
                    gatt.discoverServices();
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
                    updateStatus("Авторизация на камере...", false);

                    // Получаем модель телефона и формируем строку
                    String deviceName = "IOM " + Build.MODEL;
                    // Canon может обрезать слишком длинные имена, но обычно 20-30 символов влезает
                    pairCamera(deviceName);

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
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void pairCamera(String deviceName) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic pairChar = service.getCharacteristic(PAIRING_CHAR_UUID);
        if (pairChar == null) return;

        String nameStr = " " + deviceName + " ";
        byte[] payload = nameStr.getBytes();
        payload[0] = 0x03;

        pairChar.setValue(payload);
        bluetoothGatt.writeCharacteristic(pairChar);
    }

    @SuppressLint("MissingPermission")
    public void triggerShoot() {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic triggerChar = service.getCharacteristic(SHUTTER_CHAR_UUID);
        if (triggerChar == null) return;

        triggerChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        byte[] pressPayload = {(byte) 0x8C};
        triggerChar.setValue(pressPayload);
        bluetoothGatt.writeCharacteristic(triggerChar);

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
            try {
                bluetoothGatt.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при отключении", e);
            }
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