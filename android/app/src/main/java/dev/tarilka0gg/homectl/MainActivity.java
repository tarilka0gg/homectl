package dev.tarilka0gg.homectl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Shell around the daemon's web UI. Long-press Back to change server address / token. */
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
        if (prefs.getString("url", null) == null) askServer(); else load();
    }

    /** The token rides in the URL fragment, which the page reads and stores (never sent to the server). */
    private void load() {
        web.loadUrl(prefs.getString("url", "") + "/#" + prefs.getString("token", ""));
    }

    private void askServer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 0);
        EditText url = new EditText(this);
        url.setHint("http://100.x.y.z:7878");
        url.setText(prefs.getString("url", ""));
        EditText token = new EditText(this);
        token.setHint("token");
        token.setText(prefs.getString("token", ""));
        box.addView(url);
        box.addView(token);
        new AlertDialog.Builder(this).setTitle("homectl server").setView(box)
            .setPositiveButton("OK", (d, w) -> {
                String u = url.getText().toString().trim();
                prefs.edit().putString("url", u).putString("token", token.getText().toString().trim()).apply();
                load();
            }).show();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { e.startTracking(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { askServer(); return true; }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && !e.isCanceled()) {
            if (web.canGoBack()) web.goBack(); else finish();
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
