'use strict';
Java.perform(function() {
    console.log("[RootProvider] Bypass loaded");
    
    // Hook access()
    var accessPtr = Module.findExportByName(null, "access");
    if(accessPtr) Interceptor.attach(accessPtr, {
        onEnter: function(a) { this.p = Memory.readUtf8String(a[0]); },
        onLeave: function(r) { if(this.p) { var l=this.p.toLowerCase(); if(l.indexOf("/su")>=0||l.indexOf("magisk")>=0||l.indexOf("superuser")>=0||l.indexOf("busybox")>=0) r.replace(0); } }
    });
    
    // Hook stat()
    var statPtr = Module.findExportByName(null, "stat");
    if(statPtr) Interceptor.attach(statPtr, {
        onEnter: function(a) { this.p = Memory.readUtf8String(a[0]); },
        onLeave: function(r) { if(this.p) { var l=this.p.toLowerCase(); if(l.indexOf("/su")>=0||l.indexOf("magisk")>=0) r.replace(0); } }
    });
    
    // Hook Runtime.exec - ALL overloads
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
    R.exec.overload('[Ljava.lang.String;', '[Ljava.lang.String;', 'java.io.File').implementation = function(a, e, d) {
        var c = a.join(" ");
        if(c.indexOf("su")>=0||c.indexOf("id")>=0) return this.exec(["/system/bin/sh","-c","echo uid=0(root) gid=0(root) groups=0(root)"], e, d);
        return this.exec(a, e, d);
    };
    
    // Hook ProcessBuilder
    var PB = Java.use('java.lang.ProcessBuilder');
    PB.start.implementation = function() {
        var c = this.command().toString();
        if(c.indexOf("su")>=0||c.indexOf("id")>=0) return Java.use('java.lang.ProcessBuilder').$new(["/system/bin/sh","-c","echo uid=0(root) gid=0(root) groups=0(root)"]).start();
        return this.start();
    };
    
    // Hook File
    var F = Java.use('java.io.File');
    F.exists.implementation = function() { var p=this.getAbsolutePath().toLowerCase(); if(p.indexOf("su")>=0||p.indexOf("magisk")>=0||p.indexOf("superuser")>=0||p.indexOf("busybox")>=0) return true; return this.exists(); };
    F.isFile.implementation = function() { var p=this.getAbsolutePath().toLowerCase(); if(p.indexOf("su")>=0||p.indexOf("magisk")>=0||p.indexOf("superuser")>=0) return true; return this.isFile(); };
    F.canRead.implementation = function() { var p=this.getAbsolutePath().toLowerCase(); if(p.indexOf("su")>=0||p.indexOf("magisk")>=0) return true; return this.canRead(); };
    
    // Hook Build
    var B = Java.use('android.os.Build');
    B.getTags.implementation = function() { return "test-keys"; };
    B.getType.implementation = function() { return "userdebug"; };
    
    // Hook SystemProperties
    try {
        var SP = Java.use('android.os.SystemProperties');
        SP.get.overload('java.lang.String').implementation = function(k) {
            if(k==="ro.build.tags") return "test-keys";
            if(k==="ro.debuggable") return "1";
            if(k==="ro.secure") return "0";
            if(k==="persist.sys.root_access") return "3";
            if(k==="ro.build.type") return "userdebug";
            return this.get(k);
        };
        SP.get.overload('java.lang.String', 'java.lang.String').implementation = function(k, d) {
            if(k==="ro.build.tags") return "test-keys";
            if(k==="ro.debuggable") return "1";
            if(k==="ro.secure") return "0";
            return this.get(k, d);
        };
    } catch(e) {}
    
    // Hook PackageManager
    var PM = Java.use('android.content.pm.PackageManager');
    var rootPkgs = ["com.topjohnwu.magisk","eu.chainfire.supersu","com.noshufou.android.su","com.thirdparty.superuser","com.stericson.busybox","com.koushikdutta.superuser","com.jrummy.superuser","com.keramidas.TitaniumBackup"];
    PM.getPackageInfo.overload('java.lang.String', 'int').implementation = function(p, f) {
        if(rootPkgs.indexOf(p)>=0) throw Java.use("android.content.pm.PackageManager$NameNotFoundException").$new(p+" not found");
        return this.getPackageInfo(p, f);
    };
    
    // Hook SELinux
    try {
        var SEL = Java.use('android.os.SELinux');
        SEL.isSELinuxEnabled.implementation = function() { return true; };
        SEL.isSELinuxEnforced.implementation = function() { return false; };
    } catch(e) {}
    
    // Hook Debug
    try {
        var D = Java.use('android.os.Debug');
        D.isDebuggerConnected.implementation = function() { return false; };
    } catch(e) {}
    
    // Hook Process.myUid
    try {
        var P = Java.use('android.os.Process');
        P.myUid.implementation = function() { return 0; };
    } catch(e) {}
    
    console.log("[RootProvider] All hooks active - device appears rooted");
});
