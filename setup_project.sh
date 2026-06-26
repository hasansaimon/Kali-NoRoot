#!/bin/bash
# Run this inside Kali-NoRoot directory to set up the project

set -e

echo "Setting up RootProvider project..."

# ============== app/build.gradle ==============
cat > app/build.gradle << 'GRADLEEOF'
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.rootprovider'
    compileSdk 34

    defaultConfig {
        applicationId "com.rootprovider"
        minSdk 26
        targetSdk 34
        versionCode 2
        versionName "2.0"
        
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            debuggable true
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }

    packagingOptions {
        exclude 'META-INF/DEPENDENCIES'
        exclude 'META-INF/LICENSE'
        exclude 'META-INF/LICENSE.txt'
        exclude 'META-INF/NOTICE'
        exclude 'META-INF/NOTICE.txt'
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.activity:activity:1.8.2'
    implementation 'androidx.core:core:1.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'dev.rikka.shizuku:api:13.1.5'
    implementation 'dev.rikka.shizuku:provider:13.1.5'
}

// Download Frida gadgets at build time
task downloadFridaGadgets {
    def archs = [
        'arm64-v8a': 'https://github.com/frida/frida/releases/download/16.2.1/frida-gadget-16.2.1-android-arm64.so.xz',
        'armeabi-v7a': 'https://github.com/frida/frida/releases/download/16.2.1/frida-gadget-16.2.1-android-arm.so.xz',
        'x86_64': 'https://github.com/frida/frida/releases/download/16.2.1/frida-gadget-16.2.1-android-x86_64.so.xz'
    ]
    
    def jniDir = file("src/main/jniLibs")
    doLast {
        jniDir.mkdirs()
        archs.each { arch, url ->
            def soFile = file("$jniDir/$arch/libfridagadget.so")
            if (!soFile.exists()) {
                println "Downloading Frida gadget for $arch..."
                def xzFile = file("${soFile.absolutePath}.xz")
                new URL(url).withInputStream { i -> xzFile.withOutputStream { it << i } }
                def proc = ["xz", "-d", "-f", xzFile.absolutePath].execute()
                proc.waitFor()
                if (!soFile.exists()) {
                    def inStream = new java.io.FileInputStream(xzFile)
                    def outStream = new java.io.FileOutputStream(soFile)
                    def buf = new byte[8192]
                    int len
                    while ((len = inStream.read(buf)) > 0) outStream.write(buf, 0, len)
                    inStream.close()
                    outStream.close()
                    xzFile.delete()
                }
                println "  → ${soFile.length()} bytes"
            }
        }
    }
}

task downloadApktool {
    def dest = file("src/main/assets/apktool.jar")
    doLast {
        if (!dest.exists()) {
            dest.parentFile.mkdirs()
            println "Downloading apktool..."
            new URL('https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar')
                .withInputStream { i -> dest.withOutputStream { it << i } }
        }
    }
}

task generateAssets {
    def assetsDir = file("src/main/assets")
    doLast {
        assetsDir.mkdirs()
        
        // Generate bypass_root.js
        def jsFile = file("$assetsDir/bypass_root.js")
        jsFile.text = '''\
'use strict';
Java.perform(function() {
    console.log("[RootProvider] Bypass loaded");
    
    var accessPtr = Module.findExportByName(null, "access");
    if(accessPtr) Interceptor.attach(accessPtr, {
        onEnter: function(a) { this.p = Memory.readUtf8String(a[0]); },
        onLeave: function(r) { if(this.p) { var l=this.p.toLowerCase(); if(l.indexOf("/su")>=0||l.indexOf("magisk")>=0||l.indexOf("superuser")>=0||l.indexOf("busybox")>=0) r.replace(0); } }
    });
    
    var statPtr = Module.findExportByName(null, "stat");
    if(statPtr) Interceptor.attach(statPtr, {
        onEnter: function(a) { this.p = Memory.readUtf8String(a[0]); },
        onLeave: function(r) { if(this.p) { var l=this.p.toLowerCase(); if(l.indexOf("/su")>=0||l.indexOf("magisk")>=0) r.replace(0); } }
    });
    
    var R = Java.use('java.lang.Runtime');
    R.exec.overload('[Ljava.lang.String;').implementation = function(a) {
        var c = a.join(" ");
        if(c.indexOf("su")>=0||c.indexOf("id")>=0) return Java.use('java.lang.ProcessBuilder').$new(["/system/bin/sh","-c","echo uid=0(root) gid=0(root) groups=0(root)"]).start();
        return this.exec(a);
    };
    R.exec.overload('java.lang.String').implementation = function(c) {
        if(c.indexOf("su")>=0||c.indexOf("id")>=0) return Java.use('java.lang.ProcessBuilder').$new(["/system/bin/sh","-c","echo uid=0(root) gid=0(root) groups=0(root)"]).start();
        return this.exec(c);
    };
    
    var PB = Java.use('java.lang.ProcessBuilder');
    PB.start.implementation = function() {
        var c = this.command().toString();
        if(c.indexOf("su")>=0||c.indexOf("id")>=0) return Java.use('java.lang.ProcessBuilder').$new(["/system/bin/sh","-c","echo uid=0(root) gid=0(root) groups=0(root)"]).start();
        return this.start();
    };
    
    var F = Java.use('java.io.File');
    F.exists.implementation = function() { var p=this.getAbsolutePath().toLowerCase(); if(p.indexOf("su")>=0||p.indexOf("magisk")>=0||p.indexOf("superuser")>=0||p.indexOf("busybox")>=0) return true; return this.exists(); };
    
    var B = Java.use('android.os.Build');
    B.getTags.implementation = function() { return "test-keys"; };
    B.getType.implementation = function() { return "userdebug"; };
    
    try {
        var SP = Java.use('android.os.SystemProperties');
        SP.get.overload('java.lang.String').implementation = function(k) {
            if(k==="ro.build.tags") return "test-keys";
            if(k==="ro.debuggable") return "1";
            if(k==="ro.secure") return "0";
            if(k==="persist.sys.root_access") return "3";
            return this.get(k);
        };
    } catch(e) {}
    
    var PM = Java.use('android.content.pm.PackageManager');
    var rootPkgs = ["com.topjohnwu.magisk","eu.chainfire.supersu","com.noshufou.android.su","com.thirdparty.superuser","com.stericson.busybox"];
    PM.getPackageInfo.overload('java.lang.String','int').implementation = function(p,f) {
        if(rootPkgs.indexOf(p)>=0) throw Java.use("android.content.pm.PackageManager$NameNotFoundException").$new(p+" not found");
        return this.getPackageInfo(p,f);
    };
    
    try {
        var SEL = Java.use('android.os.SELinux');
        SEL.isSELinuxEnabled.implementation = function() { return true; };
        SEL.isSELinuxEnforced.implementation = function() { return false; };
    } catch(e) {}
    
    console.log("[RootProvider] All hooks active");
});
'''
        
        // Generate frida-gadget.config.json
        def configFile = file("$assetsDir/frida-gadget.config.json")
        configFile.text = '{"interaction":{"type":"listen","address":"127.0.0.1:27042","on_port":"reuse"},"scripts":{"load":["bypass_root.js"],"on_change":"reload"}}'
        
        println "Assets generated"
    }
}

