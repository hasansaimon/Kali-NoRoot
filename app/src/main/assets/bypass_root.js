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
            onEnter: function(args) {
                this.path = Memory.readUtf8String(args[0]);
            },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path)) {
                    retval.replace(-1);
                }
            }
        });
    }

    var statPtr = Module.findExportByName(null, "stat");
    if (statPtr) {
        Interceptor.attach(statPtr, {
            onEnter: function(args) {
                this.path = Memory.readUtf8String(args[0]);
            },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path)) {
                    retval.replace(-1);
                }
            }
        });
    }

    var lstatPtr = Module.findExportByName(null, "lstat");
    if (lstatPtr) {
        Interceptor.attach(lstatPtr, {
            onEnter: function(args) {
                this.path = Memory.readUtf8String(args[0]);
            },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path)) {
                    retval.replace(-1);
                }
            }
        });
    }

    var fopenPtr = Module.findExportByName(null, "fopen");
    if (fopenPtr) {
        Interceptor.attach(fopenPtr, {
            onEnter: function(args) {
                this.path = Memory.readUtf8String(args[0]);
            },
            onLeave: function(retval) {
                if (this.path && isRootPath(this.path) &&
                    retval.toInt32() !== 0) {
                    retval.replace(0);
                }
            }
        });
    }

    // ============ JAVA HOOKS ============

    // Runtime.exec - hide su/magisk execution
    try {
        var Runtime = Java.use('java.lang.Runtime');
        Runtime.exec.overload('[Ljava.lang.String;').implementation = function(cmd) {
            var cmdStr = joinJavaStringArray(cmd);
            if (cmdStr.indexOf("su") >= 0 || cmdStr.indexOf("magisk") >= 0) {
                return Java.use('java.lang.ProcessBuilder')
                    .$new(['sh', '-c', 'echo "su: not found"'])
                    .start();
            }
            return this.exec(cmd);
        };
    } catch(e) { console.warn("Runtime.exec hook failed: " + e); }

    // ProcessBuilder - hide process creation
    try {
        var ProcessBuilder = Java.use('java.lang.ProcessBuilder');
        ProcessBuilder.start.implementation = function() {
            try {
                var cmd = this.command();
                if (cmd) {
                    var cmdStr = String(cmd.toString());
                    if (cmdStr.indexOf("su") >= 0 ||
                        cmdStr.indexOf("magisk") >= 0) {
                        return Java.use('java.lang.ProcessBuilder')
                            .$new(['sh', '-c', 'echo blocked']).start();
                    }
                }
            } catch(e) {}
            return this.start();
        };
    } catch(e) { console.warn("ProcessBuilder hook failed: " + e); }

    // File.exists - hide magisk/su paths
    try {
        var File = Java.use('java.io.File');
        File.exists.implementation = function() {
            var path = this.getAbsolutePath();
            if (isRootPath(path)) return false;
            return this.exists();
        };
    } catch(e) {}

    // File.canExecute - hide su binaries
    try {
        File.canExecute.implementation = function() {
            var path = this.getAbsolutePath();
            if (isRootPath(path)) return false;
            return this.canExecute();
        };
    } catch(e) {}

    // Build.TAGS - report release-keys
    try {
        var Build = Java.use('android.os.Build');
        Build.TAGS.value = "release-keys";
        Build.TYPE.value = "user";
        try {
            var fp = Build.FINGERPRINT.value;
            if (fp) Build.FINGERPRINT.value =
                fp.replace("test-keys", "release-keys");
        } catch(e) {}
        try {
            var disp = Build.DISPLAY.value;
            if (disp) Build.DISPLAY.value =
                disp.replace("eng.", "user.");
        } catch(e) {}
    } catch(e) {}

    // SystemProperties - hide root indicators
    try {
        var SystemProperties = Java.use('android.os.SystemProperties');
        SystemProperties.get.overload('java.lang.String').implementation = function(key) {
            if (key.indexOf("ro.debuggable") >= 0) return "0";
            if (key.indexOf("ro.secure") >= 0) return "1";
            if (key.indexOf("persist.sys.root_access") >= 0) return "0";
            if (key.indexOf("init.svc.adbd") >= 0) return "running";
            return this.get(key);
        };
    } catch(e) {}

    // PackageManager - hide root apps
    try {
        var PM = Java.use('android.content.pm.PackageManager');
        var rootPkgs = [
            "com.noshufou.android.su", "com.noshufou.android.su.elite",
            "com.thirdparty.superuser", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.zachspong.temprootremovebounty",
            "com.ramdroid.appquarantine", "com.devadvance.rootchecker",
            "com.androidsu.superuser", "com.saurik.substrate",
            "de.robv.android.xposed.installer", "com.keramidas.TitaniumBackup",
            "com.topjohnwu.magisk", "com.jrummy.root.browserfree",
            "com.jrummy.list.anDroid", "com.amphoras.hidemyroot",
            "com.ryosoftware.rootchecker", "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch", "xyz.paphonb.music",
            "me.phh.superuser", "com.android
