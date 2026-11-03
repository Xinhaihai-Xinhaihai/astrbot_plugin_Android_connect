package io.github.astrbot.androidconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PhoneControlService extends Service {
    static volatile long authorizedUntil;
    static volatile String connectionState = "手机控制未开启";
    private static volatile PhoneControlService live;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    static boolean enabled() { return authorizedUntil > System.currentTimeMillis() && PhoneAccessibilityService.instance != null; }
    static void disableLocally(Context context) {
        authorizedUntil = 0; connectionState = "手机控制已关闭";
        context.stopService(new Intent(context, PhoneControlService.class));
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) {
            authorizedUntil = 0; stopSelf(); return START_NOT_STICKY;
        }
        if (PhoneAccessibilityService.instance == null) { authorizedUntil = 0; stopSelf(); return START_NOT_STICKY; }
        int hours = Math.max(1, Math.min(8, intent.getIntExtra("hours", 1)));
        authorizedUntil = System.currentTimeMillis() + hours * 3600000L;
        live = this;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("phone_control", "手机控制", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PhoneControlService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "phone_control")
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("AstrBot 手机控制已开启")
            .setContentText("限时授权中 · 点“停止”立即撤销").setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止手机控制", stop).build()).build();
        startForeground(1001, notification);
        if (!running) { running = true; worker.execute(this::poll); }
        return START_NOT_STICKY;
    }
    private void poll() {
        Prefs prefs = new Prefs(this); Api api = new Api(prefs);
        String device = prefs.id("device");
        try {
            while (running && enabled()) {
                try {
                    JSONObject response = api.call("POST", "/devices/" + device + "/poll", Api.json("enabled", true));
                    boolean allowed = response.optBoolean("allowed");
                    connectionState = allowed ? "手机在线，可接收 Agent 指令" : "请在电脑插件配置允许手机控制";
                    JSONArray commands = response.optJSONArray("commands");
                    if (commands != null) for (int i = 0; i < commands.length(); i++) {
                        if (!running || !enabled()) break;
                        JSONObject command = commands.getJSONObject(i);
                        String id = command.getString("id");
                        if (command.optDouble("expires") * 1000 < System.currentTimeMillis()) continue;
                        if (!prefs.consume(id)) continue;
                        CompletableFuture<String> result = new CompletableFuture<>();
                        handler.post(() -> {
                            PhoneAccessibilityService service = PhoneAccessibilityService.instance;
                            if (!enabled() || service == null) result.complete("{\"error\":\"手机控制未授权\"}");
                            else service.execute(command.optString("action"), command.optJSONObject("arguments"), result::complete);
                        });
                        String text = result.get(5, TimeUnit.SECONDS);
                        String log = prefs.get("phone_log", "");
                        prefs.put("phone_log", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date())
                            + "  " + command.optString("action") + "  " + text.substring(0, Math.min(text.length(), 120)) + "\n"
                            + log.substring(0, Math.min(log.length(), 5000)));
                        api.call("POST", "/devices/" + device + "/commands/" + id, Api.json("result", text));
                    }
                    if (!allowed) Thread.sleep(3000);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) {
                    connectionState = "手机控制连接中断，将重试";
                    try { Thread.sleep(3000); } catch (InterruptedException interrupted) { break; }
                }
            }
        } finally {
            authorizedUntil = 0; connectionState = "手机控制已关闭";
            try { api.call("POST", "/devices/" + device + "/disable", Api.json()); } catch (Exception ignored) { }
            handler.post(this::stopSelf);
        }
    }
    @Override public void onDestroy() {
        running = false; authorizedUntil = 0; live = null;
        worker.shutdownNow(); super.onDestroy();
    }
}
