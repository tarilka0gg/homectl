package dev.tarilka0gg.homectl;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Android share-sheet target: sends the shared file(s) to the daemon's inbox. */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            java.util.ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        String base = getSharedPreferences("homectl", MODE_PRIVATE).getString("url", "");
        String token = getSharedPreferences("homectl", MODE_PRIVATE).getString("token", "");
        if (base.isEmpty() || uris.isEmpty()) {
            Toast.makeText(this, "homectl: not configured or nothing to send", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Toast.makeText(this, "Sending " + uris.size() + " file(s)…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            for (Uri u : uris) if (send(base, token, u)) ok++;
            final String msg = "homectl: sent " + ok + "/" + uris.size();
            runOnUiThread(() -> { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish(); });
        }).start();
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return "shared-" + System.currentTimeMillis();
    }

    private boolean send(String base, String token, Uri u) {
        try {
            String name = URLEncoder.encode(nameOf(u), "UTF-8").replace("+", "%20");
            HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/api/files/" + name).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setChunkedStreamingMode(65536);
            c.setRequestProperty("Authorization", "Bearer " + token);
            try (InputStream is = getContentResolver().openInputStream(u); OutputStream os = c.getOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            int code = c.getResponseCode();
            c.disconnect();
            return code == 201;
        } catch (Exception e) {
            return false;
        }
    }
}
