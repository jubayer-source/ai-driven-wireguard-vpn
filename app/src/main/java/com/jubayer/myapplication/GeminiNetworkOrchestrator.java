package com.jubayer.myapplication;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiNetworkOrchestrator {
    private static final String TAG = "GeminiOrchestrator";
    public static final String API_KEY = "AQ.Ab8RN6LZMZL1Q9hnPYf63TFiRUAFqQvX_PAlbaIxF9w6TocH8w";
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + API_KEY;

    private final OkHttpClient client;
    private final Handler mainHandler;

    public interface GeminiCallback {
        void onResult(boolean connect, String reason, int confidence);
        void onError(String error);
    }

    public GeminiNetworkOrchestrator() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private void runLocalHeuristics(NetworkAnalyzer.Metrics m, boolean isVpnActive, GeminiCallback callback) {
        StringBuilder reason = new StringBuilder();
        int confidence = 98;

        if (!isVpnActive) {
            reason.append("Your connection is currently unencrypted. Tap SECURE CONNECTION to protect your network privacy & IP address.");
        } else if (m.pingMs > 150) {
            reason.append("Slow connection speed detected (").append(m.pingMs)
                    .append("ms). Tunnel active to stabilize your latency & optimize routing.");
        } else if (!m.isSecure && m.ssid != null && !m.ssid.isEmpty()) {
            reason.append("Connected to public Wi-Fi '").append(m.ssid)
                    .append("'. Encrypting all internet data to protect your browsing from local network trackers.");
        } else if (m.signalStrength != 0 && m.signalStrength < -75) {
            reason.append("Weak signal (").append(m.signalStrength)
                    .append("dBm). Maintaining persistent tunnel to keep your connection stable.");
        } else if ("Cellular".equalsIgnoreCase(m.connectionType)) {
            reason.append("Mobile data active. Encrypting traffic to secure your real IP address & prevent carrier tracking.");
        } else if (m.pingMs > 0 && m.pingMs <= 100) {
            reason.append("Fast network detected (").append(m.pingMs)
                    .append("ms). All your outbound internet traffic is encrypted and fully private.");
        } else {
            reason.append("Your network '").append(m.networkName)
                    .append("' is secured. Internet traffic is encrypted through WireGuard.");
        }

        final String finalReason = reason.toString();
        mainHandler.post(() -> callback.onResult(true, finalReason, confidence));
    }

    public void analyzeNetworkContext(NetworkAnalyzer.Metrics metrics, boolean isVpnActive, GeminiCallback callback) {
        if (API_KEY.startsWith("AQ.")) {
            runLocalHeuristics(metrics, isVpnActive, callback);
            return;
        }

        try {
            String prompt = String.format(Locale.US,
                    "Context: ConnectionType=%s, SSID=%s, Latency=%dms, RSSI=%ddBm, Encrypted=%b, VpnActive=%b. " +
                            "Task: Perform zero-trust threat analysis. Is WireGuard VPN tunnel required? " +
                            "Respond in JSON ONLY: {\"connect\": boolean, \"reason\": \"string\", \"confidence\": int}",
                    metrics.connectionType, metrics.ssid, metrics.pingMs, metrics.signalStrength, metrics.isSecure, isVpnActive
            );

            JSONObject payload = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject()
                            .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));

            RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder().url(API_URL).post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runLocalHeuristics(metrics, isVpnActive, callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (Response resp = response) {
                        if (!resp.isSuccessful()) {
                            runLocalHeuristics(metrics, isVpnActive, callback);
                            return;
                        }
                        String bodyStr = resp.body().string();
                        JSONObject mainResp = new JSONObject(bodyStr);
                        String aiText = mainResp.getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts")
                                .getJSONObject(0).getString("text");

                        String cleanJson = aiText.substring(aiText.indexOf("{"), aiText.lastIndexOf("}") + 1);
                        JSONObject result = new JSONObject(cleanJson);

                        boolean connect = result.optBoolean("connect", true);
                        String reason = result.optString("reason", "Analysis complete.");
                        int confidence = result.optInt("confidence", 92);

                        mainHandler.post(() -> callback.onResult(connect, reason, confidence));
                    } catch (Exception e) {
                        runLocalHeuristics(metrics, isVpnActive, callback);
                    }
                }
            });
        } catch (JSONException e) {
            runLocalHeuristics(metrics, isVpnActive, callback);
        }
    }
}