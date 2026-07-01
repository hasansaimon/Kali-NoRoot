package com.rootprovider;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

public class MainActivity extends AppCompatActivity {

    // Service controls
    private Button btnStartStop;
    private TextView tvServiceStatus, tvApiUrl, tvAdbInfo;

    // Status monitor
    private TextView tvCurrentStage, tvProgressDetail;
    private LinearProgressIndicator progressBar;

    // App picker
    private EditText etSearch;
    private RecyclerView appListView;
    private ImageView ivSelectedIcon;
    private TextView tvSelectedApp;
    private LinearLayout selectedAppCard;
    private Button btnFilePicker;

    // Action buttons
    private Button btnPatch, btnVerify, btnInstall;

    // Log
    private TextView tvLog;

    // State
    private String selectedApkPath;
    private String selectedAppName;
    private boolean serviceRunning = false;
    private String deviceIp = "0.0.0.0";
    private String lastPatchedApkPath;
    private AppListAdapter appAdapter;
    private final List<AppInfo> allApps = new ArrayList<>();
    private BroadcastReceiver updateReceiver;

    // File picker
    private final ActivityResultLauncher<String> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            try {
                String name = getFileName(uri);
                File outFile = new File(getCacheDir(),
                    "input_" + System.currentTimeMillis() + ".apk");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                selectedApkPath = outFile.getAbsolutePath();
                selectedAppName = name;
                updateSelectedApp(null, name, true);
                enableActions(true, false, false);
                log("Selected (file): " + name);
            } catch (Exception e) {
                log("Error reading file: " + e.getMessage());
            }
        });

    // Permission launcher (API 33+)
    private final ActivityResultLauncher<String> notifPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) {
                Toast.makeText(this,
                    "Notification permission denied — patcher notifications will be hidden",
                    Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();
        registerReceivers();
        ensurePermissions();
        loadInstalledApps();
        getDeviceIp();

        if (savedInstanceState == null) {
            checkCrashRecovery();
        } else {
            // Restore state after rotation
            selectedApkPath = savedInstanceState.getString("apk_path");
            selectedAppName = savedInstanceState.getString("app_name");
            lastPatchedApkPath = savedInstanceState.getString("last_patch");
            serviceRunning = savedInstanceState.getBoolean("service_running", false);
            if (serviceRunning) updateServiceUI(true);
            if (lastPatchedApkPath != null && new File(lastPatchedApkPath).exists()) {
                enableActions(false, true, true);
            } else if (selectedApkPath != null) {
                enableActions(true, false, false);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("apk_path", selectedApkPath);
        outState.putString("app_name", selectedAppName);
        outState.putString("last_patch", lastPatchedApkPath);
        outState.putBoolean("service_running", serviceRunning);
    }

    private void initViews() {
        btnStartStop = findViewById(R.id.btnStartStop);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvApiUrl = findViewById(R.id.tvApiUrl);
        tvAdbInfo = findViewById(R.id.tvAdbInfo);

        tvCurrentStage = findViewById(R.id.tvCurrentStage);
        tvProgressDetail = findViewById(R.id.tvProgressDetail);
        progressBar = findViewById(R.id.progressBar);

        etSearch = findViewById(R.id.etSearch);
        appListView = findViewById(R.id.appListView);
        ivSelectedIcon = findViewById(R.id.ivSelectedIcon);
        tvSelectedApp = findViewById(R.id.tvSelectedApp);
        selectedAppCard = findViewById(R.id.selectedAppCard);
        btnFilePicker = findViewById(R.id.btnFilePicker);

        btnPatch = findViewById(R.id.btnPatch);
        btnVerify = findViewById(R.id.btnVerify);
        btnInstall = findViewById(R.id.btnInstall);

        tvLog = findViewById(R.id.tvLog);

        appAdapter = new AppListAdapter();
        appListView.setLayoutManager(new LinearLayoutManager(this));
        appListView.setAdapter(appAdapter);

        tvServiceStatus.setText(R.string.service_stopped);
        tvCurrentStage.setText(R.string.stage_idle);
        tvProgressDetail.setText("Ready");
        progressBar.setProgress(0);
        enableActions(false, false, false);
    }

    private void setupListeners() {
        btnStartStop.setOnClickListener(v -> toggleService());
        btnFilePicker.setOnClickListener(v ->
            filePickerLauncher.launch("application/vnd.android.package-archive"));
        btnPatch.setOnClickListener(v -> startPatching());
        btnVerify.setOnClickListener(v -> verifyPatch());
        btnInstall.setOnClickListener(v -> installPatchedApk());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int s2, int s3, int s4) {}
            @Override public void afterTextChanged(Editable e) {}
            @Override
            public void onTextChanged(CharSequence q, int s, int b, int c) {
                filterApps(q.toString());
            }
        });
    }

    private void registerReceivers() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case "PATCHER_PROGRESS":
                        int stage = intent.getIntExtra("stage", 0);
                        int pct = intent.getIntExtra("progress", 0);
                        String detail = intent.getStringExtra("detail");
                        updateStage(stage, pct, detail);
                        progressBar.setProgressCompat(pct, true);
                        break;
                    case "PATCHER_DONE":
                        String out = intent.getStringExtra("output_path");
                        lastPatchedApkPath = out;
                        tvCurrentStage.setText(R.string.stage_done);
                        tvCurrentStage.setTextColor(
                            ContextCompat.getColor(MainActivity.this,
                                android.R.color.holo_green_light));
                        tvProgressDetail.setText("Output: " + new File(out).getName());
                        progressBar.setProgressCompat(100, true);
                        enableActions(false, true, true);
                        log("✓ Patching complete!");
                        saveCrashRecovery(out);
                        break;
                    case "PATCHER_ERROR":
                        String err = intent.getStringExtra("error");
                        tvCurrentStage.setText(R.string.stage_failed);
                        tvCurrentStage.setTextColor(
                            ContextCompat.getColor(MainActivity.this,
                                android.R.color.holo_red_light));
                        tvProgressDetail.setText(err);
                        progressBar.setProgressCompat(0, true);
                        enableActions(true, false, false);
                        log("✗ Error: " + err);
                        break;
                    case "ROOT_PROVIDER_UPDATE":
                        String msg = intent.getStringExtra("message");
                        if (msg != null) log(msg);
                        break;
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction("PATCHER_PROGRESS");
        f.addAction("PATCHER_DONE");
        f.addAction("PATCHER_ERROR");
        f.addAction("ROOT_PROVIDER_UPDATE");
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, f);
    }

    private void updateStage(int stage, int pct, String detail) {
        String[] stages = {
            "Idle", "Analyzing APK...", "Extracting APK...",
            "Decompiling with apktool...", "Injecting fake root environment...",
            "Deploying Frida gadget...", "Patching smali entry points...",
            "Setting debuggable flag...", "Recompiling APK...",
            "Signing APK...", "Verifying patched APK..."
        };
        String text = (stage >= 0 && stage < stages.length) ? stages[stage] : "Working...";
        tvCurrentStage.setText(text);
        tvCurrentStage.setTextColor(
            ContextCompat.getColor(this, android.R.color.white));
        tvProgressDetail.setText(detail != null ? detail : pct + "%");
    }

    private void checkCrashRecovery() {
        SharedPreferences prefs = getSharedPreferences("rootprovider", MODE_PRIVATE);
        String lastPath = prefs.getString("last_patch_path", null);
        if (lastPath == null || !new File(lastPath).exists()) return;

        lastPatchedApkPath = lastPath;
        new AlertDialog.Builder(this)
            .setTitle(R.string.crash_recovery_title)
            .setMessage(R.string.crash_recovery_body + "\n\n" + lastPath)
            .setPositiveButton("INSTALL", (d, w) -> installPatchedApk())
            .setNegativeButton("VERIFY", (d, w) -> verifyPatch())
            .setNeutralButton(R.string.discard, (d, w) -> {
                new File(lastPath).delete();
                prefs.edit().remove("last_patch_path").apply();
                lastPatchedApkPath = null;
                enableActions(true, false, false);
            })
            .setCancelable(true)
            .show();
    }

    private void saveCrashRecovery(String path) {
        getSharedPreferences("rootprovider", MODE_PRIVATE)
            .edit()
            .putString("last_patch_path", path)
            .apply();
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                log("Cannot request install permission: " + e.getMessage());
            }
        }
        // POST_NOTIFICATIONS permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void loadInstalledApps() {
        allApps.clear();
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            try {
                Drawable icon = ri.activityInfo.loadIcon(pm);
                String name = ri.activityInfo.loadLabel(pm).toString();
                allApps.add(new AppInfo(name, pkg, icon));
            } catch (Exception ignored) {}
        }
        Collections.sort(allApps, (a, b) -> a.name.compareToIgnoreCase(b.name));
        appAdapter.setData(allApps);
    }

    private void filterApps(String q) {
        if (q.isEmpty()) {
            appAdapter.setData(allApps);
            return;
        }
        List<AppInfo> filtered = new ArrayList<>();
        String lower = q.toLowerCase();
        for (AppInfo a : allApps) {
            if (a.name.toLowerCase().contains(lower) ||
                a.packageName.toLowerCase().contains(lower))
                filtered.add(a);
        }
        appAdapter.setData(filtered);
    }

    private void onAppTapped(AppInfo app) {
        String pkg = app.packageName.toLowerCase();
        if (pkg.contains("vending") || pkg.contains("google") ||
            pkg.contains("gms") || pkg.contains("playstore")) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.play_store_warning_title)
                .setMessage(R.string.play_store_warning_body)
                .setPositiveButton(R.string.continue_action,
                    (d, w) -> selectApp(app))
                .setNegativeButton(R.string.cancel, null)
                .show();
            return;
        }
        selectApp(app);
    }

    private void selectApp(AppInfo app) {
        selectedAppName = app.name + " (" + app.packageName + ")";
        updateSelectedApp(app.icon, selectedAppName, true);
        try {
            ApplicationInfo ai = getPackageManager()
                .getApplicationInfo(app.packageName, 0);
            File src = new File(ai.sourceDir);
            File dst = new File(getCacheDir(),
                "to_patch_" + app.packageName.replace('.', '_') + ".apk");
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            selectedApkPath = dst.getAbsolutePath();
            enableActions(true, false, false);
            log("Selected: " + app.name + " (" +
                String.format("%.1f", dst.length() / 1048576.0) + " MB)");
        } catch (Exception e) {
            log("Error: " + e.getMessage());
            Toast.makeText(this, "Cannot read APK", Toast.LENGTH_SHORT).show();
            enableActions(false, false, false);
        }
    }

    private void updateSelectedApp(Drawable icon, String name, boolean show) {
        selectedAppCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (icon != null) ivSelectedIcon.setImageDrawable(icon);
        tvSelectedApp.setText(name);
    }

    private void enableActions(boolean patch, boolean verify, boolean install) {
        btnPatch.setEnabled(patch);
        btnVerify.setEnabled(verify);
        btnInstall.setEnabled(install);
    }

    private void getDeviceIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                int ip = wi.getIpAddress();
                deviceIp = String.format(Locale.US, "%d.%d.%d.%d",
                    ip & 0xff, (ip >> 8) & 0xff,
                    (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception e) {
            deviceIp = "127.0.0.1";
        }
        tvApiUrl.setText("API: " + deviceIp + ":8443");
        tvAdbInfo.setText("ADB: " + deviceIp + ":5555");
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void toggleService() {
        Intent intent = new Intent(this, RootProviderService.class);
        if (!serviceRunning) {
            startServiceCompat(intent);
            serviceRunning = true;
            updateServiceUI(true);
            log("✓ Service started | API: " + deviceIp + ":8443");
        } else {
            stopService(intent);
            serviceRunning = false;
            updateServiceUI(false);
            log("✗ Service stopped");
        }
    }

    private void updateServiceUI(boolean running) {
        if (running) {
            btnStartStop.setText(R.string.stop_service);
            btnStartStop.setBackgroundTintList(
                ContextCompat.getColorStateList(this,
                    android.R.color.holo_red_light));
            tvServiceStatus.setText(R.string.service_active);
            tvServiceStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_light));
        } else {
            btnStartStop.setText(R.string.start_service);
            btnStartStop.setBackgroundTintList(
                ContextCompat.getColorStateList(this,
                    android.R.color.holo_green_light));
            tvServiceStatus.setText(R.string.service_stopped);
            tvServiceStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_light));
        }
    }

    private void startPatching() {
        if (selectedApkPath == null) {
            Toast.makeText(this, "No APK selected", Toast.LENGTH_SHORT).show();
            return;
        }
        String lower = new File(selectedApkPath).getName().toLowerCase();
        if (lower.contains("play") || lower.contains("store") ||
            lower.contains("google") || lower.co
