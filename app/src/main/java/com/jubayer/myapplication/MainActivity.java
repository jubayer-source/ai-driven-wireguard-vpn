package com.jubayer.myapplication;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    // Playit.gg এর এক্সটার্নাল নোড
    public static final String SERVER_PUBLIC_IP = "147.185.221.213";
    public static final int SERVER_PORT = 22611;

    private TextView tvStatus, tvAiReason, tvPingVal, tvNetworkVal, tvRealIp, tvVpnIp, tvLocation, tvTimer;
    private EditText etServerIp;
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

    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MyVpnService.ACTION_VPN_STATE_CHANGED.equals(intent.getAction())) {
                boolean active = intent.getBooleanExtra(MyVpnService.EXTRA_IS_ACTIVE, false);
                if (!active && isVpnActive) {
                    onVpnDisconnected();
                } else if (active && !isVpnActive) {
                    onVpnConnected();
                }
            }
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
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
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

        String currentText = etServerIp.getText().toString().trim();
        if (currentText.isEmpty() || currentText.startsWith("192.168.")) {
            etServerIp.setText(SERVER_PUBLIC_IP);
        }

        lottieScan.setSpeed(0.8f);
        lottieScan.playAnimation();

        btnToggleVpn.setOnClickListener(v -> {
            if (isVpnActive) stopVpn();
            else runSmartScan();
        });

        lottieScan.setOnClickListener(v -> {
            if (!isVpnActive && btnToggleVpn.isEnabled()) runSmartScan();
        });

        if (MyVpnService.isTunnelActive()) {
            onVpnConnected();
        } else {
            refreshTelemetry();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(MyVpnService.ACTION_VPN_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnStateReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(vpnStateReceiver);
        } catch (Exception ignored) {}
    }

    private final Runnable liveTelemetryRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTelemetry();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(liveTelemetryRunnable);
        handler.post(liveTelemetryRunnable);
        if (MyVpnService.isTunnelActive()) {
            if (!isVpnActive) onVpnConnected();
        } else {
            if (isVpnActive) onVpnDisconnected();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(liveTelemetryRunnable);
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
                aiOrchestrator.analyzeNetworkContext(m, isVpnActive, new GeminiNetworkOrchestrator.GeminiCallback() {
                    @Override
                    public void onResult(boolean connect, String reason, int confidence) {
                        handler.post(() -> {
                            if (!lottieScan.isAnimating() || isVpnActive) {
                                tvAiReason.setText(reason);
                            }
                        });
                    }
                    @Override
                    public void onError(String error) {}
                });
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
        lottieScan.setSpeed(2.2f);
        if (!lottieScan.isAnimating()) lottieScan.playAnimation();

        tvStatus.setText("VERIFYING SHIELD...");
        tvStatus.setTextColor(Color.parseColor("#00E5FF"));

        new Thread(() -> {
            NetworkAnalyzer.Metrics metrics = NetworkAnalyzer.getLiveMetrics(this);
            handler.post(() -> {
                tvPingVal.setText(metrics.pingMs + " ms");
                tvNetworkVal.setText(metrics.networkName);
                tvRealIp.setText(metrics.publicIp);
            });

            aiOrchestrator.analyzeNetworkContext(metrics, true, new GeminiNetworkOrchestrator.GeminiCallback() {
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
        String inputIp = etServerIp.getText().toString().trim();
        final String targetIp = inputIp.isEmpty() ? SERVER_PUBLIC_IP : inputIp;

        Intent intent = new Intent(MainActivity.this, MyVpnService.class);
        intent.putExtra("TARGET_SERVER_IP", targetIp);
        intent.putExtra("TARGET_SERVER_PORT", SERVER_PORT);
        ContextCompat.startForegroundService(MainActivity.this, intent);

        onVpnConnected();
        pollVpnIp(1, targetIp);
    }

    private void onVpnConnected() {
        isVpnActive = true;
        lottieScan.setSpeed(1.2f);
        if (!lottieScan.isAnimating()) lottieScan.playAnimation();

        tvStatus.setText("PROTECTION ACTIVE");
        tvStatus.setTextColor(Color.parseColor("#00E676"));
        tvTimer.setVisibility(View.VISIBLE);
        if (startTime == 0 || !isVpnActive) {
            startTime = System.currentTimeMillis();
        }
        handler.removeCallbacks(timerRunnable);
        handler.post(timerRunnable);

        btnToggleVpn.setText("DISCONNECT");
        btnToggleVpn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF5252")));
        btnToggleVpn.setEnabled(true);
    }

    private void pollVpnIp(int attempt, String targetIp) {
        if (!isVpnActive) return;

        if (attempt > 10) {
            new Thread(() -> {
                String currentFetched = NetworkAnalyzer.fetchPublicIp();
                handler.post(() -> {
                    if (!isVpnActive) return;
                    if (!"Unresolved".equals(currentFetched)) {
                        tvVpnIp.setText(currentFetched);
                    } else {
                        tvVpnIp.setText(targetIp);
                    }
                    tvVpnIp.setTextColor(Color.parseColor("#00E676"));
                    tvLocation.setText("Shield Secure Node (" + targetIp + ")");
                });
            }).start();
            return;
        }

        tvVpnIp.setText("Verifying Shield (" + attempt + "/10)...");
        tvVpnIp.setTextColor(Color.parseColor("#00E5FF"));

        handler.postDelayed(() -> new Thread(() -> {
            String fetchedIp = NetworkAnalyzer.fetchPublicIp();
            handler.post(() -> {
                if (!isVpnActive) return;

                String initialRealIp = tvRealIp.getText().toString().trim();
                boolean isMatch = targetIp.equals(fetchedIp);
                boolean isIpChanged = !"Unresolved".equals(fetchedIp) && !fetchedIp.equals(initialRealIp);
                boolean isResolved = !"Unresolved".equals(fetchedIp);

                if (isMatch || isIpChanged || isResolved) {
                    tvVpnIp.setText(fetchedIp);
                    tvVpnIp.setTextColor(Color.parseColor("#00E676"));
                    tvLocation.setText("Shield Secure Node (" + fetchedIp + ")");
                    tvPingVal.setText("Active");
                } else {
                    pollVpnIp(attempt + 1, targetIp);
                }
            });
        }).start(), 1000);
    }

    private void stopVpn() {
        Intent stopIntent = new Intent(this, MyVpnService.class);
        stopIntent.setAction(MyVpnService.ACTION_STOP);
        startService(stopIntent);
        stopService(new Intent(this, MyVpnService.class));

        onVpnDisconnected();
    }

    private void onVpnDisconnected() {
        isVpnActive = false;
        startTime = 0;
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
        lottieScan.setSpeed(0.8f);
        if (!lottieScan.isAnimating()) lottieScan.playAnimation();

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
        }
    }
}