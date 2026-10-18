package com.dndn.seller;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.*;

public class MainActivity extends Activity {
    static final String URL = "http://127.0.0.1:" + LocalServer.PORT + "/seller";
    WebView wv;
    ValueCallback<Uri[]> fileCb;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startForegroundService(new Intent(this, ServerService.class));
        wv = new WebView(this);
        wv.setBackgroundColor(0xFFECE5DD);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.postDelayed(() -> v.loadUrl(URL), 1000);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), 9); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        setContentView(wv);
        wv.postDelayed(() -> wv.loadUrl(URL), 600);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        else askBattery();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { askBattery(); }

    void askBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName()))
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
        } catch (Exception e) { }
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == 9 && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, d));
            fileCb = null;
        }
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }
}
