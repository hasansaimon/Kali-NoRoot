package com.rootprovider;

import android.app.*;
import android.content.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
import org.json.JSONArray;

public class RootProviderService extends Service {

    private static final int NOTIF_ID = 1001;
    private static final int API_PORT = 8443;
    private static final int ADB_PORT = 5555;
    private NotificationManager nm;
    private ServerThread apiServer;
    private ServerThread adbServer;
    private File rootFsDir;
    private String deviceIp;
    private List<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        
        NotificationChannel channel = new NotificationChannel(
            "root_provider", "Root Provider", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);

        rootFsDir = new File(getFilesDir(), "rootfs");
        try {
            new FakeRootEnvironment(rootFsDir).deploy();
        } catch (IOException e) {
            Log.e("RootProvider", "Fake root deploy failed", e);
        }
        
        // Get device IP
        WifiManager wifi = (WifiManager) getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            WifiInfo info = wifi.getConnectionInfo();
            int ipInt = info.getIpAddress();
            deviceIp = String.format(Locale.US, "%d.%d.%d.%d",
                (ipInt & 0xff), (ipInt >> 8 & 0xff),
                (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
        }

        // Start servers
        startAPIServer();
        startWiFiADBServer();
        startIPCServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String apiUrl = "http://" + deviceIp + ":" + API_PORT;
        
        Notification notification = new Notification.Builder(this, "root_provider")
            .setContentTitle("⚠️ Root Provider Active")
            .setContentText("API: " + apiUrl + " | WiFi ADB: " + deviceIp + ":" + ADB_PORT)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, 
                new Intent(this, MainActivity.class), 
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .build();
        
        startForeground(NOTIF_ID, notification);
        return START_STICKY;
    }

