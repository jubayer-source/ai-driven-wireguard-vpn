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
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import com.wireguard.crypto.Key;

import java.net.InetAddress;

public class MyVpnService extends VpnService implements Tunnel {
    private static final String TAG = "MyVpnService";
    private static final String CHANNEL_ID = "ShieldNotificationChannel";
    public static final String ACTION_STOP = "com.jubayer.myapplication.STOP_VPN";

    private Backend backend;

    // Fixed credentials for your Ubuntu Server (RE-ALIGNED)
    private static final String SERVER_PUBKEY = "EXaSGFXpcgor5uHcIxGjRMVoOR6uHkqZxY6lPNAUUXM=";
    private static final String CLIENT_PRIVKEY = "GOwbK9lzmw0+RwJjL2F6AbYY6PQLj0B81RuGOFruTEw=";
    private static final String CLIENT_ADDRESS = "10.0.0.2/24";
    private static final int DEFAULT_SERVER_PORT = 443;

    @Override
    public void onCreate() {
        super.onCreate();
        backend = new GoBackend(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String serverIp = intent != null ? intent.getStringExtra("TARGET_SERVER_IP") : "192.168.50.252";
        int serverPort = intent != null ? intent.getIntExtra("TARGET_SERVER_PORT", DEFAULT_SERVER_PORT) : DEFAULT_SERVER_PORT;
        startForeground(1, createNotification());

        new Thread(() -> {
            try {
                // System stabilization delay
                Thread.sleep(1000);

                Config config = new Config.Builder()
                        .setInterface(new Interface.Builder()
                                .addAddress(com.wireguard.config.InetNetwork.parse(CLIENT_ADDRESS))
                                .parsePrivateKey(CLIENT_PRIVKEY)
                                .addDnsServer(InetAddress.getByName("8.8.8.8"))
                                .addDnsServer(InetAddress.getByName("1.1.1.1"))
                                // Critical: Set MTU to 1280 for mobile network compatibility
                                .setMtu(1280)
                                .build())
                        .addPeer(new Peer.Builder()
                                .setPublicKey(Key.fromBase64(SERVER_PUBKEY))
                                .setEndpoint(com.wireguard.config.InetEndpoint.parse(serverIp + ":" + serverPort))
                                .addAllowedIp(com.wireguard.config.InetNetwork.parse("0.0.0.0/0"))
                                .addAllowedIp(com.wireguard.config.InetNetwork.parse("::/0"))
                                .setPersistentKeepalive(25)
                                .build())
                        .build();

                backend.setState(this, State.UP, config);
                Log.i(TAG, "VPN Tunnel State requested: UP for " + serverIp + ":" + serverPort);
            } catch (Exception e) {
                Log.e(TAG, "WireGuard Backend Error", e);
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    @Override
    public String getName() { return "wg0"; }

    @Override
    public void onStateChange(State newState) { 
        Log.i(TAG, "Tunnel State Change: " + newState); 
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
                .setContentTitle("Aegis Sentinel: Shield Active")
                .setContentText("Network Orchestration is protecting your data.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(launchPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISCONNECT", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VPN Shield", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows active VPN protection status");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        new Thread(() -> {
            try {
                backend.setState(this, State.DOWN, null);
            } catch (Exception ignored) {}
        }).start();
    }
}
