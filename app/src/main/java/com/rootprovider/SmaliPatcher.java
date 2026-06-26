package com.rootprovider;

import android.util.Log;
import java.io.*;

/**
 * Patches smali bytecode in a decompiled APK to load Frida gadget
 * and sets the debuggable flag in AndroidManifest.xml.
 */
public class SmaliPatcher {

    private static final String TAG = "SmaliPatcher";

    private final File decompiledDir;

    public SmaliPatcher(File decompiledDir) {
        this.decompiledDir = decompiledDir;
    }

    /**
     * Find all smali directories and inject FridaLoader.load() call
     * into the first Activity's onCreate method.
     */
    public void patchSmali() throws IOException {
        for (File dir : decompiledDir.listFiles()) {
            if (dir != null && dir.getName().startsWith("smali")) {
                findAndPatchActivity(dir);
            }
        }
    }

    private void findAndPatchActivity(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                findAndPatchActivity(f);
            } else if (f.getName().endsWith(".smali")) {
                String content = readFile(f);
                // Look for Activity.onCreate and inject FridaLoader.load() after super call
                if (content.contains("onCreate(Landroid/os/Bundle;)V") &&
                    !content.contains("fridagadget")) {
                    content = content.replace(
                        "invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V",
                        "invoke-static {}, Lcom/rootprovider/FridaLoader;->load()V\n" +
                        "    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V"
                    );
                    writeFile(f, content);
                    Log.d(TAG, "Patched: " + f.getName());
                }
            }
        }
    }

    /**
     * Set android:debuggable="true" in AndroidManifest.xml.
     */
    public void setDebuggable() throws IOException {
        File manifest = new File(decompiledDir, "AndroidManifest.xml");
        if (!manifest.exists()) return;

        String content = readFile(manifest);
        content = content.replace("android:debuggable=\"false\"", "android:debuggable=\"true\"");
        if (!content.contains("android:debuggable")) {
            content = content.replace("<application ", "<application android:debuggable=\"true\" ");
        }
        writeFile(manifest, content);
    }

    private String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void writeFile(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        }
    }
}
