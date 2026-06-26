package com.rootprovider;

import android.app.IntentService;
import android.content.Intent;
import android.os.LocalBroadcastManager;
import android.util.Log;
import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * IntentService that patches an APK:
 * 1. Decompile with apktool
 * 2. Inject fake root filesystem (FakeRootEnvironment)
 * 3. Inject Frida bypass script + gadget (FridaInjector)
 * 4. Patch smali to load Frida at startup (SmaliPatcher)
 * 5. Set debuggable flag (SmaliPatcher)
 * 6. Recompile with apktool
 * 7. Sign with generated keystore
 */
public class ApkPatcher extends IntentService {

    public ApkPatcher() {
        super("ApkPatcher");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;

        String apkPath = intent.getStringExtra("apk_path");
        if (apkPath == null) return;

        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            sendError("APK not found: " + apkPath);
            return;
        }

        try {
            sendUpdate("Starting patch...", 0);

            File workDir = new File(getFilesDir(), "patch_" + System.currentTimeMillis());
            File outputDir = new File(getFilesDir(), "patched");
            outputDir.mkdirs();
            workDir.mkdirs();

            File decompiledDir = new File(workDir, "decompiled");
            File unsignedApk = new File(workDir, "unsigned.apk");
            String outputName = apkFile.getName().replace(".apk", "_rooted.apk");
            File outputApk = new File(outputDir, outputName);

            // Step 1: Extract tools from assets
            sendUpdate("Loading tools...", 5);
            File apktoolJar = new File(getFilesDir(), "apktool.jar");
            extractAsset("apktool.jar", apktoolJar);

            // Step 2: Decompile APK
            sendUpdate("Decompiling APK...", 10);
            execJavaJar(apktoolJar, "d", "-f", "-o",
                       decompiledDir.getAbsolutePath(), apkPath);

            // Step 3: Inject fake root filesystem
            sendUpdate("Injecting fake root environment...", 30);
            FakeRootEnvironment fakeRoot = new FakeRootEnvironment(
                new File(decompiledDir, "assets/rootfs"));
            fakeRoot.deploy();

            // Step 4: Inject Frida bypass script + config
            sendUpdate("Injecting Frida bypass...", 45);
            FridaInjector frida = new FridaInjector(decompiledDir, getAssets());
            frida.injectBypassScript();
            frida.generateFridaLoaderSmali();

            // Step 5: Patch smali to load Frida at startup
            sendUpdate("Patching smali...", 60);
            SmaliPatcher smali = new SmaliPatcher(decompiledDir);
            smali.patchSmali();

            // Step 6: Set debuggable flag
            sendUpdate("Setting debuggable...", 70);
            smali.setDebuggable();

            // Step 7: Recompile
            sendUpdate("Recompiling APK...", 80);
            execJavaJar(apktoolJar, "b", "-o",
                       unsignedApk.getAbsolutePath(), decompiledDir.getAbsolutePath());

            // Step 8: Sign
            sendUpdate("Signing APK...", 90);
            signApk(unsignedApk, outputApk);

            // Step 9: Cleanup & finish
            deleteRecursive(workDir);
            sendUpdate("Patch complete! Saved to: " + outputApk.getName(), 100);

            Intent done = new Intent("ROOT_PROVIDER_UPDATE");
            done.putExtra("message", "Patched APK: " + outputApk.getAbsolutePath());
            done.putExtra("output_path", outputApk.getAbsolutePath());
            done.putExtra("done", true);
            LocalBroadcastManager.getInstance(this).sendBroadcast(done);

        } catch (Exception e) {
            Log.e("ApkPatcher", "Patch failed", e);
            sendError("Patch failed: " + e.getMessage());
        }
    }

    private void signApk(File unsigned, File signed) throws Exception {
        File keystore = new File(getFilesDir(), "pentest.keystore");
        if (!keystore.exists()) {
            Process ksProc = Runtime.getRuntime().exec(new String[]{
                "keytool", "-genkey", "-v", "-keystore", keystore.getAbsolutePath(),
                "-alias", "rootprovider", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-storepass", "root123", "-keypass", "root123",
                "-dname", "CN=RootProvider"
            });
            ksProc.waitFor(30, TimeUnit.SECONDS);
        }

        File signerJar = new File(getFilesDir(), "uber-apk-signer.jar");
        extractAsset("uber-apk-signer.jar", signerJar);

        try {
            execJavaJar(signerJar, "--apks", unsigned.getAbsolutePath(),
                       "--ks", keystore.getAbsolutePath(),
                       "--ksPass", "root123",
                       "--ksAlias", "rootprovider",
                       "--out", signed.getAbsolutePath());
        } catch (Exception e) {
            Process jsProc = Runtime.getRuntime().exec(new String[]{
                "jarsigner", "-keystore", keystore.getAbsolutePath(),
                "-storepass", "root123", "-keypass", "root123",
                unsigned.getAbsolutePath(), "rootprovider"
            });
            jsProc.waitFor(30, TimeUnit.SECONDS);
            copyFile(unsigned, signed);
        }
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

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            Log.d("ApkPatcher", line);
        }

        boolean finished = p.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new Exception("Command timed out");
        }
        if (p.exitValue() != 0) {
            throw new Exception("Exit code: " + p.exitValue());
        }
    }

    private void extractAsset(String assetName, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        if (dest.exists() && dest.length() > 0) return;
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
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
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private void sendUpdate(String msg, int progress) {
        Intent intent = new Intent("ROOT_PROVIDER_UPDATE");
        intent.putExtra("message", msg);
        intent.putExtra("progress", progress);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendError(String msg) {
        Intent intent = new Intent("ROOT_PROVIDER_UPDATE");
        intent.putExtra("message", msg);
        intent.putExtra("error", true);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
