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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.os.Build;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BleManager {

    private static final String TAG = "BleManager";
    private static final String PREF_NAME = "CanonRemotePrefs";
    private static final String KEY_MAC = "camera_mac";

    public static final UUID SERVICE_UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821");
    public static final UUID PAIRING_CHAR_UUID = UUID.fromString("00050002-0000-1000-0000-d8492fffa821");
    public static final UUID SHUTTER_CHAR_UUID = UUID.fromString("00050003-0000-1000-0000-d8492fffa821");

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private boolean isReconnecting = false;
    private BleCallback callback;

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);

                if (device != null && bluetoothGatt != null && device.getAddress().equals(bluetoothGatt.getDevice().getAddress())) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        Log.d(TAG, "Bonded successfully. Discovering services...");
                        mainHandler.postDelayed(() -> {
                            if (bluetoothGatt != null) bluetoothGatt.discoverServices();
                        }, 600);
                    } else if (bondState == BluetoothDevice.BOND_NONE) {
                        Log.w(TAG, "Bonding failed or removed.");
                        updateStatus("Bonding failed. Reset camera BT settings.", false);
                    }
                }
            }
        }
    };

    private Runnable connectTimeoutRunnable = () -> {
        Log.w(TAG, "GATT connection timeout!");
        updateStatus("Connection timeout. Try again.", false);
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
        context.registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    public void connectToCamera() {
        if (!bluetoothAdapter.isEnabled()) {
            updateStatus("Enable Bluetooth!", false);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedMac = prefs.getString(KEY_MAC, null);

        if (savedMac != null) {
            updateStatus("Waiting for camera signal...", false);
            if (isReconnecting) return;
            isReconnecting = true;

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder().setDeviceAddress(savedMac).build());

            bluetoothLeScanner.startScan(filters, settings, reconnectScanCallback);

            mainHandler.postDelayed(() -> {
                if (isReconnecting) {
                    isReconnecting = false;
                    bluetoothLeScanner.stopScan(reconnectScanCallback);
                    updateStatus("Camera not found.", false);
                }
            }, 20000);
        } else {
            startScan();
        }
    }

    private final ScanCallback reconnectScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (isReconnecting) {
                isReconnecting = false;
                bluetoothLeScanner.stopScan(this);
                updateStatus("Camera found, connecting...", false);
                connectToDevice(result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isReconnecting = false;
            updateStatus("Scan error: " + errorCode, false);
        }
    };

    public void forgetCamera() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String mac = prefs.getString(KEY_MAC, null);
        if (mac != null) {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
            unpairDevice(device);
        }
        prefs.edit().remove(KEY_MAC).apply();
    }

    private void unpairDevice(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            Log.e(TAG, "Removing bond failed", e);
        }
    }

    private void startScan() {
        if (bluetoothLeScanner == null || isScanning) return;

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        updateStatus("Searching for camera (Pairing mode)...", false);
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
        if (bluetoothGatt == null && !isReconnecting) {
            updateStatus("Camera not found", false);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, "Camera found: " + device.getAddress());
            stopScan();
            connectToDevice(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            updateStatus("Scan error: " + errorCode, false);
            isScanning = false;
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        closeGatt();

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MAC, device.getAddress()).apply();

        mainHandler.removeCallbacks(connectTimeoutRunnable);
        mainHandler.postDelayed(connectTimeoutRunnable, 20000);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "GATT Connected. Waiting for encryption...");
                    updateStatus("Establishing link...", false);
                    
                    mainHandler.postDelayed(() -> {
                        if (bluetoothGatt != null) {
                            if (bluetoothGatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                                Log.d(TAG, "Initiating system bond...");
                                bluetoothGatt.getDevice().createBond();
                            } else {
                                Log.d(TAG, "Already bonded, discovering services...");
                                bluetoothGatt.discoverServices();
                            }
                        }
                    }, 1000);
                    
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    updateStatus("Disconnected", false);
                    closeGatt();
                }
            } else {
                Log.e(TAG, "GATT error: " + status);
                updateStatus("GATT error: " + status, false);
                closeGatt();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    Log.d(TAG, "Canon service found. Identifying phone...");
                    updateStatus("Identifying phone...", false);
                    pairCamera("IOM " + Build.MODEL);
                } else {
                    updateStatus("Service not found", false);
                    closeGatt();
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().equals(PAIRING_CHAR_UUID)) {
                    Log.d(TAG, "Phone identified by camera!");
                    updateStatus("CONNECTED! Ready to shoot.", true);
                } else if (characteristic.getUuid().equals(SHUTTER_CHAR_UUID)) {
                    Log.d(TAG, "Trigger sent");
                }
            }
        }
    };

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
            if (bluetoothGatt != null) {
                bluetoothGatt.writeCharacteristic(triggerChar);
            }
        }, 200);
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    public void onDestroy() {
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (Exception ignored) {}
        closeGatt();
    }

    private void closeGatt() {
        if (bluetoothGatt != null) {
            try {
                refreshDeviceCache(bluetoothGatt);
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Cleanup error", e);
            }
            bluetoothGatt = null;
        }
    }

    private boolean refreshDeviceCache(BluetoothGatt gatt) {
        try {
            Method localMethod = gatt.getClass().getMethod("refresh", (Class[]) null);
            if (localMethod != null) {
                return (Boolean) localMethod.invoke(gatt, (Object[]) null);
            }
        } catch (Exception localException) {
            Log.e(TAG, "Cache refresh failed");
        }
        return false;
    }

    private void updateStatus(String status, boolean isConnected) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onStatusUpdate(status, isConnected);
            }
        });
    }
}