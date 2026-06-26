package com.rootprovider;

import android.app.ActivityManager;
import android.content.*;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private Button btnStartStop, btnPatchApp, btnSelectApk, btnTestAPI;
    private TextView tvStatus, tvLog, tvApiUrl, tvAdbInfo;
    private ProgressBar progressBar;
    private EditText etApiCommand;
    private String selectedApkPath;
    private boolean serviceRunning = false;
    private String deviceIp = "0.0.0.0";
    private BroadcastReceiver receiver;
    private static final int API_PORT = 8443;
    private static final int ADB_PORT = 5555;

    private final ActivityResultLauncher<String> filePicker = 
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                try {
                    String name = getFileName(uri);
                    InputStream in = getContentResolver().openInputStream(uri);
                    File outFile = new File(getCacheDir(), "input_" + System.currentTimeMillis() + ".apk");
                    FileOutputStream out = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    out.close();
                    in.close();
                    selectedApkPath = outFile.getAbsolutePath();
                    log("Selected: " + name + " (" + outFile.length() / 1024 + " KB)");
                    btnPatchApp.setEnabled(true);
                    btnPatchApp.setText("PATCH: " + name);
                } catch (IOException e) {
                    log("Error: " + e.getMessage());
                }
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartStop = findViewById(R.id.btnStartStop);
        btnPatchApp = findViewById(R.id.btnPatchApp);
        btnSelectApk = findViewById(R.id.btnSelectApk);
        btnTestAPI = findViewById(R.id.btnTestAPI);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvApiUrl = findViewById(R.id.tvApiUrl);
        tvAdbInfo = findViewById(R.id.tvAdbInfo);
        progressBar = findViewById(R.id.progressBar);
        etApiCommand = findViewById(R.id.etApiCommand);

        // Get device IP
        getDeviceIp();

        // Check if service is already running
        serviceRunning = isServiceRunning(RootProviderService.class);
        updateServiceUI();
        updateConnectionInfo();

        btnStartStop.setOnClickListener(v -> toggleService());
        btnSelectApk.setOnClickListener(v -> filePicker.launch("application/vnd.android.package-archive"));
        btnPatchApp.setOnClickListener(v -> patchApk());
        btnTestAPI.setOnClickListener(v -> testAPI());

        // Enable WiFi debugging
        enableWiFiDebugging();

        // Register broadcast receiver for service updates
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String msg = intent.getStringExtra("message");
                int progress = intent.getIntExtra("progress", -1);
                boolean done = intent.getBooleanExtra("done", false);
                boolean error = intent.getBooleanExtra("error", false);
                
                if (msg != null) log(msg);
                if (progress >= 0) progressBar.setProgress(progress);
                if (done) {
                    progressBar.setVisibility(View.GONE);
                    btnPatchApp.setEnabled(true);
                    tvStatus.setText("✓ Patching complete!");
                    // Show install button
                    String outputPath = intent.getStringExtra("output_path");
                    if (outputPath != null) {
                        installPatchedApk(new File(outputPath));
                    }
                }
                if (error) {
                    progressBar.setVisibility(View.GONE);
                    btnPatchApp.setEnabled(true);
                    tvStatus.setText("✗ Error - check log");
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, new IntentFilter("ROOT_PROVIDER_UPDATE"));

        // Auto-start service
        if (!serviceRunning) {
            toggleService();
        }
    }

    private void getDeviceIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                int ipInt = info.getIpAddress();
                deviceIp = String.format(Locale.US, "%d.%d.%d.%d",
                    (ipInt & 0xff), (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
            }
        } catch (Exception e) {
            deviceIp = "127.0.0.1";
        }
    }

    private void updateConnectionInfo() {
        String apiUrl = "http://" + deviceIp + ":" + API_PORT;
        tvApiUrl.setText("API: " + apiUrl);
        tvAdbInfo.setText("ADB: " + deviceIp + ":" + ADB_PORT);
    }

    private void enableWiFiDebugging() {
        // Try to enable WiFi ADB debugging
        try {
            // Enable developer settings and WiFi debugging
            Settings.Global.putInt(getContentResolver(), 
                Settings.Global.ADB_ENABLED, 1);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Global.putInt(getContentResolver(),
                    "adb_wifi_enabled", 1);
            }
            
            log("WiFi debugging enabled on " + deviceIp);
        } catch (Exception e) {
            log("WiFi debugging: " + e.getMessage());
            log("Enable manually: Developer Options → Wireless Debugging");
        }
    }

    private void testAPI() {
        String cmd = etApiCommand.getText().toString().trim();
        if (cmd.isEmpty()) cmd = "ping";
        
        new Thread(() -> {
            try {
                String apiUrl = "http://" + deviceIp + ":" + API_PORT;
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                String jsonCmd = "{\"command\":\"" + cmd + "\",\"params\":{}}";
                
                OutputStream os = conn.getOutputStream();
                os.write(jsonCmd.getBytes());
                os.flush();
                
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                
                log("API Response: " + response.toString());
                
            } catch (Exception e) {
                log("API Error: " + e.getMessage());
            }
        }).start();
    }

    private void toggleService() {
        Intent intent = new Intent(this, RootProviderService.class);
        if (!serviceRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceRunning = true;
            updateServiceUI();
            log("✓ Root provider service started");
            log("  API: http://" + deviceIp + ":" + API_PORT);
            log("  ADB: " + deviceIp + ":" + ADB_PORT);
        } else {
            stopService(intent);
            serviceRunning = false;
            updateServiceUI();
            log("✗ Root provider service stopped");
        }
    }

    private void updateServiceUI() {
        if (serviceRunning) {
            btnStartStop.setText("STOP ROOT PROVIDER");
            btnStartStop.setBackgroundColor(0xFFFF4444);
            tvStatus.setText("● FAKE ROOT ACTIVE");
            tvStatus.setTextColor(0xFF00E676);
        } else {
            btnStartStop.setText("START ROOT PROVIDER");
            btnStartStop.setBackgroundColor(0xFF00E676);
            tvStatus.setText("○ Service Stopped");
            tvStatus.setTextColor(0xFFFF4444);
        }
    }

    private void patchApk() {
        if (selectedApkPath == null) return;
        
        btnPatchApp.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvStatus.setText("Patching...");

        Intent intent = new Intent(this, ApkPatcher.class);
        intent.putExtra("apk_path", selectedApkPath);
        startService(intent);
    }

    private void installPatchedApk(File apkFile) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.setDataAndType(
                FileProvider.getUriForFile(this, 
                    getPackageName() + ".fileprovider", apkFile),
                "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            log("Install prompt sent");
        } catch (Exception e) {
            log("Install error: " + e.getMessage());
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }

    private String getFileName(Uri uri) {
        String name = "unknown.apk";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    public void log(final String msg) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            tvLog.append("[" + time + "] " + msg + "\n");
            scrollLogToBottom();
        });
    }

    private void scrollLogToBottom() {
        ScrollView sv = findViewById(R.id.scrollView);
        if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        }
    }
}
