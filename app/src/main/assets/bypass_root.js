'use strict';

Java.perform(function() {
    console.log("[RootProvider v2.1] Injecting root bypass hooks...");

    // ============ HELPERS ============
    function joinJavaStringArray(arr) {
        if (!arr) return "";
        var parts = [];
        for (var i = 0; i < arr.length; i++) {
            parts.push(String(arr[i]));
        }
        return parts.join(" ");
    }

    function isRootPath(path) {
        if (!path) return false;
        var lower = path.toLowerCase();
        var indicators = [
            "/su", "magisk", "superuser", "supersu", "busybox",
            "koush", "chainfire", "suhide", ".magisk", "/data/adb",
            "/sbin/su", "/system/etc/init.d", "daemonsu",
            "/system/xbin", "root_", "test-keys", "eng."
        ];
        for (var i = 0; i < indicators.length; i++) {
            if (lower.indexOf(indicators[i]) >= 0) return true;
        }
        return false;
    }

    // ============ NATIVE HOOKS ============
    var accessPtr = Module.findExportByName(null, "access");
    if (accessPtr) {
        Interceptor.attach(accessPtr, {
            onEnter: function(args) { this.path = Memory.readUtf8String(args[0]); },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path)) retval.replace(-1);
            }
        });
    }

    var statPtr = Module.findExportByName(null, "stat");
    if (statPtr) {
        Interceptor.attach(statPtr, {
            onEnter: function(args) { this.path = Memory.readUtf8String(args[0]); },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path)) retval.replace(-1);
            }
        });
    }

    var fopenPtr = Module.findExportByName(null, "fopen");
    if (fopenPtr) {
        Interceptor.attach(fopenPtr, {
            onEnter: function(args) { this.path = Memory.readUtf8String(args[0]); },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path) && retval.toInt32() !== 0)
                    retval.replace(0);
            }
        });
    }

    // ============ JAVA HOOKS ============
    try {
        var Runtime = Java.use('java.lang.Runtime');
        Runtime.exec.overload('[Ljava.lang.String;').implementation = function(cmd) {
            var cmdStr = joinJavaStringArray(cmd);
            if (cmdStr.indexOf("su") >= 0 || cmdStr.indexOf("magisk") >= 0) {
                return Java.use('java.lang.ProcessBuilder')
                    .$new(['sh', '-c', 'echo "su: not found"']).start();
            }
            return this.exec(cmd);
        };
    } catch(e) { console.warn("Runtime.exec hook: " + e); }

    try {
        var ProcessBuilder = Java.use('java.lang.ProcessBuilder');
        ProcessBuilder.start.implementation = function() {
            try {
                var cmd = this.command();
                if (cmd) {
                    var cmdStr = String(cmd.toString());
                    if (cmdStr.indexOf("su") >= 0 || cmdStr.indexOf("magisk") >= 0)
                        return Java.use('java.lang.ProcessBuilder')
                            .$new(['sh', '-c', 'echo blocked']).start();
                }
            } catch(e) {}
            return this.start();
        };
    } catch(e) { console.warn("ProcessBuilder hook: " + e); }

    try {
        var File = Java.use('java.io.File');
        File.exists.implementation = function() {
            return isRootPath(this.getAbsolutePath()) ? false : this.exists();
        };
        File.canExecute.implementation = function() {
            return isRootPath(this.getAbsolutePath()) ? false : this.canExecute();
        };
    } catch(e) {}

    try {
        var Build = Java.use('android.os.Build');
        Build.TAGS.value = "release-keys";
        Build.TYPE.value = "user";
        try { Build.FINGERPRINT.value = String(Build.FINGERPRINT.value).replace("test-keys", "release-keys"); } catch(e) {}
        try { Build.DISPLAY.value = String(Build.DISPLAY.value).replace("eng.", "user."); } catch(e) {}
    } catch(e) {}

    try {
        var SP = Java.use('android.os.SystemProperties');
        SP.get.overload('java.lang.String').implementation = function(key) {
            if (key.indexOf("ro.debuggable") >= 0) return "0";
            if (key.indexOf("ro.secure") >= 0) return "1";
            if (key.indexOf("persist.sys.root_access") >= 0) return "0";
            return this.get(key);
        };
    } catch(e) {}

    // Hide root apps from PackageManager
    try {
        var PM = Java.use('android.content.pm.PackageManager');
        var rootPkgs = [
            "com.noshufou.android.su", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.topjohnwu.magisk",
            "com.keramidas.TitaniumBackup", "de.robv.android.xposed.installer",
            "com.jrummy.root.browserfree", "com.amphoras.hidemyroot",
            "com.ryosoftware.rootchecker", "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch", "me.phh.superuser",
            "com.saurik.substrate", "com.devadvance.rootchecker",
            "com.thirdparty.superuser", "com.zachspong.temprootremovebounty"
        ];

        PM.getPackageInfo.overload('java.lang.String', 'int').implementation = function(pkg, flags) {
            if (rootPkgs.indexOf(pkg) >= 0) {
                throw Java.use('android.content.pm.PackageManager$NameNotFoundException').$new(pkg + " not installed");
            }
            return this.getPackageInfo(pkg, flags);
        };
    } catch(e) {}

    // SELinux hooks
    try {
        var SELinux = Java.use('android.os.SELinux');
        SELinux.isSELinuxEnabled.implementation = function() { return true; };
        SELinux.isSELinuxEnforced.implementation = function() { return false; };
    } catch(e) {}

    // Process.myUid() → return root UID
    try {
        var Process = Java.use('android.os.Process');
        Process.myUid.implementation = function() { return 0; };
    } catch(e) {}

    // ============ SAFETYNET / PLAY INTEGRITY BYPASS ============
    try {
        var SafetyNet = Java.use('com.google.android.gms.safetynet.SafetyNet');
        SafetyNet.getClient.implementation = function(activity) {
            console.log("[SafetyNet] getClient called");
            return this.getClient(activity);
        };
        var SNApi = Java.use('com.google.android.gms.safetynet.SafetyNetApi');
        SNApi.attest.implementation = function(client, nonce) {
            console.log("[SafetyNet] attest() called - returning fake");
            var Tasks = Java.use('com.google.android.gms.tasks.Tasks');
            return Tasks.forResult(null);
        };
    } catch(e) { console.warn("SafetyNet hooks: " + e); }

    try {
        var IntegrityMgr = Java.use('com.google.android.play.core.integrity.IntegrityManager');
        IntegrityMgr.requestIntegrityToken.implementation = function(request) {
            console.log("[Play Integrity] requestIntegrityToken() called");
            var Tasks = Java.use('com.google.android.gms.tasks.Tasks');
            return Tasks.forResult(null);
        };
    } catch(e) { console.warn("Play Integrity hooks: " + e); }

    try {
        var GA = Java.use('com.google.android.gms.common.GoogleApiAvailability');
        GA.isGooglePlayServicesAvailable.implementation = function(ctx) { return 0; };
    } catch(e) {}

    console.log("[RootProvider v2.1] All hooks active - environment looks rooted to target apps");
});
