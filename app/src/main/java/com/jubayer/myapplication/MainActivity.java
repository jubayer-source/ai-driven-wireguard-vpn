package com.jubayer.myapplication;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_HOTSPOT_PUBLIC_IP = "42.0.7.221";
    private static final String DEFAULT_LOCAL_LAN_IP = "192.168.50.203";
    private static final int SERVER_PORT = 51820;

    private TextView tvStatus, tvAiReason, tvPingVal, tvNetworkVal, tvRealIp, tvVpnIp, tvLocation, tvTimer;
    private EditText etServerIp;
    private Button btnToggleVpn;
    private LottieAnimationView lottieScan;

    private boolean isVpnActive = false;
    private boolean userHasEditedIp = false;
    private GeminiNetworkOrchestrator aiOrchestrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long startTime = 0;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long millis = System.currentTimeMillis() - startTime;
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            int hours = minutes / 60;
            seconds = seconds % 60;
            minutes = minutes % 60;
            tvTimer.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds));
            handler.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<Intent> vpnLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    startVpn();
                } else {
                    resetUi("SYSTEM ARMED");
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvAiReason = findViewById(R.id.tvAiReason);
        tvPingVal = findViewById(R.id.tvPingVal);
        tvNetworkVal = findViewById(R.id.tvNetworkVal);
        tvRealIp = findViewById(R.id.tvRealIp);
        tvVpnIp = findViewById(R.id.tvVpnIp);
        tvLocation = findViewById(R.id.tvLocation);
        tvTimer = findViewById(R.id.tvTimer);
        etServerIp = findViewById(R.id.etServerIp);
        btnToggleVpn = findViewById(R.id.btnToggleVpn);
        lottieScan = findViewById(R.id.lottieScan);

        aiOrchestrator = new GeminiNetworkOrchestrator();

        // Preset Node IP for testing
        etServerIp.setText(DEFAULT_LOCAL_LAN_IP);

        // Lottie সবসময় চালুর ব্যবস্থা (আইডল গতিতে)
        lottieScan.setSpeed(0.8f);
        lottieScan.playAnimation();

        etServerIp.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (etServerIp.hasFocus()) userHasEditedIp = true;
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        btnToggleVpn.setOnClickListener(v -> {
            if (isVpnActive) stopVpn();
            else runSmartScan();
        });

        lottieScan.setOnClickListener(v -> {
            if (!isVpnActive && btnToggleVpn.isEnabled()) runSmartScan();
        });

        refreshTelemetry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTelemetry();
    }

    private void refreshTelemetry() {
        new Thread(() -> {
            NetworkAnalyzer.Metrics m = NetworkAnalyzer.getLiveMetrics(this);
            handler.post(() -> {
                tvPingVal.setText(m.pingMs + " ms");
                tvNetworkVal.setText(m.networkName);
                if (!isVpnActive) {
                    tvRealIp.setText(m.publicIp);
                }
            });
        }).start();
    }

    private void runSmartScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE}, 100);
            return;
        }

        btnToggleVpn.setEnabled(false);

        // স্ক্যান করার সময় এনিমেশনের গতি বাড়িয়ে দ্রুত করা
        lottieScan.setSpeed(2.2f);
        if (!lottieScan.isAnimating()) {
            lottieScan.playAnimation();
        }

        tvStatus.setText("AEGIS INTELLIGENCE SCAN...");
        tvStatus.setTextColor(Color.parseColor("#00E5FF"));

        new Thread(() -> {
            NetworkAnalyzer.Metrics metrics = NetworkAnalyzer.getLiveMetrics(this);
            handler.post(() -> {
                tvPingVal.setText(metrics.pingMs + " ms");
                tvNetworkVal.setText(metrics.networkName);
                tvRealIp.setText(metrics.publicIp);
            });

            aiOrchestrator.analyzeNetworkContext(metrics, new GeminiNetworkOrchestrator.GeminiCallback() {
                @Override
                public void onResult(boolean connect, String reason, int confidence) {
                    handler.post(() -> {
                        tvAiReason.setText(reason);
                        if (connect) prepareVpn();
                        else resetUi("SYSTEM ARMED");
                    });
                }

                @Override
                public void onError(String error) {
                    handler.post(() -> {
                        resetUi("OFFLINE PROTECT");
                        tvAiReason.setText("Analysis Error: " + error);
                    });
                }
            });
        }).start();
    }

    private void prepareVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) vpnLauncher.launch(intent);
        else startVpn();
    }

    private void startVpn() {
        String targetIp = DEFAULT_LOCAL_LAN_IP;
        if (etServerIp != null && etServerIp.getText() != null) {
            String input = etServerIp.getText().toString().trim();
            if (!input.isEmpty()) targetIp = input;
        }

        executeVpnStart(targetIp);
    }

    private void executeVpnStart(String targetIp) {
        Intent intent = new Intent(this, MyVpnService.class);
        intent.putExtra("TARGET_SERVER_IP", targetIp);
        intent.putExtra("TARGET_SERVER_PORT", SERVER_PORT);
        ContextCompat.startForegroundService(this, intent);

        isVpnActive = true;

        // ভিপিএন এক্টিভ থাকলে এনিমেশন মাঝারি গতিতে ঘুরতে থাকবে
        lottieScan.setSpeed(1.2f);
        if (!lottieScan.isAnimating()) {
            lottieScan.playAnimation();
        }

        tvStatus.setText("PROTECTION ACTIVE");
        tvStatus.setTextColor(Color.parseColor("#00E676"));
        tvTimer.setVisibility(View.VISIBLE);
        startTime = System.currentTimeMillis();
        handler.removeCallbacks(timerRunnable);
        handler.post(timerRunnable);

        btnToggleVpn.setText("DISCONNECT");
        btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF5252")));
        btnToggleVpn.setEnabled(true);

        pollVpnIp(1, targetIp);
    }

    private void pollVpnIp(int attempt, String currentIp) {
        if (!isVpnActive) return;

        if (attempt > 6) {
            tvVpnIp.setText("Unreachable Route");
            tvVpnIp.setTextColor(Color.parseColor("#FF5252"));
            tvLocation.setText("Routing Failure");
            Toast.makeText(this, "Tunnel established but internet unreachable. Check Server NAT setup.", Toast.LENGTH_LONG).show();
            return;
        }

        tvVpnIp.setText("Verifying Route...");
        tvVpnIp.setTextColor(Color.parseColor("#00E5FF"));

        handler.postDelayed(() -> new Thread(() -> {
            String fetchedIp = NetworkAnalyzer.fetchPublicIp();
            handler.post(() -> {
                if (fetchedIp != null && !fetchedIp.equals("Unresolved")) {
                    tvVpnIp.setText(fetchedIp);
                    tvVpnIp.setTextColor(Color.parseColor("#00E676"));
                    tvLocation.setText("Aegis Secure Node (" + currentIp + ")");
                } else {
                    pollVpnIp(attempt + 1, currentIp);
                }
            });
        }).start(), 2000);
    }

    private void stopVpn() {
        stopService(new Intent(this, MyVpnService.class));
        handler.removeCallbacks(timerRunnable);
        tvTimer.setVisibility(View.GONE);
        tvVpnIp.setText("Inactive");
        tvVpnIp.setTextColor(Color.parseColor("#80FFFFFF"));
        tvLocation.setText("Local Subnet Direct");
        resetUi("SYSTEM ARMED");
        refreshTelemetry();
    }

    private void resetUi(String status) {
        isVpnActive = false;

        // ভিপিএন বন্ধ হলেও এনিমেশন শান্ত গতিতে ঘুরবে
        lottieScan.setSpeed(0.8f);
        if (!lottieScan.isAnimating()) {
            lottieScan.playAnimation();
        }

        tvStatus.setText(status);
        tvStatus.setTextColor(Color.parseColor("#FFFFFF"));
        btnToggleVpn.setText("SECURE CONNECTION");
        btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#00E5FF")));
        btnToggleVpn.setEnabled(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            runSmartScan();
        } else {
            Toast.makeText(this, "Permissions required for network orchestration.", Toast.LENGTH_LONG).show();
        }
    }
}