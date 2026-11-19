package com.bardi.livestreamer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private final EditText[] cameras = new EditText[4];
    private EditText streamKey;
    private TextView status;
    private SharedPreferences prefs;
    private final Handler statusHandler = new Handler();

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("bardi_stream", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 12, 18, 16);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("BARDI LIVE STREAMER");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 4, 0, 4);
        root.addView(title, match());

        TextView subtitle = new TextView(this);
        subtitle.setText("Gabungkan 4 kamera BARDI menjadi satu siaran YouTube Live 720p.");
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, match());

        for (int i = 0; i < 4; i++) {
            addLabel(root, "URL RTSP KAMERA " + (i + 1));
            cameras[i] = new EditText(this);
            cameras[i].setSingleLine(true);
            cameras[i].setTextSize(14);
            cameras[i].setHint("rtsp://user:password@alamat-kamera:554/...");
            cameras[i].setText(prefs.getString("cam" + i, ""));
            root.addView(cameras[i], match());
        }

        addLabel(root, "YOUTUBE STREAM KEY");
        streamKey = new EditText(this);
        streamKey.setSingleLine(true);
        streamKey.setTextSize(14);
        streamKey.setHint("Tempel Stream Key dari YouTube Studio");
        streamKey.setText(getSavedStreamKey());
        root.addView(streamKey, match());

        TextView hint = new TextView(this);
        hint.setText("Stream Key disimpan hanya di perangkat ini. Jangan kirim atau unggah Stream Key ke GitHub.");
        hint.setTextSize(12);
        root.addView(hint, match());

        Button studio = new Button(this);
        studio.setText("BUKA YOUTUBE STUDIO");
        studio.setOnClickListener(v -> openYouTubeStudio());
        root.addView(studio, match());

        Button save = new Button(this);
        save.setText("SIMPAN PENGATURAN");
        save.setOnClickListener(v -> {
            saveSettings();
            setStatus("Pengaturan tersimpan di perangkat.");
        });
        root.addView(save, match());

        Button start = new Button(this);
        start.setText("MULAI LIVE KE YOUTUBE");
        start.setOnClickListener(v -> startStreaming());
        root.addView(start, match());

        Button stop = new Button(this);
        stop.setText("STOP LIVE");
        stop.setOnClickListener(v -> stopStreaming());
        root.addView(stop, match());

        addLabel(root, "STATUS STREAMING");
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(6, 10, 6, 10);
        status.setText("Siap. Isi 4 URL RTSP dan Stream Key YouTube.");
        root.addView(status, match());

        setContentView(scroll);
        refreshStatus();
    }

    private String getSavedStreamKey() {
        String key = prefs.getString("streamKey", "");
        if (key.length() > 0) return key;

        String oldOutput = prefs.getString("output", "");
        if (oldOutput.startsWith("rtmp://") || oldOutput.startsWith("rtmps://")) {
            int slash = oldOutput.lastIndexOf('/');
            if (slash >= 0 && slash < oldOutput.length() - 1) return oldOutput.substring(slash + 1);
        }
        return oldOutput;
    }

    private void addLabel(LinearLayout root, String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(14);
        t.setPadding(0, 10, 0, 2);
        root.addView(t, match());
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private void saveSettings() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < 4; i++) {
            e.putString("cam" + i, cameras[i].getText().toString().trim());
        }

        String keyOrUrl = streamKey.getText().toString().trim();
        String outputUrl;
        if (keyOrUrl.startsWith("rtmp://") || keyOrUrl.startsWith("rtmps://")) {
            outputUrl = keyOrUrl;
        } else {
            outputUrl = "rtmp://a.rtmp.youtube.com/live2/" + keyOrUrl;
        }
        e.putString("streamKey", keyOrUrl);
        e.putString("output", outputUrl);
        e.apply();
    }

    private boolean valid() {
        for (int i = 0; i < 4; i++) {
            String url = cameras[i].getText().toString().trim();
            if (!url.startsWith("rtsp://")) {
                setStatus("URL RTSP kamera " + (i + 1) + " belum benar. Pastikan diawali rtsp://");
                return false;
            }
        }
        if (streamKey.getText().toString().trim().length() == 0) {
            setStatus("Masukkan Stream Key YouTube terlebih dahulu.");
            return false;
        }
        return true;
    }

    private void startStreaming() {
        saveSettings();
        if (!valid()) return;

        prefs.edit().putBoolean("streamRequested", true)
            .putString("status", "Memulai layanan streaming dan memuat FFmpeg...")
            .apply();

        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            refreshStatus();
        } catch (Exception e) {
            setStatus("Tidak dapat memulai layanan: " + e.getMessage());
        }
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_STOP);
        try {
            startService(intent);
            setStatus("Perintah STOP dikirim. Menutup koneksi YouTube...");
        } catch (Exception e) {
            setStatus("Tidak dapat menghentikan layanan: " + e.getMessage());
        }
    }

    private void openYouTubeStudio() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com/")));
        } catch (Exception e) {
            setStatus("Browser tidak dapat membuka YouTube Studio: " + e.getMessage());
        }
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
        prefs.edit().putString("status", message).apply();
    }

    private void refreshStatus() {
        if (status != null) {
            status.setText(prefs.getString("status", "Siap. Isi 4 URL RTSP dan Stream Key YouTube."));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        statusHandler.removeCallbacks(statusPoll);
        statusHandler.post(statusPoll);
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusPoll);
        super.onPause();
    }
}
