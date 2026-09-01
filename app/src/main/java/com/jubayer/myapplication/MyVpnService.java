package com.jubayer.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import com.wireguard.crypto.Key;

import java.net.InetAddress;

public class MyVpnService extends VpnService implements Tunnel {
    private static final String TAG = "ShieldVpnService";
    private static final String CHANNEL_ID = "ShieldNotificationChannel";
    public static final String ACTION_STOP = "com.jubayer.myapplication.STOP_VPN";
    public static final String ACTION_VPN_STATE_CHANGED = "com.jubayer.myapplication.VPN_STATE_CHANGED";
    public static final String EXTRA_IS_ACTIVE = "is_active";

    private static Backend backend;
    private static State tunnelState = State.DOWN;
    private static String activeServerIp = "";

    // সার্ভার পাবলিক কি (পিসির wg0.conf এর সাথে মিল আছে)
    private static final String SERVER_PUBKEY = "EXaSGFXpcgor5uHcIxGjRMVoOR6uHkqZxY6lPNAUUXM=";
    
    // ক্লায়েন্ট প্রাইভেট কি (ফোন)
    private static final String CLIENT_PRIVKEY = "8OlQetLYYyG3XtGtCuz0rdnSXmXR51nb+OqTMj1erlw=";
    private static final String CLIENT_ADDRESS = "10.0.0.2/32";
    public static final int DEFAULT_SERVER_PORT = 22611;

    public static boolean isTunnelActive() {
        return tunnelState == State.UP;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (backend == null) {
            backend = new GoBackend(this);
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpnInternal();
            return START_NOT_STICKY;
        }

        String serverIp = (intent != null && intent.getStringExtra("TARGET_SERVER_IP") != null)
                ? intent.getStringExtra("TARGET_SERVER_IP").trim() : MainActivity.SERVER_PUBLIC_IP;
        int serverPort = intent != null ? intent.getIntExtra("TARGET_SERVER_PORT", DEFAULT_SERVER_PORT) : DEFAULT_SERVER_PORT;
        activeServerIp = serverIp;

        startForeground(1, createNotification(serverIp));

        new Thread(() -> {
            try {
                if (tunnelState == State.UP) {
                    try {
                        backend.setState(this, State.DOWN, null);
                    } catch (Exception ignored) {}
                }

                // ডিরেক্ট পাবলিক DNS ব্যবহার করা হচ্ছে
                Interface.Builder ifaceBuilder = new Interface.Builder()
                        .addAddress(InetNetwork.parse(CLIENT_ADDRESS))
                        .parsePrivateKey(CLIENT_PRIVKEY)
                        .addDnsServer(InetAddress.getByName("1.1.1.1"))
                        .addDnsServer(InetAddress.getByName("8.8.8.8"))
                        .setMtu(1280);

                // Endpoint সঠিক ফরম্যাটে হ্যান্ডেল করা
                String endpointStr = serverIp.contains(":") ? serverIp : (serverIp + ":" + serverPort);

                Peer.Builder peerBuilder = new Peer.Builder()
                        .setPublicKey(Key.fromBase64(SERVER_PUBKEY))
                        .setEndpoint(InetEndpoint.parse(endpointStr))
                        .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                        .setPersistentKeepalive(25);

                Config config = new Config.Builder()
                        .setInterface(ifaceBuilder.build())
                        .addPeer(peerBuilder.build())
                        .build();

                backend.setState(this, State.UP, config);
                tunnelState = State.UP;
                Log.i(TAG, "WireGuard Tunnel UP on: " + endpointStr);
                broadcastState(true);
            } catch (Exception e) {
                Log.e(TAG, "WireGuard Critical Error starting tunnel", e);
                stopVpnInternal();
            }
        }).start();

        return START_NOT_STICKY;
    }

    public synchronized void stopVpnInternal() {
        if (tunnelState == State.DOWN && backend == null) {
            stopForeground(true);
            stopSelf();
            return;
        }

        try {
            if (backend != null) {
                backend.setState(this, State.DOWN, null);
                Log.i(TAG, "WireGuard Tunnel DOWN command sent successfully.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error tearing down tunnel", e);
        } finally {
            tunnelState = State.DOWN;
            broadcastState(false);
            stopForeground(true);
            stopSelf();
        }
    }

    private void broadcastState(boolean isActive) {
        try {
            Intent intent = new Intent(ACTION_VPN_STATE_CHANGED);
            intent.putExtra(EXTRA_IS_ACTIVE, isActive);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error sending broadcast", e);
        }
    }

    @Override
    public String getName() {
        return "wg0";
    }

    @Override
    public void onStateChange(State newState) {
        Log.i(TAG, "Tunnel State: " + newState);
        tunnelState = newState;
    }

    private Notification createNotification(String serverIp) {
        Intent stopIntent = new Intent(this, MyVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent launchPendingIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SHIELD AI: Active Protection")
                .setContentText("Encrypted Tunnel: " + (serverIp != null ? serverIp : activeServerIp))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(launchPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISCONNECT", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Shield VPN Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (tunnelState == State.UP) {
            try {
                if (backend != null) {
                    backend.setState(this, State.DOWN, null);
                    Log.i(TAG, "WireGuard Tunnel DOWN in onDestroy");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down backend in onDestroy", e);
            }
            tunnelState = State.DOWN;
            broadcastState(false);
        }
        super.onDestroy();
    }
}