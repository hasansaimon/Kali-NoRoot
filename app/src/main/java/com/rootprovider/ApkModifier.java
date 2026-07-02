package com.rootprovider;

import android.util.Log;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

import javax.security.auth.x500.X500Principal;

/**
 * Modifies APK files directly as ZIP archives without requiring Java runtime.
 * Handles: binary AXML patching (debuggable flag), file injection, and V1 JAR signing.
 *
 * This replaces the apktool-based pipeline which fails on Android (no java binary).
 */
public class ApkModifier {

    private static final String TAG = "ApkModifier";

    // Resource ID for android:debuggable = 0x0101000E
    private static final int RES_ID_DEBUGGABLE = 0x0101000E;

    // AXML chunk types
    private static final int CHUNK_AXML = 0x0003;
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RESOURCE_IDS = 0x0002;
    private static final int CHUNK_START_TAG = 0x0102;

    // Attribute value types
    private static final int ATTR_TYPE_BOOLEAN = 0x12;

    // META-INF directory keys to skip during copy
    private static final Set<String> META_SKIP = new HashSet<>(Arrays.asList(
        "META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA",
        "META-INF/CERT.DSA", "META-INF/CERT.EC",
        "META-INF/SIGN.RSA", "META-INF/SIGN.SF", "META-INF/SIGN.DSA",
        "META-INF/ANDROID.RSA", "META-INF/ANDROID.SF", "META-INF/ANDROID.DSA"
    ));

    private PrivateKey privateKey;
    private X509Certificate certificate;
    private boolean initialized = false;

