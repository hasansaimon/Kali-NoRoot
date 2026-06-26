package com.rootprovider;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.*;

/**
 * Handles Frida gadget injection into a decompiled APK.
 * Copies the bypass script and Frida config into the APK assets,
 * and generates the FridaLoader smali class.
 */
public class FridaInjector {

    private static final String TAG = "FridaInjector";

    private final File decompiledDir;
    private final AssetManager assetManager;

    public FridaInjector(File decompiledDir, AssetManager assetManager) {
        this.decompiledDir = decompiledDir;
        this.assetManager = assetManager;
    }

    /**
     * Inject Frida bypass script and config into target APK assets.
     */
    public void injectBypassScript() throws IOException {
        File assetsDir = new File(decompiledDir, "assets");
        assetsDir.mkdirs();

        // Copy bypass_root.js from our assets into the target APK
        File targetJs = new File(assetsDir, "bypass_root.js");
        extractAsset("bypass_root.js", targetJs);

        // Generate Frida gadget config
        File configFile = new File(assetsDir, "frida-gadget.config.json");
        writeFile(configFile,
            "{\"interaction\":{\"type\":\"listen\",\"address\":\"127.0.0.1:27042\"}," +
            "\"scripts\":{\"load\":[\"bypass_root.js\"]}}");
    }

    /**
     * Generate the FridaLoader.smali class that loads the fridagadget native library.
     */
    public void generateFridaLoaderSmali() throws IOException {
        File loaderDir = new File(decompiledDir, "smali/com/rootprovider");
        loaderDir.mkdirs();

        File loaderFile = new File(loaderDir, "FridaLoader.smali");
        writeFile(loaderFile,
            ".class public Lcom/rootprovider/FridaLoader;\n" +
            ".super Ljava/lang/Object;\n" +
            "\n" +
            ".method public static load()V\n" +
            "    .locals 1\n" +
            "    const-string v0, \"fridagadget\"\n" +
            "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n" +
            "    return-void\n" +
            ".end method\n");
    }

    private void extractAsset(String assetName, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        if (dest.exists() && dest.length() > 0) return;
        try (InputStream in = assetManager.open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private void writeFile(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        }
    }
}
