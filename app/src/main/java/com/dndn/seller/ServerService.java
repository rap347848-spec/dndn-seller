package com.dndn.seller;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.IOException;

public class ServerService extends Service {
    LocalServer server;
    PowerManager.WakeLock wl;
    WifiManager.WifiLock wf;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("srv", "الخدمة", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("msg", "الرسائل", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "srv")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("dndn يعمل")
                .setContentText("الزبائن يستطيعون الاتصال بك الآن")
                .setContentIntent(pi).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        try {
            wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dndn:srv");
            wl.acquire();
            wf = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dndn");
            wf.acquire();
        } catch (Exception e) { }
        server = new LocalServer(this, (name, text, hasImg) -> {
            Notification m = new Notification.Builder(this, "msg")
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(name)
                    .setContentText(text.isEmpty() ? "📷 صورة" : text)
                    .setAutoCancel(true).setContentIntent(pi).build();
            nm.notify(10 + Math.abs(name.hashCode() % 100000), m);
        });
        try { server.start(); } catch (IOException e) { }
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        try { if (wl != null) wl.release(); if (wf != null) wf.release(); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
