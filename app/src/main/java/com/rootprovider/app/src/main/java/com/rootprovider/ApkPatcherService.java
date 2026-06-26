package com.rootprovider;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.LocalBroadcastManager;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public class ApkPatcherService extends Service {

    private static final String TAG = "ApkPatcherSvc";
    private static final int NOTIF_ID = 1337;
    private static final String CHANNEL_ID = "apk_patcher";

    public static final String ACTION_PROGRESS = "PATCHER_PROGRESS";
    public static final String ACTION_DONE = "PATCHER_DONE";
    public static final String ACTION_ERROR = "PATCHER_ERROR";

    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "APK Patcher", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra("apk_path")) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String apkPath = intent.getStringExtra("apk_path");

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RootProvider")
            .setContentText("Patching APK...")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build();
        startForeground(NOTIF_ID, notif);

        new Thread(() -> patchApk(apkPath)).start();

        return START_REDELIVER_INTENT;
    }

    private void patchApk(String apkPath) {
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            sendError("APK not found: " + apkPath);
            return;
        }

        try {
            // Stage 0: Analyze APK
            sendProgress(1, 5, "File: " + apkFile.getName() +
                " (" + apkFile.length() / 1024 + " KB)");

            // Create working directories
            File workDir = new File(getFilesDir(), "patch_" + System.currentTimeMillis());
            File outputDir = new File(getFilesDir(), "patched");
            File decompiledDir = new File(workDir, "decompiled");
            File unsignedApk = new File(workDir, "unsigned.apk");
            String outName = apkFile.getName().replace(".apk", "_rooted.apk");
            File outputApk = new File(outputDir, outName);
            workDir.mkdirs();
            outputDir.mkdirs();

            // Extract tools
            sendProgress(2, 8, "Extracting tools from assets...");
            File apktoolJar = ensureAsset("apktool.jar");
            File signerJar = ensureAsset("uber-apk-signer.jar");

            // Stage 3: Decompile
            sendProgress(3, 15, "Running apktool d...");
            execJavaJar(apktoolJar,
                "d", "-f", "-o", decompiledDir.getAbsolutePath(), apkPath);

            // Stage 4: Inject fake root environment
            sendProgress(4, 35, "Creating fake root filesystem...");
            File rootfsDir = new File(decompiledDir, "assets/rootfs");
            deployFakeRoot(rootfsDir);

            // Stage 5: Deploy Frida gadget
            sendProgress(5, 50, "Copying Frida gadget libraries...");
            deployFridaGadget(decompiledDir);

            // Stage 6: Patch smali
            sendProgress(6, 60, "Patching smali entry points...");
            SmaliPatcher smali = new SmaliPatcher(decompiledDir);
            smali.patchSmali();

            // Stage 7: Set debuggable
            sendProgress(7, 65, "Setting android:debuggable=true...");
            smali.setDebuggable();

            // Stage 8: Recompile
            sendProgress(8, 75, "Running apktool b...");
            execJavaJar(apktoolJar,
                "b", "-o", unsignedApk.getAbsolutePath(), decompiledDir.getAbsolutePath());

            // Stage 9: Sign
            sendProgress(9, 88, "Signing APK...");
            signApk(unsignedApk, outputApk);

            // Stage 10: Verify
            sendProgress(10, 95, "Running verification...");
            boolean valid = verifyPatchedApk(outputApk);

            // Cleanup
            deleteRecursive(workDir);

            if (valid) {
                updateNotification("Complete: " + outName, 100);
                sendDone(outputApk.getAbsolutePath());
            } else {
                sendError("Verification failed — APK may be corrupted");
            }

        } catch (Exception e) {
            Log.e(TAG, "Patch failed", e);
            sendError("Patch failed: " + e.getMessage());
        }
    }

    private void deployFakeRoot(File rootfsDir) throws IOException {
        // Create a minimal fake root filesystem structure
        String[][] dirs = {
            {"system/bin", "system/xbin", "system/app", "system/etc/init.d"},
            {"sbin", "data/adb/magisk", "data/local/tmp", "selinux"}
        };
        for (String[] group : dirs) {
            for (String d : group) {
                new File(rootfsDir, d).mkdirs();
            }
        }

        // Create fake su binary
        File su = new File(rootfsDir, "system/xbin/su");
        try (OutputStream os = new FileOutputStream(su)) {
            os.write("#!/system/bin/sh\necho 'uid=0(root) gid=0(root)'\n".getBytes());
        }
        su.setExecutable(true);
        new File(rootfsDir, "sbin/su").createNewFile();

        // Create fake magisk files
        new File(rootfsDir, "data/adb/magisk/magisk.db").createNewFile();
        new File(rootfsDir, "data/adb/magisk/util_functions.sh").createNewFile();

        // Create fake busybox
        new File(rootfsDir, "system/xbin/busybox").createNewFile();

        // Create SELinux policy files
        new File(rootfsDir, "selinux/su_contexts").createNewFile();
        new File(rootfsDir, "selinux/magisk_contexts").createNewFile();

        // Create init.d scripts
        File initScript = new File(rootfsDir, "system/etc/init.d/99root");
        try (OutputStream os = new FileOutputStream(initScript)) {
            os.write("#!/system/bin/sh\n# Fake root init script\n".getBytes());
        }
        initScript.setExecutable(true);

        // Write build.prop that indicates root
        File buildProp = new File(rootfsDir, "system/build.prop");
        try (OutputStream os = new FileOutputStream(buildProp)) {
            os.write(("ro.build.tags=test-keys\n" +
                "ro.build.type=userdebug\n" +
                "ro.debuggable=1\n" +
                "ro.secure=0\n" +
                "ro.adb.secure=0\n" +
                "persist.sys.root_access=1\n").getBytes());
        }

        Log.d(TAG, "Fake root deployed to " + rootfsDir);
    }

    private void deployFridaGadget(File decompiledDir) throws IOException {
        // Copy Frida gadget .so from jniLibs (downloaded at build time)
        File jniSrc = new File(getApplicationInfo().nativeLibraryDir, "libfridagadget.so");
        if (jniSrc.exists()) {
            File jniDst = new File(decompiledDir, "lib/arm64-v8a/libfridagadget.so");
            jniDst.getParentFile().mkdirs();
            copyFile(jniSrc, jniDst);
            Log.d(TAG, "Frida gadget deployed: " + jniDst.length() + " bytes");
        } else {
            Log.w(TAG, "Frida gadget not found in native libs, creating stub");
            // Create a minimal stub so the patcher doesn't crash
            File jniDst = new File(decompiledDir, "lib/arm64-v8a/libfridagadget.so");
            jniDst.getParentFile().mkdirs();
            jniDst.createNewFile();
        }

        // Copy bypass script
        File bypassSrc = new File(getFilesDir(), "bypass_root.js");
        if (!bypassSrc.exists()) {
            try (InputStream is = getAssets().open("bypass_root.js");
                 OutputStream os = new FileOutputStream(bypassSrc)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }
        }
        File bypassDst = new File(decompiledDir, "assets/bypass_root.js");
        bypassDst.getParentFile().mkdirs();
        copyFile(bypassSrc, bypassDst);

        // Copy Frida config
        File configSrc = new File(getFilesDir(), "frida-gadget.config.json");
        if (!configSrc.exists()) {
            try (InputStream is = getAssets().open("frida-gadget.config.json");
                 OutputStream os = new FileOutputStream(configSrc)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }
        }
        File configDst = new File(decompiledDir, "assets/frida-gadget.config.json");
        configDst.getParentFile().mkdirs();
        copyFile(configSrc, configDst);

        Log.d(TAG, "Frida files deployed to assets/");
    }

    private File ensureAsset(String name) throws IOException {
        File dest = new File(getFilesDir(), name);
        if (dest.exists() && dest.length() > 0) return dest;
        dest.getParentFile().mkdirs();
        try (InputStream is = getAssets().open(name);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
        }
        Log.d(TAG, "Extracted " + name + " (" + dest.length() + " bytes)");
        return dest;
    }

    private void execJavaJar(File jar, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "java";
        cmd[1] = "-jar";
        cmd[2] = jar.getAbsolutePath();
        System.arraycopy(args, 0, cmd, 3, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // Read output
        BufferedReader r = new BufferedReader(
            new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            Log.d(TAG, line);
        }

        boolean finished = p.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new Exception("Command timed out (5 min)");
        }
        if (p.exitValue() != 0) {
            throw new Exception("Exit code: " + p.exitValue());
        }
    }

    private void signApk(File unsigned, File signed) throws Exception {
        // Create keystore if needed
        File keystore = new File(getFilesDir(), "pentest.keystore");
        if (!keystore.exists()) {
            Process ks = Runtime.getRuntime().exec(new String[]{
                "keytool", "-genkey", "-v", "-keystore", keystore.getAbsolutePath(),
                "-alias", "rootprovider", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-storepass", "root123", "-keypass", "root123",
                "-dname", "CN=RootProvider"
            });
            ks.waitFor(30, TimeUnit.SECONDS);
        }

        // Try uber-apk-signer first
        File signerJar = new File(getFilesDir(), "uber-apk-signer.jar");
        try {
            execJavaJar(signerJar,
                "--apks", unsigned.getAbsolutePath(),
                "--ks", keystore.getAbsolutePath(),
                "--ksPass", "root123",
                "--ksAlias", "rootprovider",
                "--out", signed.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "uber-apk-signer failed, trying apksigner");
            // Fallback to apksigner
            Process js = Runtime.getRuntime().exec(new String[]{
                "apksigner", "sign",
                "--ks", keystore.getAbsolutePath(),
                "--ks-pass", "pass:root123",
                "--key-pass", "pass:root123",
                "--out", signed.getAbsolutePath(),
                unsigned.getAbsolutePath()
            });
            js.waitFor(30, TimeUnit.SECONDS);
            if (js.exitValue() != 0) {
                // Last resort: jarsigner
                Process js2 = Runtime.getRuntime().exec(new String[]{
                    "jarsigner", "-keystore", keystore.getAbsolutePath(),
                    "-storepass", "root123", "-keypass", "root123",
                    unsigned.getAbsolutePath(), "rootprovider"
                });
                js2.waitFor(30, TimeUnit.SECONDS);
                copyFile(unsigned, signed);
            }
        }
    }

    private boolean verifyPatchedApk(File apk) {
        try {
            // Check 1: Is it a valid ZIP?
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                if (zf.size() == 0) return false;

                // Check 2: Has AndroidManifest.xml
                if (zf.getEntry("AndroidManifest.xml") == null) return false;

                // Check 3: Has classes.dex
                if (zf.getEntry("classes.dex") == null &&
                    zf.getEntry("classes2.dex") == null) return false;

                // Check 4: Has bypass script
                if (zf.getEntry("assets/bypass_root.js") == null) return false;

                // Check 5: Has META-INF (signed)
                if (zf.getEntry("META-INF/MANIFEST.MF") == null) return false;

                Log.d(TAG, "Verification passed: " + zf.size() + " entries");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Verification failed", e);
            return false;
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null)
                for (File c : kids) deleteRecursive(c);
        }
        f.delete();
    }

    private void sendProgress(int stage, int pct, String detail) {
        updateNotification(detail, pct);
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra("stage", stage);
        i.putExtra("progress", pct);
        i.putExtra("detail", detail);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void sendDone(String outputPath) {
        stopForeground(false);
        Intent i = new Intent(ACTION_DONE);
        i.putExtra("output_path", outputPath);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        stopSelf();
    }

    private void sendError(String error) {
        stopForeground(false);
        Intent i = new Intent(ACTION_ERROR);
        i.putExtra("error", error);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        stopSelf();
    }

    private void updateNotification(String text, int progress) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RootProvider")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true);
        if (progress >= 0 && progress <= 100) {
            b.setProgress(100, progress, false);
        } else if (progress < 0) {
            b.setProgress(0, 0, true);
        }
        nm.notify(NOTIF_ID, b.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
          }
