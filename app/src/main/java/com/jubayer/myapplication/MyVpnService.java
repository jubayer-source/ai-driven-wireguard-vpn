package com.jubayer.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
    private static final String CHANNEL_ID = "SmartVpnChannel";
    private static final String TUNNEL_NAME = "wg0";

    private Backend backend;

    // Provided Credentials
    private static final String SERVER_PUBKEY = "GzeWHAwo5awSX8SvndQPJrZrwAogwA0VlEKHAE0FXVM=";
    private static final String CLIENT_PRIVKEY = "YPEiKy9W/InZRt3ZUKRfEuEgimqJiU8V1txUqCKHQX0=";
    private static final String CLIENT_ADDRESS = "10.0.0.2/24";
    private static final int SERVER_PORT = 51820;

    @Override
    public void onCreate() {
        super.onCreate();
        backend = new GoBackend(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String serverIp = intent != null ? intent.getStringExtra("TARGET_SERVER_IP") : "192.168.50.1";
        startForeground(1, createNotification());

        new Thread(() -> {
            try {
                // Short sleep to allow the system to prepare the VpnService interface
                Thread.sleep(2000);

                Config config = new Config.Builder()
                        .setInterface(new Interface.Builder()
                                .addAddress(com.wireguard.config.InetNetwork.parse(CLIENT_ADDRESS))
                                .parsePrivateKey(CLIENT_PRIVKEY)
                                .addDnsServer(InetAddress.getByName("8.8.8.8"))
                                .build())
                        .addPeer(new Peer.Builder()
                                .setPublicKey(Key.fromBase64(SERVER_PUBKEY))
                                .setEndpoint(com.wireguard.config.InetEndpoint.parse(serverIp + ":" + SERVER_PORT))
                                // Server now supports NAT/Forwarding, so we enable Full Tunneling
                                .addAllowedIp(com.wireguard.config.InetNetwork.parse("0.0.0.0/0"))
                                .build())
                        .build();

                backend.setState(this, State.UP, config);
            } catch (Exception e) {
                Log.e(TAG, "VPN Startup Error", e);
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    @Override
    public String getName() { return TUNNEL_NAME; }

    @Override
    public void onStateChange(State newState) { Log.d(TAG, "State: " + newState); }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aegis Sentinel: Shield Active")
                .setContentText("Traffic secured via WireGuard Tunnel")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
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