    // ===== HACKERAI API SERVER =====
    private void startAPIServer() {
        apiServer = new ServerThread("API", API_PORT) {
            @Override
            protected String handleCommand(String cmd, JSONObject params, PrintWriter writer) {
                try {
                    JSONObject response = new JSONObject();
                    response.put("status", "ok");
                    response.put("device", deviceIp);
                    response.put("service", "RootProvider v2.0");
                    response.put("root_active", true);

                    if (cmd.equals("ping")) {
                        response.put("message", "pong");
                        
                    } else if (cmd.equals("exec")) {
                        String command = params.optString("command", "");
                        String result = executeCommand(command);
                        response.put("result", result);
                        response.put("command", command);
                        
                    } else if (cmd.equals("root_check")) {
                        JSONObject rootInfo = new JSONObject();
                        rootInfo.put("su_exists", new File(rootFsDir, "system/xbin/su").exists());
                        rootInfo.put("magisk_version", "28.0");
                        rootInfo.put("build_tags", "test-keys");
                        rootInfo.put("selinux", "Permissive");
                        rootInfo.put("busybox", true);
                        response.put("root_environment", rootInfo);
                        
                    } else if (cmd.equals("patch_apk")) {
                        String apkPath = params.optString("apk_path", "");
                        if (!apkPath.isEmpty()) {
                            Intent patchIntent = new Intent(RootProviderService.this, ApkPatcher.class);
                            patchIntent.putExtra("apk_path", apkPath);
                            startService(patchIntent);
                            response.put("message", "Patching started");
                            response.put("apk_path", apkPath);
                        } else {
                            response.put("error", "No apk_path provided");
                        }
                        
                    } else if (cmd.equals("list_clients")) {
                        JSONArray clients = new JSONArray();
                        for (ClientHandler c : connectedClients) {
                            JSONObject client = new JSONObject();
                            client.put("ip", c.getIp());
                            client.put("connected_at", c.getConnectedAt());
                            clients.put(client);
                        }
                        response.put("clients", clients);
                        
                    } else if (cmd.equals("install_patched")) {
                        String apkPath = params.optString("apk_path", "");
                        if (!apkPath.isEmpty()) {
                            Intent install = new Intent(Intent.ACTION_VIEW);
                            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            install.setDataAndType(
                                FileProvider.getUriForFile(RootProviderService.this,
                                    getPackageName() + ".fileprovider",
                                    new File(apkPath)),
                                "application/vnd.android.package-archive");
                            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(install);
                            response.put("message", "Install triggered");
                        }
                        
                    } else if (cmd.equals("shell")) {
                        String command = params.optString("command", "");
                        boolean background = params.optBoolean("background", false);
                        
                        if (background) {
                            new Thread(() -> {
                                String result = executeCommand(command);
                            }).start();
                            response.put("message", "Command running in background");
                        } else {
                            String result = executeCommand(command);
                            response.put("result", result);
                            response.put("exit_code", 0);
                        }
                        
                    } else if (cmd.equals("push_file")) {
                        String path = params.optString("path", "");
                        String content = params.optString("content", "");
                        boolean executable = params.optBoolean("executable", false);
                        
                        File file = new File(rootFsDir, path);
                        file.getParentFile().mkdirs();
                        try (FileWriter w = new FileWriter(file)) {
                            w.write(content);
                        }
                        if (executable) file.setExecutable(true);
                        response.put("message", "File written: " + path);
                        response.put("size", content.length());
                        
                    } else if (cmd.equals("read_file")) {
                        String path = params.optString("path", "");
                        File file = new File(rootFsDir, path);
                        if (file.exists()) {
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                                String line;
                                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                            }
                            response.put("content", sb.toString());
                            response.put("path", path);
                        } else {
                            response.put("error", "File not found: " + path);
                        }
                        
                    } else if (cmd.equals("restart")) {
                        response.put("message", "Restarting service...");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            stopSelf();
                            startService(new Intent(RootProviderService.this, RootProviderService.class));
                        }, 1000);
                        
                    } else if (cmd.equals("status")) {
                        response.put("uptime", System.currentTimeMillis());
                        response.put("connected_clients", connectedClients.size());
                        response.put("api_port", API_PORT);
                        response.put("adb_port", ADB_PORT);
                        response.put("wifi_ip", deviceIp);
                        response.put("android_version", Build.VERSION.SDK_INT);
                        response.put("service_running", true);
                        
                    } else {
                        response.put("error", "Unknown command: " + cmd);
                        response.put("status", "error");
                    }

                    return response.toString(2);

                } catch (Exception e) {
                    try {
                        JSONObject err = new JSONObject();
                        err.put("status", "error");
                        err.put("error", e.getMessage());
                        return err.toString(2);
                    } catch (Exception ex) {
                        return "{\"status\":\"error\",\"error\":\"" + e.getMessage() + "\"}";
                    }
                }
            }
        };
        apiServer.start();
    }

    // ===== WIFI ADB SERVER =====
    private void startWiFiADBServer() {
        adbServer = new ServerThread("ADB", ADB_PORT) {
            @Override
            protected String handleCommand(String cmd, JSONObject params, PrintWriter writer) {
                if (cmd.equals("shell:")) {
                    String shellCmd = params.optString("command", "id");
                    String result = executeCommand(shellCmd);
                    return result;
                }
                if (cmd.equals("exec:")) {
                    String execCmd = params.optString("command", "");
                    String result = executeCommand(execCmd);
                    return result;
                }
                if (cmd.equals("su")) {
                    return "uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0\n";
                }
                if (cmd.equals("root")) {
                    return "adbd is already running as root\n";
                }
                if (cmd.equals("unroot")) {
                    return "adbd not running as root (root active)\n";
                }
                return cmd + ": not found\n";
            }
        };
        adbServer.start();
    }

    // ===== GENERIC SERVER THREAD =====
    private abstract class ServerThread extends Thread {
        private final String name;
        private final int port;
        
        ServerThread(String name, int port) {
            this.name = name;
            this.port = port;
        }

        @Override
        public void run() {
            try {
                ServerSocket server = new ServerSocket(port);
                Log.d("RootProvider", name + " server on port " + port);
                
                while (!isInterrupted()) {
                    try {
                        Socket client = server.accept();
                        ClientHandler handler = new ClientHandler(client, name) {
                            @Override
                            protected String processCommand(String cmd, JSONObject params, PrintWriter writer) {
                                return handleCommand(cmd, params, writer);
                            }
                        };
                        handler.start();
                        connectedClients.add(handler);
                    } catch (Exception e) {
                        Log.e("RootProvider", name + " accept error", e);
                    }
                }
            } catch (IOException e) {
                Log.e("RootProvider", name + " server failed", e);
            }
        }

        protected abstract String handleCommand(String cmd, JSONObject params, PrintWriter writer);
    }

    // ===== CLIENT HANDLER =====
    private class ClientHandler extends Thread {
        private final Socket socket;
        private final String serverName;
        private final String clientIp;
        private final long connectedAt;
        
        ClientHandler(Socket socket, String serverName) {
            this.socket = socket;
            this.serverName = serverName;
            this.clientIp = socket.getInetAddress().getHostAddress();
            this.connectedAt = System.currentTimeMillis();
        }

        String getIp() { return clientIp; }
        String getConnectedAt() { 
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(connectedAt));
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(
                    socket.getOutputStream(), true);

                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject request = new JSONObject(line);
                        String cmd = request.optString("command", "ping");
                        JSONObject params = request.optJSONObject("params");
                        if (params == null) params = new JSONObject();
                        
                        String response = processCommand(cmd, params, writer);
                        writer.println(response);
                        
                    } catch (Exception e) {
                        String cmd = line.trim();
                        String response = processCommand(cmd, new JSONObject(), writer);
                        writer.println(response);
                    }
                }
                
                socket.close();
                connectedClients.remove(this);
                
            } catch (IOException e) {
                Log.e("RootProvider", "Client handler error", e);
                connectedClients.remove(this);
            }
        }

        protected String processCommand(String cmd, JSONObject params, PrintWriter writer) {
            return "OK";
        }
    }

    private void startIPCServer() {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8888);
                while (true) {
                    Socket client = server.accept();
                    handleIPCClient(client);
                }
            } catch (IOException e) {
                Log.e("RootProvider", "IPC server failed", e);
            }
        }).start();
    }

    private void handleIPCClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(
                client.getOutputStream(), true);
            
            String line;
            while ((line = reader.readLine()) != null) {
                String response = executeCommand(line);
                writer.println(response);
            }
            
            client.close();
        } catch (IOException e) {
            Log.e("RootProvider", "IPC client error", e);
        }
    }

    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            return output.toString().trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (apiServer != null) apiServer.interrupt();
        if (adbServer != null) adbServer.interrupt();
        Log.d("RootProvider", "Service destroyed");
    }
}