    /**
     * Initialize the signer with a generated self-signed RSA key pair.
     */
    public void initSigner() throws Exception {
        if (initialized) return;

        // Generate RSA key pair (2048-bit)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        // Build self-signed X.509 certificate valid for 10 years
        org.bouncycastle.x509.X509V3CertificateGenerator certGen =
                new org.bouncycastle.x509.X509V3CertificateGenerator();

        long now = System.currentTimeMillis();
        certGen.setSerialNumber(new java.math.BigInteger(64, new SecureRandom()));
        certGen.setIssuerDN(new X500Principal("CN=RootProvider, O=RootProvider, C=US"));
        certGen.setNotBefore(new java.util.Date(now));
        certGen.setNotAfter(new java.util.Date(now + 365L * 10 * 24 * 3600 * 1000));
        certGen.setSubjectDN(new X500Principal("CN=RootProvider, O=RootProvider, C=US"));
        certGen.setPublicKey(kp.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");
        certGen.addExtension(org.bouncycastle.asn1.x509.X509Extensions.BasicConstraints,
                true, new org.bouncycastle.asn1.x509.BasicConstraints(true));

        this.certificate = certGen.generate(kp.getPrivate(), "BC");
        this.privateKey = kp.getPrivate();
        this.initialized = true;

        Log.d(TAG, "Signer initialized with self-signed RSA cert");
    }

    /**
     * Initialize signer with an existing keystore file.
     */
    public void initSigner(File keystoreFile, String password, String alias) throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            ks.load(fis, password.toCharArray());
        }
        this.privateKey = (PrivateKey) ks.getKey(alias, password.toCharArray());
        this.certificate = (X509Certificate) ks.getCertificate(alias);
        this.initialized = true;
        Log.d(TAG, "Signer initialized from keystore: " + alias);
    }

    /**
     * Main entry point: modify an APK with fake root + Frida gadget injection.
     *
     * @param inputApk      Original APK file
     * @param outputApk     Output patched APK (will be overwritten)
     * @param rootfsDir     Fake root filesystem directory to inject, or null
     * @param fridaLibPath  Path to libfridagadget.so, or null
     * @param bypassJsPath  Path to bypass_root.js, or null
     * @param fridaConfigPath Path to frida-gadget.config.json, or null
     * @throws Exception on failure
     */
    public void modifyApk(File inputApk, File outputApk,
                          File rootfsDir, String fridaLibPath,
                          String bypassJsPath, String fridaConfigPath) throws Exception {
        if (!initialized) initSigner();

        outputApk.getParentFile().mkdirs();

        // Phase 1: Read original APK and build new unsigned APK
        File unsignedApk = new File(outputApk.getParentFile(), "unsigned.tmp");

        Log.d(TAG, "Building unsigned APK: " + unsignedApk);

        // Read all entries from original
        Map<String, byte[]> originalEntries = new LinkedHashMap<>();
        byte[] manifestXml = null;
        boolean hasApplicationTag = false;

        try (ZipFile zf = new ZipFile(inputApk)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // Skip META-INF signature files
                if (META_SKIP.contains(name.toUpperCase(Locale.US))) continue;
                if (name.toUpperCase(Locale.US).startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") ||
                         name.endsWith(".DSA") || name.endsWith(".EC") ||
                         name.endsWith(".MF"))) continue;

                byte[] data = readEntry(zf, entry);

                // Capture AndroidManifest.xml for patching
                if (name.equals("AndroidManifest.xml")) {
                    manifestXml = data;
                    continue; // We'll write the patched version
                }

                originalEntries.put(name, data);

                if (name.contains("smali") && name.contains("Activity")) {
                    hasApplicationTag = true;
                }
            }
        }

        if (manifestXml == null) {
            throw new Exception("AndroidManifest.xml not found in APK");
        }

        // Phase 2: Patch AndroidManifest.xml (set debuggable=true)
        Log.d(TAG, "Patching AndroidManifest.xml debuggable flag...");
        byte[] patchedManifest = patchDebuggable(manifestXml);

        // Phase 3: Write the unsigned APK
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk))) {
            zos.setLevel(9);

            // Write patched manifest first
            writeEntry(zos, "AndroidManifest.xml", patchedManifest);

            // Write all original entries
            for (Map.Entry<String, byte[]> e : originalEntries.entrySet()) {
                writeEntry(zos, e.getKey(), e.getValue());
            }

            // Phase 4: Inject rootfs
            if (rootfsDir != null && rootfsDir.exists()) {
                Log.d(TAG, "Injecting rootfs from: " + rootfsDir);
                injectDirectory(zos, rootfsDir, "assets/rootfs/");
            } else {
                Log.d(TAG, "No rootfs to inject");
            }

            // Phase 5: Inject Frida gadget
            if (fridaLibPath != null) {
                File fridaFile = new File(fridaLibPath);
                if (fridaFile.exists()) {
                    Log.d(TAG, "Injecting Frida gadget: " + fridaLibPath);
                    writeEntry(zos, "lib/arm64-v8a/libfridagadget.so", readFile(fridaFile));
                    writeEntry(zos, "lib/armeabi-v7a/libfridagadget.so", readFile(fridaFile));
                    // Also try to get x86_64 from sibling path
                    String x8664Path = fridaLibPath.replace("arm64-v8a", "x86_64");
                    File x8664File = new File(x8664Path);
                    if (x8664File.exists()) {
                        writeEntry(zos, "lib/x86_64/libfridagadget.so", readFile(x8664File));
                    }
                } else {
                    Log.w(TAG, "Frida lib not found at: " + fridaLibPath);
                }
            }

            // Inject bypass_root.js
            if (bypassJsPath != null) {
                File jsFile = new File(bypassJsPath);
                if (jsFile.exists()) {
                    Log.d(TAG, "Injecting bypass_root.js");
                    writeEntry(zos, "assets/bypass_root.js", readFile(jsFile));
                }
            }

            // Inject frida-gadget.config.json
            if (fridaConfigPath != null) {
                File cfgFile = new File(fridaConfigPath);
                if (cfgFile.exists()) {
                    Log.d(TAG, "Injecting frida-gadget.config.json");
                    writeEntry(zos, "assets/frida-gadget.config.json", readFile(cfgFile));
                }
            }

            // Phase 6: Generate FridaLoader.smali (so the app loads the gadget)
            String fridaSmali = ".class public Lcom/rootprovider/FridaLoader;\n" +
                    ".super Ljava/lang/Object;\n" +
                    "\n" +
                    ".method public static load()V\n" +
                    "    .locals 1\n" +
                    "    const-string v0, \"fridagadget\"\n" +
                    "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n" +
                    "    return-void\n" +
                    ".end method\n";
            writeEntry(zos, "smali/com/rootprovider/FridaLoader.smali", fridaSmali.getBytes("UTF-8"));

            Log.d(TAG, "Unsigned APK written: " + unsignedApk.length() + " bytes");
        }

        // Phase 7: Sign the APK (V1 JAR signing)
        Log.d(TAG, "Signing APK...");
        signApk(unsignedApk, outputApk);

        // Cleanup
        unsignedApk.delete();

        Log.d(TAG, "Patched APK ready: " + outputApk.getAbsolutePath() +
                " (" + outputApk.length() + " bytes)");
    }

    // ========================================================================
    // Binary AXML Patching
    // ========================================================================

    /**
     * Patches the binary AndroidManifest.xml to set android:debuggable="true".
     * If the attribute exists, changes its value to true.
     * If it doesn't exist, adds it to the application tag.
     */
    private byte[] patchDebuggable(byte[] axml) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);

        // Read AXML header
        int chunkType = buf.getShort() & 0xFFFF;
        if (chunkType != CHUNK_AXML) {
            throw new Exception("Not a valid AXML file (type: " +
                    Integer.toHexString(chunkType) + ")");
        }

        // Skip header size + chunk size
        buf.getShort(); // header size
        buf.getInt();   // chunk size

        // Parse chunks until we find the application tag
        byte[] result = axml.clone();
        result = patchApplicationTag(result);
        return result;
    }

    private byte[] patchApplicationTag(byte[] axml) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);

        // We need to decode and find:
        // 1. String pool (for namespace/name lookups)
        // 2. Resource IDs (to map attribute name strings to resource IDs)
        // 3. Start tags (to find <application>)
        //
        // Then modify the debuggable attribute value.

        int offset = 0;

        // Read AXML header
        int chunkType = readShort(axml, offset); offset += 2;
        int headerSize = readShort(axml, offset); offset += 2;
        int chunkSize = readInt(axml, offset); offset += 4;

        if (chunkType != CHUNK_AXML) {
            throw new Exception("Not AXML: type=0x" + Integer.toHexString(chunkType));
        }

        // String pool state
        int stringPoolOffset = -1;
        int stringPoolSize = 0;
        int stringCount = 0;
        int styleCount = 0;
        int flags = 0;
        int stringsOffset = 0;
        int stylesOffset = 0;

        // Resource IDs state  
        int resIdOffset = -1;
        int resIdCount = 0;

        // Find the string pool and resource ID chunks
        int pos = offset;
        while (pos < axml.length) {
            if (pos + 8 > axml.length) break;
            int type = readShort(axml, pos);
            int size = readInt(axml, pos + 4);

            if (type == CHUNK_STRING_POOL) {
                stringPoolOffset = pos;
                stringPoolSize = size;
                // Parse string pool header
                stringCount = readInt(axml, pos + 8);
                styleCount = readInt(axml, pos + 12);
                flags = readInt(axml, pos + 16);
                stringsOffset = readInt(axml, pos + 20);
                stylesOffset = readInt(axml, pos + 24);
            } else if (type == CHUNK_RESOURCE_IDS) {
                resIdOffset = pos;
                resIdCount = (size - 8) / 4;
            }

            if (size <= 0) break;
            pos += size;
        }

        if (stringPoolOffset < 0) {
            throw new Exception("No string pool found in AXML");
        }

        // Get the android namespace URI string index
        // The android namespace is usually "http://schemas.android.com/apk/res/android"
        int androidNsUriIdx = -1;
        String androidNs = "http://schemas.android.com/apk/res/android";
        for (int i = 0; i < stringCount; i++) {
            String s = getString(axml, stringPoolOffset, i, flags);
            if (androidNs.equals(s)) {
                androidNsUriIdx = i;
                break;
            }
        }

        // Now parse content chunks to find the <application> start tag
        pos = offset;
        while (pos < axml.length) {
            if (pos + 8 > axml.length) break;
            int type = readShort(axml, pos);
            int size = readInt(axml, pos + 4);

            if (type == CHUNK_START_TAG) {
                int nameIdx = readInt(axml, pos + 20); // name is at offset 20 from chunk start
                String tagName = getString(axml, stringPoolOffset, nameIdx, flags);

                if ("application".equals(tagName)) {
                    return patchApplicationDebuggable(axml, pos, size, stringPoolOffset, flags);
                }
            }

            if (size <= 0) break;
            pos += size;
        }

        // Didn't find application tag — just return original
        Log.w(TAG, "No <application> tag found, returning original manifest");
        return axml;
    }

    /**
     * Patches the debuggable attribute in the <application> start tag.
     * The start tag structure (positions relative to chunk start):
     *   [0-3]   namespaceUri (string pool index)
     *   [4-7]   name (string pool index)
     *   [8-9]   attributeStart (offset from chunk start to first attribute)
     *   [10-11] attributeSize (size of each attribute)
     *   [12-13] attributeCount
     *   [14-15] idIndex
     *   [16-17] classIndex
     *   [18-19] styleIndex
     *   [20+]   attributes (each attributeSize bytes)
     *
     * Each attribute:
     *   [0-3]   namespaceUri (string pool index)
     *   [4-7]   name (string pool index)
     *   [8-11]  valueString (string pool index, 0xFFFFFFFF for non-string)
     *   [12-15] valueType
     *   [16-19] valueData
     */
    private byte[] patchApplicationDebuggable(byte[] axml, int tagOffset, int tagSize,
                                               int stringPoolOffset, int flags) {
        int attributeStart = readShort(axml, tagOffset + 8);
        int attributeSize = readShort(axml, tagOffset + 10);
        int attributeCount = readShort(axml, tagOffset + 12);

        // Map attribute name string indices to resource IDs
        // Resource IDs are stored in a separate chunk where index i in the
        // resource ID array corresponds to string pool index i

        boolean foundDebuggable = false;
        int debuggableAttrOffset = -1;

        for (int i = 0; i < attributeCount; i++) {
            int attrOff = tagOffset + attributeStart + i * attributeSize;
            if (attrOff + 20 > axml.length) break;

            int nameIdx = readInt(axml, attrOff + 4);

            // Check if this attribute is "debuggable" by looking up resource ID
            int resId = getResourceIdForString(axml, nameIdx, stringPoolOffset, flags);

            if (resId == RES_ID_DEBUGGABLE) {
                foundDebuggable = true;
                debuggableAttrOffset = attrOff;
                break;
            }
        }

        byte[] result = axml.clone();

        if (foundDebuggable && debuggableAttrOffset >= 0) {
            // Found debuggable attribute, change value to true
            // Value type is at offset +12, value data at offset +16
            int valueTypeOffset = debuggableAttrOffset + 12;
            int valueDataOffset = debuggableAttrOffset + 16;

            // Verify it's a boolean type
            int currentType = readInt(result, valueTypeOffset);
            if (currentType == ATTR_TYPE_BOOLEAN) {
                // Change from false (0) to true (0xFFFFFFFF)
                writeInt(result, valueDataOffset, 0xFFFFFFFF);
                Log.d(TAG, "Set android:debuggable=true (was boolean attr)");
            } else {
                // Not a boolean, force-set it
                writeInt(result, valueTypeOffset, ATTR_TYPE_BOOLEAN);
                writeInt(result, valueDataOffset, 0xFFFFFFFF);
                Log.d(TAG, "Force-set android:debuggable=true (changed type from " +
                        Integer.toHexString(currentType) + ")");
            }
        } else {
            // debuggable attribute not found — need to add it
            // This is complex: we need to insert a new attribute into the start tag
            // Since attributeSize is typically 20 bytes, we need to expand the chunk
            Log.d(TAG, "android:debuggable not found, attempting to add it...");

            // Find or add "debuggable" to string pool, find or add android namespace
            // For simplicity, let's use a hybrid approach:
            // Search for "debuggable" in existing strings and add the attribute
            result = addDebuggableAttribute(result, tagOffset, tagSize,
                    stringPoolOffset, flags, attributeStart, attributeSize, attributeCount);
        }

        return result;
    }

    /**
     * Adds a debuggable=true attribute to the application start tag.
     * This requires expanding the chunk to make room for the new attribute (20 bytes).
     */
    private byte[] addDebuggableAttribute(byte[] axml, int tagOffset, int tagSize,
                                           int stringPoolOffset, int flags,
                                           int attributeStart, int attributeSize,
                                           int attributeCount) {
        // We need to insert 20 bytes (one attribute) into the start tag chunk
        // Find the index of "debuggable" in the string pool, or find a close match
        int debuggableStrIdx = findOrAddString(axml, "debuggable", stringPoolOffset, flags);
        int androidNsStrIdx = findOrAddString(axml,
                "http://schemas.android.com/apk/res/android", stringPoolOffset, flags);

        if (debuggableStrIdx < 0) {
            Log.w(TAG, "Cannot add debuggable: string pool manipulation failed");
            return axml;
        }
        if (androidNsStrIdx < 0) {
            Log.w(TAG, "Cannot add debuggable: no android namespace found");
            return axml;
        }

        // Now insert the attribute. The attribute goes at:
        // tagOffset + attributeStart + attributeCount * attributeSize
        // We need to shift everything after this point by 20 bytes
        int insertOffset = tagOffset + attributeStart + attributeCount * attributeSize;

        // The new attribute data (20 bytes):
        ByteBuffer attrBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        attrBuf.putInt(androidNsStrIdx);  // namespace URI
        attrBuf.putInt(debuggableStrIdx); // name
        attrBuf.putInt(0xFFFFFFFF);        // valueString (not a string)
        attrBuf.putInt(ATTR_TYPE_BOOLEAN); // valueType
        attrBuf.putInt(0xFFFFFFFF);        // valueData (true)

        byte[] newAxml = new byte[axml.length + 20];
        System.arraycopy(axml, 0, newAxml, 0, insertOffset);
        System.arraycopy(attrBuf.array(), 0, newAxml, insertOffset, 20);
        System.arraycopy(axml, insertOffset, newAxml, insertOffset + 20,
                axml.length - insertOffset);

        // Update the tag size and attribute count
        int newTagSize = tagSize + 20;
        writeInt(newAxml, tagOffset + 4, newTagSize);     // update chunk size
        int newAttrCount = attributeCount + 1;
        writeShort(newAxml, tagOffset + 12, newAttrCount); // update attribute count

        Log.d(TAG, "Added android:debuggable=true attribute");
        return newAxml;
    }

    /**
     * Finds a string in the AXML string pool, or adds it if possible.
     * Returns the string pool index, or -1 on failure.
     */
    private int findOrAddString(byte[] axml, String target, int spOffset, int flags) {
        // First try to find existing
        int count = readInt(axml, spOffset + 8);
        for (int i = 0; i < count; i++) {
            String s = getString(axml, spOffset, i, flags);
            if (target.equals(s)) return i;
        }

        // Not found — we can't easily add strings to the pool without
        // complex manipulation. Try to find a close match for common cases.
        // For "debuggable", it's almost always in the pool already.
        Log.w(TAG, "String '" + target + "' not found in pool");
        return -1;
    }

    /**
     * Gets the resource ID for a string pool index.
     * Resource IDs are stored in a separate chunk where the array index
     * corresponds to the string pool index.
     */
    private int getResourceIdForString(byte[] axml, int strIdx,
                                        int spOffset, int flags) {
        // Scan for resource ID chunk
        int pos = 0;
        while (pos + 8 <= axml.length) {
            int type = readShort(axml, pos);
            int size = readInt(axml, pos + 4);
            if (type == CHUNK_RESOURCE_IDS) {
                if (strIdx >= 0 && strIdx < (size - 8) / 4) {
                    return readInt(axml, pos + 8 + strIdx * 4);
                }
                return -1;
            }
            if (size <= 0) break;
            pos += size;
        }
        return -1;
    }

    /**
     * Reads a string from the AXML string pool at the given index.
     */
    private String getString(byte[] axml, int spOffset, int index, int flags) {
        int count = readInt(axml, spOffset + 8);
        if (index < 0 || index >= count) return null;

        int stringsOffsetVal = readInt(axml, spOffset + 20);
        int stringOffsets = spOffset + 28; // after 7 int32 headers

        int strOffset = readInt(axml, stringOffsets + index * 4);
        int strStart = spOffset + stringsOffsetVal + strOffset;

        // Check if UTF-8 or UTF-16 based on flags bit 8
        boolean isUtf8 = (flags & (1 << 8)) != 0;

        if (isUtf8) {
            // UTF-8: each string has 1 or 2 byte length prefix
            int len = axml[strStart] & 0xFF;
            if ((len & 0x80) != 0) {
                len = ((len & 0x7F) << 8) | (axml[strStart + 1] & 0xFF);
                strStart += 2;
            } else {
                strStart += 1;
            }
            // Skip the extended length if present
            if (axml[strStart] >= 0) {
                // 1-byte character length (skip)
                strStart += 1;
            } else {
                // 2-byte character length
                strStart += 2;
            }
            try {
                int end = strStart;
                while (end < axml.length && axml[end] != 0) end++;
                return new String(axml, strStart, end - strStart, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        } else {
            // UTF-16
            int len = readShort(axml, strStart) & 0xFFFF;
            strStart += 2;
            // Strings are null-terminated
            try {
                String s = new String(axml, strStart, len * 2, "UTF-16LE");
                // Remove null terminator if present
                int nullIdx = s.indexOf('\u0000');
                return nullIdx >= 0 ? s.substring(0, nullIdx) : s;
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ========================================================================
    // APK Signing (V1 JAR Signing)
    // ========================================================================

    /**
     * Sign an APK with V1 JAR signing (MANIFEST.MF + CERT.SF + CERT.RSA).
     * Uses BouncyCastle for PKCS7/CMS signature generation.
     */
    private void signApk(File unsignedApk, File signedApk) throws Exception {
        // Step 1: Generate MANIFEST.MF with SHA-256 digests of all files
        MessageDigest md = MessageDigest.getInstance("SHA-256");

        // Read all entries
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(unsignedApk)) {
            Enumeration<? extends ZipEntry> zipEnum = zf.entries();
            while (zipEnum.hasMoreElements()) {
                ZipEntry entry = zipEnum.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // Skip META-INF directory
                if (name.startsWith("META-INF/")) continue;
                entries.put(name, readEntry(zf, entry));
            }
        }

        // Build MANIFEST.MF
        StringBuilder manifestSb = new StringBuilder();
        manifestSb.append("Manifest-Version: 1.0\r\n");
        manifestSb.append("Created-By: RootProvider\r\n");
        manifestSb.append("\r\n");

        // Track individual entry digests for CERT.SF
        Map<String, String> entryDigests = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            byte[] data = entry.getValue();

            // Calculate SHA-256 digest
            md.reset();
            byte[] digest = md.digest(data);
            String b64Digest = Base64.getEncoder().encodeToString(digest);

            // Write to manifest
            manifestSb.append("Name: ").append(name).append("\r\n");
            manifestSb.append("SHA-256-Digest: ").append(b64Digest).append("\r\n");
            manifestSb.append("\r\n");

            entryDigests.put(name, b64Digest);
        }

        byte[] manifestBytes = manifestSb.toString().getBytes("UTF-8");

        // Compute digest of manifest
        md.reset();
        byte[] manifestDigest = md.digest(manifestBytes);
        String manifestB64 = Base64.getEncoder().encodeToString(manifestDigest);

        // Build CERT.SF
        StringBuilder sfSb = new StringBuilder();
        sfSb.append("Signature-Version: 1.0\r\n");
        sfSb.append("Created-By: RootProvider\r\n");
        sfSb.append("SHA-256-Digest-Manifest: ").append(manifestB64).append("\r\n");
        sfSb.append("\r\n");

        for (Map.Entry<String, String> entry : entryDigests.entrySet()) {
            sfSb.append("Name: ").append(entry.getKey()).append("\r\n");
            sfSb.append("SHA-256-Digest: ").append(entry.getValue()).append("\r\n");
            sfSb.append("\r\n");
        }

        byte[] sfBytes = sfSb.toString().getBytes("UTF-8");

        // Step 3: Sign CERT.SF and create CERT.RSA (PKCS7)
        // Calculate signature of SF
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signature = sig.sign();

        // Create PKCS7 SignedData using BouncyCastle
        byte[] pkcs7 = createPKCS7(signature, sfBytes);

        // Step 4: Build the signed APK
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(signedApk))) {
            zos.setLevel(9);

            // Write META-INF/MANIFEST.MF
            writeEntry(zos, "META-INF/MANIFEST.MF", manifestBytes);

            // Write META-INF/CERT.SF
            writeEntry(zos, "META-INF/CERT.SF", sfBytes);

            // Write META-INF/CERT.RSA
            writeEntry(zos, "META-INF/CERT.RSA", pkcs7);

            // Copy all original entries
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writeEntry(zos, entry.getKey(), entry.getValue());
            }
        }

        Log.d(TAG, "APK signed successfully");
    }

    /**
     * Creates a PKCS7/CMS SignedData structure using BouncyCastle.
     * This wraps a raw RSA signature + certificate into the PKCS7 format
     * required for JAR signing (CERT.RSA).
     */
    private byte[] createPKCS7(byte[] signature, byte[] signedContent) throws Exception {
        // Use BouncyCastle's CMS API (correct usage)
        java.util.List<X509Certificate> certList = new ArrayList<>();
        certList.add(certificate);

        org.bouncycastle.cms.CMSSignedDataGenerator generator = new org.bouncycastle.cms.CMSSignedDataGenerator();

        // Digest calculator provider
        org.bouncycastle.operator.DigestCalculatorProvider digProv =
            new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder()
                .setProvider("BC").build();

        org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder signerBuilder =
            new org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(digProv);

        // Build a ContentSigner from the private key
        org.bouncycastle.operator.ContentSigner contentSigner =
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(privateKey);

        generator.addSignerInfoGenerator(signerBuilder.build(contentSigner, certificate));

        // Add the certificate
        org.bouncycastle.cert.jcajce.JcaCertStore certStore = new org.bouncycastle.cert.jcajce.JcaCertStore(certList);
        generator.addCertificates(certStore);

        // Create CMS processable and generate signed data
        org.bouncycastle.cms.CMSProcessableByteArray processable = new org.bouncycastle.cms.CMSProcessableByteArray(signedContent);
        org.bouncycastle.cms.CMSSignedData signedData = generator.generate(processable, true);
        return signedData.getEncoded();
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private byte[] readEntry(ZipFile zf, ZipEntry entry) throws IOException {
        try (InputStream is = zf.getInputStream(entry)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) entry.getSize());
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
            return baos.toByteArray();
        }
    }

    private byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) file.length());
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    private void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setSize(data.length);
        entry.setCompressedSize(-1); // Let ZipOutputStream decide
        entry.setMethod(ZipEntry.DEFLATED);
        entry.setTime(System.currentTimeMillis());
        entry.setCrc(crc32(data));
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private void injectDirectory(ZipOutputStream zos, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                injectDirectory(zos, f, prefix + f.getName() + "/");
            } else {
                writeEntry(zos, prefix + f.getName(), readFile(f));
            }
        }
    }

    // Binary read helpers (little-endian)
    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeShort(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
