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
    private static final String TAG = "AegisVpnService";
    private static final String CHANNEL_ID = "AegisShieldNotificationChannel";
    public static final String ACTION_STOP = "com.jubayer.myapplication.STOP_VPN";

    private Backend backend;

    // Ubuntu Server Active WireGuard Credentials
    private static final String SERVER_PUBKEY = "EXaSGFXpcgor5uHcIxGjRMVoOR6uHkqZxY6lPNAUUXM=";
    private static final String CLIENT_PRIVKEY = "8OlQetLYYyG3XtGtCuz0rdnSXmXR51nb+OqTMj1erlw=";
    private static final String CLIENT_ADDRESS = "10.0.0.2/32";
    private static final int DEFAULT_SERVER_PORT = 51820;

    @Override
    public void onCreate() {
        super.onCreate();
        backend = new GoBackend(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpnInternal();
            return START_NOT_STICKY;
        }

        String serverIp = (intent != null && intent.getStringExtra("TARGET_SERVER_IP") != null)
                ? intent.getStringExtra("TARGET_SERVER_IP") : "42.0.7.221";
        int serverPort = intent != null ? intent.getIntExtra("TARGET_SERVER_PORT", DEFAULT_SERVER_PORT) : DEFAULT_SERVER_PORT;

        startForeground(1, createNotification());

        new Thread(() -> {
            try {
                // Ensure underlying socket setup stabilizes
                Thread.sleep(300);

                Interface.Builder ifaceBuilder = new Interface.Builder()
                        .addAddress(InetNetwork.parse(CLIENT_ADDRESS))
                        .parsePrivateKey(CLIENT_PRIVKEY)
                        .addDnsServer(InetAddress.getByName("1.1.1.1"))
                        .addDnsServer(InetAddress.getByName("8.8.8.8"))
                        .setMtu(1280); // Strict MTU to prevent packet fragmentation over heterogeneous Wi-Fi networks

                Peer.Builder peerBuilder = new Peer.Builder()
                        .setPublicKey(Key.fromBase64(SERVER_PUBKEY))
                        .setEndpoint(InetEndpoint.parse(serverIp + ":" + serverPort))
                        .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                        .addAllowedIp(InetNetwork.parse("::/0"))
                        .setPersistentKeepalive(15); // Maintain UDP NAT Mapping active

                Config config = new Config.Builder()
                        .setInterface(ifaceBuilder.build())
                        .addPeer(peerBuilder.build())
                        .build();

                backend.setState(this, State.UP, config);
                Log.i(TAG, "WireGuard Tunnel UP on Node Endpoint: " + serverIp + ":" + serverPort);
            } catch (Exception e) {
                Log.e(TAG, "WireGuard Engine Critical Failure", e);
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    private void stopVpnInternal() {
        new Thread(() -> {
            try {
                backend.setState(this, State.DOWN, null);
            } catch (Exception e) {
                Log.e(TAG, "Error tearing down tunnel", e);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }).start();
    }

    @Override
    public String getName() {
        return "wg0";
    }

    @Override
    public void onStateChange(State newState) {
        Log.i(TAG, "Aegis Security Tunnel State Changed: " + newState);
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, MyVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent launchPendingIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AEGIS SENTINEL: Active Shield")
                .setContentText("Traffic encrypted and routed via Secure Node Gateway.")
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
                    "Aegis Shield Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active Aegis Sentinel encryption status");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpnInternal();
    }
}