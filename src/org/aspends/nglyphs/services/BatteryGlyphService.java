package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;

import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphManagerV2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

class BluetoothBattery {
    int mainBattery = -1;
    int leftBattery = -1;
    int rightBattery = -1;
    int caseBattery = -1;
}

public class BatteryGlyphService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "battery_service_channel";
    private static final float ACCEL_THRESHOLD = 11.5f; // Slightly more than gravity
    private static final float Z_FACEDOWN_THRESHOLD = -8.0f;

    private SharedPreferences prefs;
    private BatteryManager batteryManager;
    private SensorManager sensorManager;
    private PowerManager powerManager;
    private boolean isFaceDown, isProximityCovered;
    private boolean isCharging;
    private float lastX, lastY, lastZ;
    private boolean hasLastAccel;
    private final android.os.Handler btHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.Map<String, Integer> btBatteryMap = new java.util.HashMap<>();
    private final java.util.List<String> btDeviceRotationList = new java.util.ArrayList<>();
    private int btRotationIndex = 0;
    private final Runnable btRotationRunnable = new Runnable() {
        @Override
        public void run() {
            if (btDeviceRotationList.isEmpty()) return;

            if (btRotationIndex >= btDeviceRotationList.size()) {
                btDeviceRotationList.clear();
                btRotationIndex = 0;
                return;
            }

            String address = btDeviceRotationList.get(btRotationIndex);
            Integer level = btBatteryMap.get(address);

            if (level != null) {
                int duration = 10000;
                AnimationManager.showBluetoothBattery(level, BatteryGlyphService.this, duration);
                btRotationIndex++;
                btHandler.postDelayed(this, duration + 500); // 500ms gap between devices
            } else {
                // Device removed? Skip to next
                btRotationIndex++;
                btHandler.post(this);
            }
        }
    };

    private final Runnable btTriggerRunnable = () -> {
        if (!btBatteryMap.isEmpty()) {
            if (prefs.getBoolean("bluetooth_battery_combine_enabled", false)) {
                int sum = 0;
                for (int level : btBatteryMap.values()) {
                    sum += level;
                }
                int average = sum / btBatteryMap.size();
                android.widget.Toast.makeText(this, "Bluetooth Battery: " + average + "% 🔋", android.widget.Toast.LENGTH_SHORT).show();
                AnimationManager.showBluetoothBattery(average, this, 10000);
            } else {
                // If a rotation is already happening, just update the map (which we did in handleBluetoothBattery)
                // If no rotation is happening, start one
                if (btDeviceRotationList.isEmpty()) {
                    btDeviceRotationList.clear();
                    btDeviceRotationList.addAll(btBatteryMap.keySet());
                    btRotationIndex = 0;
                    btHandler.removeCallbacks(btRotationRunnable);
                    btHandler.post(btRotationRunnable);
                }
            }
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                isCharging = true;
                triggerBatteryAnimation(2000);
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                isCharging = false;
                AnimationManager.cancelPriority(AnimationManager.PRIORITY_BATTERY);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            } else if ("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(action)
                    || "android.bluetooth.device.action.ACL_CONNECTED".equals(action)) {
                handleBluetoothBattery(intent);
            } else if ("android.bluetooth.device.action.ACL_DISCONNECTED".equals(action)) {
                android.bluetooth.BluetoothDevice device = getParcelableExtraCompat(intent, "android.bluetooth.device.extra.DEVICE", android.bluetooth.BluetoothDevice.class);
                
                if (device != null) {
                    String address = device.getAddress();
                    btBatteryMap.remove(address);
                    
                    // Stop any running rotation tasks to prevent "ghost" triggers
                    btHandler.removeCallbacks(btTriggerRunnable);
                    btHandler.removeCallbacks(btRotationRunnable);
                    btDeviceRotationList.clear();
                    btRotationIndex = 0;
                    
                    // If the map is empty, ensure the glyphs turn off immediately
                    if (btBatteryMap.isEmpty()) {
                        AnimationManager.cancelPriority(AnimationManager.PRIORITY_BATTERY);
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Battery Glyph",
                    NotificationManager.IMPORTANCE_MIN);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null)
                manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Battery Glyph Active")
                .setContentText("Monitoring charging and tap events")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        startForeground(4449, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        registerSensors();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        filter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        filter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
        registerReceiver(batteryReceiver, filter);
    }

    private void registerSensors() {
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (accel != null)
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
        if (prox != null)
            sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean("master_allow", false) || (!prefs.getBoolean("battery_glyph_enabled", false)
                && !prefs.getBoolean("bluetooth_battery_glyph_enabled", false))) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Initial state grab - Do NOT trigger animation here, it leads to redundant cycles
        // on every service refresh/rebind. Let the receivers handle the events.
        Intent initial = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (initial != null) {
            int status = initial.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        }

        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            isFaceDown = z < Z_FACEDOWN_THRESHOLD;

            if (hasLastAccel) {
                float deltaX = Math.abs(x - lastX);
                float deltaY = Math.abs(y - lastY);
                float deltaZ = Math.abs(z - lastZ);
                float totalDelta = deltaX + deltaY + deltaZ;

                // Threshold of 0.15 handles extremely subtle nudges/vibrations
                boolean allowWhileOn = prefs.getBoolean("battery_screen_on_enabled", false);
                if (totalDelta > 0.15f && isFaceDown && isProximityCovered && (allowWhileOn || !powerManager.isInteractive())
                        && isCharging) {
                    triggerBatteryAnimation(2000);
                } else if (!isCharging || (powerManager.isInteractive() && !allowWhileOn)) {
                    AnimationManager.cancelPriority(AnimationManager.PRIORITY_BATTERY);
                }
            }

            lastX = x;
            lastY = y;
            lastZ = z;
            hasLastAccel = true;
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            isProximityCovered = event.values[0] < event.sensor.getMaximumRange();
        }
    }

    private long lastAnimationTime = 0;
    private static final long DEBOUNCE_INTERVAL_MS = 5000;

    private void triggerBatteryAnimation(int holdMs) {
        if (prefs.getBoolean("battery_glyph_enabled", false) && isCharging) {
            long now = System.currentTimeMillis();
            if (now - lastAnimationTime > DEBOUNCE_INTERVAL_MS) {
                lastAnimationTime = now;
                int level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                AnimationManager.playBatteryAnimation(level, this, holdMs);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void handleBluetoothBattery(Intent intent) {
        if (!prefs.getBoolean("bluetooth_battery_glyph_enabled", false))
            return;

        android.bluetooth.BluetoothDevice device = getParcelableExtraCompat(intent, "android.bluetooth.device.extra.DEVICE", android.bluetooth.BluetoothDevice.class);
        if (device == null) return;

        BluetoothBattery bb = new BluetoothBattery();
        String action = intent.getAction();

        // 1. Sourcing: Standard Battery Level
        bb.mainBattery = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
        if (bb.mainBattery == -1) {
            try {
                java.lang.reflect.Method m = device.getClass().getMethod("getBatteryLevel");
                Object res = m.invoke(device);
                if (res instanceof Integer) bb.mainBattery = (Integer) res;
            } catch (Exception ignored) {}
        }

        // 2. Sourcing: Bluetooth Metadata (Reflective access for public HIDDEN constants)
        try {
            java.lang.reflect.Method getMetadata = device.getClass().getMethod("getMetadata", int.class);
            byte[] main = (byte[]) getMetadata.invoke(device, 16); // METADATA_MAIN_BATTERY
            byte[] left = (byte[]) getMetadata.invoke(device, 17); // METADATA_LEFT_BATTERY
            byte[] right = (byte[]) getMetadata.invoke(device, 18); // METADATA_RIGHT_BATTERY
            byte[] caseBatt = (byte[]) getMetadata.invoke(device, 19); // METADATA_CASE_BATTERY

            if (main != null) bb.mainBattery = Integer.parseInt(new String(main));
            if (left != null) bb.leftBattery = Integer.parseInt(new String(left));
            if (right != null) bb.rightBattery = Integer.parseInt(new String(right));
            if (caseBatt != null) bb.caseBattery = Integer.parseInt(new String(caseBatt));
        } catch (Exception ignored) {}

        // 3. Sourcing: Vendor Broadcasts (Common extras)
        if (intent.hasExtra("leftBattery")) bb.leftBattery = intent.getIntExtra("leftBattery", -1);
        if (intent.hasExtra("rightBattery")) bb.rightBattery = intent.getIntExtra("rightBattery", -1);
        if (intent.hasExtra("caseBattery")) bb.caseBattery = intent.getIntExtra("caseBattery", -1);
        if (intent.hasExtra("batteryLevel") && bb.mainBattery == -1) bb.mainBattery = intent.getIntExtra("batteryLevel", -1);

        // --- Normalization Rules ---
        int percent = -1;
        int count = 0;
        int sum = 0;

        if (bb.leftBattery >= 0) { sum += bb.leftBattery; count++; }
        if (bb.rightBattery >= 0) { sum += bb.rightBattery; count++; }
        if (bb.caseBattery >= 0) { sum += bb.caseBattery; count++; }

        if (count > 0) {
            percent = sum / count;
        } else if (bb.mainBattery >= 0) {
            percent = bb.mainBattery;
        }

        if (percent >= 0 && percent <= 100) {
            // Ignore zero on initial connection noise
            if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action) && percent == 0) return;

            String address = device.getAddress();
            String name = device.getName() != null ? device.getName() : "Bluetooth Device";
            btBatteryMap.put(address, percent);

            // Unified Handover
            if (GlyphNotificationListener.instance != null) {
                GlyphNotificationListener.instance.onProgressDetected("BLUETOOTH", name, percent, percent, 100, "BT_" + address);
                GlyphNotificationListener.updateGlyphLine(percent);
            }

            if (!prefs.getBoolean("bluetooth_battery_combine_enabled", false)) {
                android.widget.Toast.makeText(this, name + ": " + percent + "% 🔋", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
        }
        sensorManager.unregisterListener(this);
        AnimationManager.cancelPriority(AnimationManager.PRIORITY_BATTERY);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private <T extends android.os.Parcelable> T getParcelableExtraCompat(android.content.Intent intent, String name, Class<T> clazz) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(name, clazz);
        } else {
            return intent.getParcelableExtra(name);
        }
    }
}
