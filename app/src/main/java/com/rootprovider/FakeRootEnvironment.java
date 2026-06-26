package com.rootprovider;

import java.io.*;

/**
 * Deploys a fake root filesystem that mimics Magisk, SuperSU, and BusyBox.
 * Used both by the service (runtime) and the APK patcher (injected into target APK).
 */
public class FakeRootEnvironment {

    private final File rootFsDir;

    public FakeRootEnvironment(File rootFsDir) {
        this.rootFsDir = rootFsDir;
    }

    /**
     * Deploy complete fake root filesystem.
     */
    public void deploy() throws IOException {
        deploySu();
        deployMagisk();
        deployBusyBox();
        deployBuildProp();
        deploySELinux();
        deployInitScript();
        deployMagiskConfig();
        deployStubApks();
        deployDataDirs();
    }

    private void deploySu() throws IOException {
        File suFile = new File(rootFsDir, "system/xbin/su");
        suFile.getParentFile().mkdirs();
        writeFile(suFile,
            "#!/system/bin/sh\n" +
            "echo \"uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0\"\n" +
            "if [ \"$1\" = \"-c\" ]; then shift; eval \"$@\"; fi\n" +
            "exit 0\n");
        suFile.setExecutable(true);
    }

    private void deployMagisk() throws IOException {
        File magiskFile = new File(rootFsDir, "system/bin/magisk");
        magiskFile.getParentFile().mkdirs();
        writeFile(magiskFile,
            "#!/system/bin/sh\n" +
            "echo \"Magisk v28.0 (RootProvider)\"\n" +
            "echo \"Running in systemless mode\"\n" +
            "exit 0\n");
        magiskFile.setExecutable(true);
    }

    private void deployBusyBox() throws IOException {
        File bbFile = new File(rootFsDir, "system/xbin/busybox");
        bbFile.getParentFile().mkdirs();
        writeFile(bbFile,
            "#!/system/bin/sh\n" +
            "echo \"BusyBox v1.36.1 (RootProvider) multi-call binary\"\n" +
            "echo \"Applets: cat, chmod, cp, dd, echo, grep, kill, ln, ls, mkdir, mount, mv, ps, rm, sed, sh, sleep, tar, touch\"\n");
        bbFile.setExecutable(true);

        // Busybox applet symlinks
        String[] applets = {"cat","chmod","cp","dd","echo","grep","kill","ln","ls","mkdir","mount","mv","ps","rm","sed","sh","sleep","tar","touch","umount","which"};
        for (String applet : applets) {
            File link = new File(rootFsDir, "system/xbin/" + applet);
            if (!link.exists()) {
                try {
                    Runtime.getRuntime().exec("ln -sf busybox " + link.getAbsolutePath());
                } catch (Exception ignored) {}
            }
        }
    }

    private void deployBuildProp() throws IOException {
        File bpFile = new File(rootFsDir, "system/build.prop");
        bpFile.getParentFile().mkdirs();
        writeFile(bpFile,
            "ro.build.tags=test-keys\n" +
            "ro.debuggable=1\n" +
            "ro.secure=0\n" +
            "persist.sys.root_access=3\n" +
            "ro.build.type=userdebug\n");
    }

    private void deploySELinux() throws IOException {
        File selFile = new File(rootFsDir, "selinux/enforce");
        selFile.getParentFile().mkdirs();
        writeFile(selFile, "0\n");
    }

    private void deployInitScript() throws IOException {
        File initd = new File(rootFsDir, "system/etc/init.d/99SuperSUDaemon");
        initd.getParentFile().mkdirs();
        writeFile(initd, "#!/system/bin/sh\n# RootProvider init script\nexit 0\n");
        initd.setExecutable(true);
    }

    private void deployMagiskConfig() throws IOException {
        File mcFile = new File(rootFsDir, "data/adb/magisk/config.sh");
        mcFile.getParentFile().mkdirs();
        writeFile(mcFile, "MAGISK_VER=28.0\nMAGISK_VER_CODE=28000\nBOOTMODE=true\n");
    }

    private void deployStubApks() throws IOException {
        File suStub = new File(rootFsDir, "system/app/Superuser.apk");
        suStub.getParentFile().mkdirs();
        suStub.createNewFile();

        File magiskStub = new File(rootFsDir, "system/app/MagiskManager.apk");
        magiskStub.getParentFile().mkdirs();
        magiskStub.createNewFile();
    }

    private void deployDataDirs() {
        new File(rootFsDir, "data/data/com.topjohnwu.magisk").mkdirs();
        new File(rootFsDir, "data/data/eu.chainfire.supersu").mkdirs();
        new File(rootFsDir, "data/data/com.noshufou.android.su").mkdirs();
        new File(rootFsDir, "data/local/tmp").mkdirs();
    }

    /**
     * Get the root filesystem base directory.
     */
    public File getRootFsDir() {
        return rootFsDir;
    }

    private void writeFile(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
            w.flush();
        }
    }
}