tasks.matching { it.name.startsWith('merge') && it.name.endsWith('Assets') }.all {
    it.dependsOn generateAssets, downloadApktool
}

tasks.matching { it.name.startsWith('merge') && it.name.endsWith('JniLibFolders') }.all {
    it.dependsOn downloadFridaGadgets
}
GRADLEEOF

# ============== settings.gradle ==============
cat > settings.gradle << 'SETTINGSEOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RootProvider"
include ':app'
SETTINGSEOF

# ============== gradle.properties ==============
cat > gradle.properties << 'PROPSEOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
PROPSEOF

# ============== .gitignore ==============
cat > .gitignore << 'GITIGNOREEOF'
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
local.properties
/app/build
/app/src/main/assets/apktool.jar
/app/src/main/jniLibs
*.apk
GITIGNOREEOF

# ============== AndroidManifest.xml ==============
cat > app/src/main/AndroidManifest.xml << 'MANIFESTOEF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.rootprovider">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="RootProvider"
        android:supportsRtl="true"
        android:theme="@style/Theme.MaterialComponents.DayNight.DarkActionBar"
        android:debuggable="true"
        android:requestLegacyExternalStorage="true"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/vnd.android.package-archive" />
            </intent-filter>
        </activity>

        <service
            android:name=".RootProviderService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />

        <provider
            android:name=".RootContentProvider"
            android:authorities="${applicationId}.root"
            android:exported="true" />

        <service
            android:name=".RootAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <provider
            android:name=".ApkFileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:grantUriPermissions="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

    </application>
</manifest>
MANIFESTOEF

# ============== res files ==============
mkdir -p app/src/main/res/xml
mkdir -p app/src/main/res/values

cat > app/src/main/res/xml/accessibility_service_config.xml << 'ACSXML'
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100" />
ACSXML

cat > app/src/main/res/xml/file_paths.xml << 'FPXML'
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-path name="external" path="." />
    <cache-path name="cache" path="." />
    <files-path name="files" path="." />
    <external-files-path name="external_files" path="." />
</paths>
FPXML

cat > app/src/main/res/values/strings.xml << 'STRXML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">RootProvider</string>
    <string name="accessibility_description">RootProvider monitors apps to inject fake root environment</string>
</resources>
STRXML

cat > app/src/main/res/values/themes.xml << 'THEMEXML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.RootProvider" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
        <item name="colorPrimary">#00E676</item>
        <item name="colorPrimaryDark">#009624</item>
        <item name="colorAccent">#FF5722</item>
        <item name="android:windowBackground">#121212</item>
        <item name="android:statusBarColor">#121212</item>
        <item name="android:navigationBarColor">#121212</item>
    </style>
</resources>
THEMEXML

echo ""
echo "=========================================="
echo "  Project structure created!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "  cd Kali-NoRoot"
echo "  gradle wrapper --gradle-version 8.1.1"
echo "  ./gradlew assembleDebug"
echo ""
