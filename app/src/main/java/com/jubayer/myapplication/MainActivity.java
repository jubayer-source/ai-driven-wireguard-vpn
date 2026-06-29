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
    private static final String SERVER_IP = "163.47.36.234";

    private TextView tvStatus, tvAiReason, tvPingVal, tvNetworkVal, tvRealIp, tvVpnIp, tvLocation, tvTimer;
    private Button btnToggleVpn;
    private LottieAnimationView lottieScan;
    
    private boolean isVpnActive = false;
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
            result -> { if (result.getResultCode() == Activity.RESULT_OK) startVpn(); }
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
        btnToggleVpn = findViewById(R.id.btnToggleVpn);
        lottieScan = findViewById(R.id.lottieScan);

        aiOrchestrator = new GeminiNetworkOrchestrator();

        btnToggleVpn.setOnClickListener(v -> {
            if (isVpnActive) stopVpn();
            else runSmartScan();
        });

        // Add scan trigger on lottie for reliability
        lottieScan.setOnClickListener(v -> {
            if (!isVpnActive && btnToggleVpn.isEnabled()) runSmartScan();
        });

        refreshTelemetry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (VpnService.prepare(this) != null && isVpnActive) {
            stopVpn();
        }
        refreshTelemetry();
    }

    private void refreshTelemetry() {
        new Thread(() -> {
            NetworkAnalyzer.Metrics m = NetworkAnalyzer.getLiveMetrics(this);
            handler.post(() -> {
                tvPingVal.setText(m.pingMs + " ms");
                tvNetworkVal.setText(m.networkName);
                tvRealIp.setText(m.publicIp);
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
        lottieScan.setSpeed(2.5f);
        lottieScan.playAnimation();
        tvStatus.setText("AI ANALYSIS IN PROGRESS");
        tvStatus.setTextColor(Color.parseColor("#00D2FF"));

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
                        lottieScan.setSpeed(1.0f);
                        tvAiReason.setText(reason);
                        if (connect) {
                            prepareVpn();
                        } else {
                            resetUi("SHIELD STANDBY");
                            Toast.makeText(MainActivity.this, "AI: Network Secure. Shield in Standby.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    handler.post(() -> {
                        resetUi("LOCAL FALLBACK ACTIVE");
                        tvAiReason.setText(error);
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
        Intent intent = new Intent(this, MyVpnService.class);
        intent.putExtra("TARGET_SERVER_IP", SERVER_IP);
        ContextCompat.startForegroundService(this, intent);
        
        isVpnActive = true;
        lottieScan.pauseAnimation();
        tvStatus.setText("PROTECTION ACTIVE");
        tvStatus.setTextColor(Color.parseColor("#00E676"));
        tvTimer.setVisibility(View.VISIBLE);
        startTime = System.currentTimeMillis();
        handler.post(timerRunnable);

        btnToggleVpn.setText("DISCONNECT");
        btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF5252")));
        btnToggleVpn.setEnabled(true);

        // Start polling loop to fetch verified VPN IP
        pollVpnIp(1);
    }

    private void pollVpnIp(int attempt) {
        if (!isVpnActive || attempt > 5) {
            if (attempt > 5 && "Resolving...".equals(tvVpnIp.getText().toString())) {
                tvVpnIp.setText("Protected (Tunnel Active)");
                tvVpnIp.setTextColor(Color.parseColor("#00E676"));
                tvLocation.setText("Aegis Secure Node");
            }
            return;
        }

        tvVpnIp.setText("Resolving...");
        handler.postDelayed(() -> new Thread(() -> {
            NetworkAnalyzer.Metrics m = NetworkAnalyzer.getLiveMetrics(this);
            handler.post(() -> {
                if (m.publicIp != null && !m.publicIp.contains("Checking") && !m.publicIp.equals("Unresolved")) {
                    tvVpnIp.setText(m.publicIp);
                    tvVpnIp.setTextColor(Color.parseColor("#00E676"));
                    tvLocation.setText("Aegis Secure Node");
                    tvPingVal.setText(m.pingMs + " ms");
                } else {
                    pollVpnIp(attempt + 1);
                }
            });
        }).start(), 3000);
    }

    private void stopVpn() {
        stopService(new Intent(this, MyVpnService.class));
        handler.removeCallbacks(timerRunnable);
        tvTimer.setVisibility(View.GONE);
        tvVpnIp.setText("Inactive");
        tvVpnIp.setTextColor(Color.parseColor("#80FFFFFF"));
        tvLocation.setText("Local Gateway");
        resetUi("SYSTEM READY");
    }

    private void resetUi(String status) {
        isVpnActive = false;
        lottieScan.pauseAnimation();
        tvStatus.setText(status);
        tvStatus.setTextColor(Color.parseColor("#FFFFFF"));
        btnToggleVpn.setText("SECURE CONNECTION");
        btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#00D2FF")));
        btnToggleVpn.setEnabled(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runSmartScan();
            } else {
                Toast.makeText(this, "Permissions required for telemetry", Toast.LENGTH_LONG).show();
            }
        }
    }
}
