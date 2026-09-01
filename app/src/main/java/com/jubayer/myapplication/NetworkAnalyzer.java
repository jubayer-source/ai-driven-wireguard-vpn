package com.jubayer.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NetworkAnalyzer {
    private static final String TAG = "AegisNetworkAnalyzer";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static class Metrics {
        public int pingMs;
        public int signalStrength;
        public String connectionType;
        public String ssid;
        public String networkName;
        public String publicIp;
        public boolean isSecure;
        public String securityType;
    }

    public static boolean isConnectedToLocalWifi(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        String ipStr = String.format(java.util.Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                        return ipStr.startsWith("192.168.68.");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Local Wi-Fi check error: " + e.getMessage());
        }
        return false;
    }

    public static Metrics getLiveMetrics(Context context) {
        Metrics metrics = new Metrics();
        metrics.connectionType = getConnectionType(context);

        if ("WIFI".equals(metrics.connectionType)) {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifiManager != null ? wifiManager.getConnectionInfo() : null;
            metrics.ssid = getWifiSSID(context);
            metrics.networkName = "Wi-Fi: " + metrics.ssid;
            metrics.signalStrength = info != null ? info.getRssi() : -100;
            metrics.securityType = getWifiSecurityType(info);
            metrics.isSecure = !"OPEN".equals(metrics.securityType);
        } else if ("CELLULAR".equals(metrics.connectionType)) {
            metrics.networkName = getActiveDataCarrierName(context) + " (" + getCellularType(context) + ")";
            metrics.signalStrength = -80;
            metrics.isSecure = true;
            metrics.ssid = "N/A";
            metrics.securityType = "LTE-A Encryption";
        } else {
            metrics.networkName = "Offline";
            metrics.ssid = "N/A";
            metrics.signalStrength = -100;
        }

        metrics.pingMs = measureHttpLatency();
        metrics.publicIp = fetchPublicIp();

        return metrics;
    }

    private static String getConnectionType(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "NONE";
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) return "NONE";
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) return "NONE";

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR";
        return "OTHER";
    }

    private static String getActiveDataCarrierName(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "Mobile Data";
        }
        try {
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm != null) {
                int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    List<SubscriptionInfo> subList = sm.getActiveSubscriptionInfoList();
                    if (subList != null) {
                        for (SubscriptionInfo info : subList) {
                            if (info.getSubscriptionId() == dataSubId) {
                                return info.getDisplayName().toString();
                            }
                        }
                    }
                }
            }
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null ? tm.getNetworkOperatorName() : "Carrier";
        } catch (Exception e) {
            return "Mobile Data";
        }
    }

    private static String getWifiSSID(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return "Unknown";
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = (info != null) ? info.getSSID() : "Unknown";
        if (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) return "Private Link";
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
        return ssid;
    }

    private static String getWifiSecurityType(WifiInfo info) {
        if (info == null) return "Unknown";
        String ssid = info.getSSID().toLowerCase();
        if (ssid.contains("open") || ssid.contains("free")) return "OPEN";
        return "WPA2/WPA3 Protected";
    }

    private static String getCellularType(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "LTE";
        }
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return "LTE";
        try {
            int networkType = tm.getDataNetworkType();
            switch (networkType) {
                case TelephonyManager.NETWORK_TYPE_LTE: return "4.5G";
                case TelephonyManager.NETWORK_TYPE_NR: return "5G";
                default: return "4G";
            }
        } catch (Exception e) {
            return "4G";
        }
    }

    public static String fetchPublicIp() {
        String[] endpoints = {
                "https://api.ipify.org",
                "https://icanhazip.com",
                "https://ipinfo.io/ip",
                "https://ifconfig.me/ip",
                "https://checkip.amazonaws.com"
        };
        for (String url : endpoints) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "curl/7.68.0")
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String ip = response.body().string().trim();
                        if (!ip.isEmpty() && ip.contains(".") && ip.length() <= 15) return ip;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Endpoint " + url + " unreachable: " + e.getMessage());
            }
        }
        return "Unresolved";
    }

    private static int measureHttpLatency() {
        long start = System.currentTimeMillis();
        try {
            Request request = new Request.Builder().url("https://www.google.com/generate_204").build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) return (int) (System.currentTimeMillis() - start);
            }
        } catch (IOException ignored) {}
        return 999;
    }
}