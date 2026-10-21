package dev.tarilka0gg.homectl;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.EditText;
import android.app.AlertDialog;

/** Thin shell: shows the daemon's web UI. Long-press back to change the server address. */
public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("homectl", MODE_PRIVATE);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        setContentView(web);
        String url = prefs.getString("url", null);
        if (url == null) askUrl(); else web.loadUrl(url);
    }

    private void askUrl() {
        EditText in = new EditText(this);
        in.setHint("http://100.x.y.z:7878");
        new AlertDialog.Builder(this).setTitle("homectl server").setView(in).setCancelable(false)
            .setPositiveButton("OK", (d, w) -> {
                String u = in.getText().toString().trim();
                prefs.edit().putString("url", u).apply();
                web.loadUrl(u);
            }).show();
    }

    @Override
    public boolean onKeyLongPress(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) { askUrl(); return true; }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) { e.startTracking(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_BACK && !e.isCanceled()) {
            if (web.canGoBack()) web.goBack(); else finish();
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
