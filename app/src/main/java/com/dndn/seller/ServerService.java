package com.dndn.seller;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ServerService extends Service {
    static final int UDP_PORT = 45454;
    LocalServer server;
    PowerManager.WakeLock wl;
    WifiManager.WifiLock wf;
    NsdManager nsd;
    NsdManager.RegistrationListener regL;
    volatile boolean bc = true;

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
        registerNsd();
        startBroadcast();
    }

    /** الإعلان الأول: mDNS/NSD */
    void registerNsd() {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("dndn");
            info.setServiceType("_dndn._tcp.");
            info.setPort(LocalServer.PORT);
            regL = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
                public void onServiceRegistered(NsdServiceInfo i) { }
                public void onServiceUnregistered(NsdServiceInfo i) { }
            };
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regL);
        } catch (Exception e) { }
    }

    /** الإعلان الثاني (احتياطي): بث UDP كل ثانيتين على كل الشبكات */
    void startBroadcast() {
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                while (bc) {
                    String name = getSharedPreferences("cfg", 0).getString("name", "dndn");
                    byte[] b = ("DNDN|" + LocalServer.PORT + "|" + name).getBytes("UTF-8");
                    List<InetAddress> to = new ArrayList<>();
                    try { to.add(InetAddress.getByName("255.255.255.255")); } catch (Exception e) { }
                    try {
                        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                            if (!ni.isUp() || ni.isLoopback()) continue;
                            for (InterfaceAddress ia : ni.getInterfaceAddresses())
                                if (ia.getBroadcast() != null) to.add(ia.getBroadcast());
                        }
                    } catch (Exception e) { }
                    for (InetAddress a : to) {
                        try { ds.send(new DatagramPacket(b, b.length, a, UDP_PORT)); } catch (Exception e) { }
                    }
                    Thread.sleep(2000);
                }
            } catch (Exception e) { }
        }).start();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        bc = false;
        try { if (nsd != null && regL != null) nsd.unregisterService(regL); } catch (Exception e) { }
        if (server != null) server.stop();
        try { if (wl != null) wl.release(); if (wf != null) wf.release(); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
