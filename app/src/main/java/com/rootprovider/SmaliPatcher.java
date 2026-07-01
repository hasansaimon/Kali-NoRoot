package com.rootprovider;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmaliPatcher {

    private static final String TAG = "SmaliPatcher";
    private final File decompiledDir;

    // Pattern to find main activity in AndroidManifest.xml
    private static final Pattern MAIN_ACTIVITY_PATTERN = Pattern.compile(
        "<activity\\s+android:name=\"([^\"]+)\"[^>]*" +
        "<intent-filter>\\s*<action\\s+android:name=\"android.intent.action.MAIN\""
    );

    // Pattern to find <application tag
    private static final Pattern APP_TAG_PATTERN = Pattern.compile(
        "<application([^>]*)>"
    );

    public SmaliPatcher(File decompiledDir) {
        this.decompiledDir = decompiledDir;
    }

    /**
     * Inject loadLibrary("frida-gadget") into the main activity's static
     * initializer so Frida gadget is loaded before any other code runs.
     */
    public void patchSmali() throws Exception {
        File manifestFile = new File(decompiledDir, "AndroidManifest.xml");
        String mainActivity = findMainActivity(manifestFile);
        if (mainActivity == null) {
            Log.w(TAG, "No main activity found, " +
                "skipping loadLibrary injection");
            return;
        }

        Log.d(TAG, "Main activity: " + mainActivity);
        File smaliFile = findSmaliFile(mainActivity);
        if (smaliFile == null || !smaliFile.exists()) {
            Log.w(TAG, "Smali file not found for " + mainActivity);
            return;
        }

        String smali = readFile(smaliFile);
        if (smali.contains("loadLibrary") &&
            smali.contains("\"frida-gadget\"")) {
            Log.d(TAG, "loadLibrary already injected, skipping");
            return;
        }

        String loadLibrarySmali =
            "\n    # RootProvider: inject Frida gadget loader\n" +
            "    const-string v0, \"frida-gadget\"\n" +
            "    invoke-static {v0}, Ljava/lang/System;->" +
            "loadLibrary(Ljava/lang/String;)V\n";

        // Inject at the beginning of <clinit> or at start of <init>
        String patched;
        if (smali.contains(".method static constructor <clinit>")) {
            patched = smali.replaceFirst(
                "(\\.method static constructor <clinit>\\s*\\n" +
                "\\s*\\.locals(?:\\s+\\d+)?\\s*\\n)",
                "$1" + loadLibrarySmali
            );
        } else if (smali.contains(".method public constructor <init>")) {
            patched = smali.replaceFirst(
                "(\\.method public constructor <init>\\s*\\n" +
                "\\s*\\.locals(?:\\s+\\d+)?\\s*\\n)",
                "$1" + loadLibrarySmali
            );
        } else {
            // No clinit/init — inject a new <clinit> at the top of class
            int classStart = smali.indexOf(".class");
            if (classStart < 0) {
                Log.w(TAG, "Malformed smali, skipping loadLibrary");
                return;
            }
            String clinit = "\n.method static constructor <clinit>()V\n" +
                "    .registers 1\n" +
                loadLibrarySmali +
                "    return-void\n" +
                ".end method\n";
            patched = smali.substring(0, classStart) + clinit +
                smali.substring(classStart);
        }

        writeFile(smaliFile, patched);
        Log.d(TAG, "loadLibrary('frida-gadget') injected into " +
            smaliFile.getName());
    }

    /**
     * Set android:debuggable="true" in AndroidManifest.xml so the patched
     * app is debuggable. This allows JDWP attach and Frida interactions.
     */
    public void setDebuggable() throws Exception {
        File manifestFile = new File(decompiledDir, "AndroidManifest.xml");
        if (!manifestFile.exists()) {
            Log.w(TAG, "AndroidManifest.xml not found");
            return;
        }
        String manifest = readFile(manifestFile);
        // If android:debuggable already exists, replace its value
        Pattern p = Pattern.compile(
            "android:debuggable=\"(true|false)\"");
        Matcher m = p.matcher(manifest);
        if (m.find()) {
            manifest = m.replaceFirst("android:debuggable=\"true\"");
        } else {
            // Inject into <application ...>
            manifest = manifest.replaceFirst(
                "(<application[^>]*?)>",
                "$1 android:debuggable=\"true\">"
            );
        }
        writeFile(manifestFile, manifest);
        Log.d(TAG, "android:debuggable=true set");
    }

    /**
     * Set android:extractNativeLibs="true" in AndroidManifest.xml so the
     * Frida gadget .so is properly extracted from the APK at install time.
     * Without this, the .so may not be loadable on Android 6+ devices.
     */
    public void setExtractNativeLibs() throws Exception {
        File manifestFile = new File(decompiledDir, "AndroidManifest.xml");
        if (!manifestFile.exists()) {
            Log.w(TAG, "AndroidManifest.xml not found");
            return;
        }
        String manifest = readFile(manifestFile);
        Pattern p = Pattern.compile(
            "android:extractNativeLibs=\"(true|false)\"");
        Matcher m = p.matcher(manifest);
        if (m.find()) {
            manifest = m.replaceFirst(
                "android:extractNativeLibs=\"true\"");
        } else {
            manifest = manifest.replaceFirst(
                "(<application[^>]*?)>",
                "$1 android:extractNativeLibs=\"true\">"
            );
        }
        writeFile(manifestFile, manifest);
        Log.d(TAG, "android:extractNativeLibs=true set");
    }

    private String findMainActivity(File manifestFile) throws Exception {
        String manifest = readFile(manifestFile);
        // First, look for activity with MAIN/LAUNCHER intent filter
        Pattern[] patterns = {
            // <activity ...> <intent-filter> <action MAIN> <category LAUNCHER>
            Pattern.compile(
                "<activity[^>]+android:name=\"([^\"]+)\"[^>]*>" +
                "[\\s\\S]*?<action android:name=\"android.intent.action.MAIN\"" +
                "[\\s\\S]*?<category android:name=\"android.intent.category.LAUNCHER\""
            ),
            // Simpler: any activity with MAIN action
            Pattern.compile(
                "<activity[^>]+android:name=\"([^\"]+)\"[^>]*>" +
                "[\\s\\S]*?<action android:name=\"android.intent.action.MAIN\""
            )
        };
        for (Pattern p : patterns) {
            Matcher m = p.matcher(manifest);
            if (m.find()) {
                String name = m.group(1);
                if (name.startsWith(".")) {
                    // Relative to package — need to resolve later
                    return name;
                }
                return name;
            }
        }
        return null;
    }

    private File findSmaliFile(String activityName) {
        // activityName may be:
        //   "com.example.MainActivity" → try smali/.../MainActivity.smali
        //   ".MainActivity" → resolve using package from manifest
        File smaliDir = new File(decompiledDir, "smali");
        if (!smaliDir.exists()) {
            smaliDir = new File(decompiledDir, "smali_classes2");
        }
        if (activityName.startsWith(".")) {
            // Read package from manifest
            try {
                File manifestFile = new File(decompiledDir,
                    "AndroidManifest.xml");
                String manifest = readFile(manifestFile);
                Pattern pkg = Pattern.compile("package=\"([^\"]+)\"");
                Matcher m = pkg.matcher(manifest);
                if (m.find()) {
                    activityName = m.group(1) + activityName;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to read manifest", e);
            }
        }
        String path = activityName.replace('.', '/') + ".smali";
        File f = new File(smaliDir, path);
        if (f.exists()) return f;

        // Try smali_classes2, 3, 4
        for (int i = 2; i <= 5; i++) {
            File alt = new File(decompiledDir, "smali_classes" + i);
            f = new File(alt, path);
            if (f.exists()) return f;
        }
        return null;
    }

    private String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private void writeFile(File f, String content) throws Exception {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f), "UTF-8"))) {
            w.write(content);
        }
    }
            }
