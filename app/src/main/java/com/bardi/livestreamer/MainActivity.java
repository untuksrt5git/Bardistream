package com.bardi.livestreamer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private final EditText[] cameras = new EditText[4];
    private EditText output;
    private TextView status;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("bardi_stream", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 14, 18, 14);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("BARDI LIVE STREAMER");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, match());

        for (int i = 0; i < 4; i++) {
            addLabel(root, "RTSP CAMERA " + (i + 1));
            cameras[i] = new EditText(this);
            cameras[i].setSingleLine(true);
            cameras[i].setTextSize(14);
            cameras[i].setHint("rtsp://user:password@camera-ip:554/...");
            cameras[i].setText(prefs.getString("cam" + i, ""));
            root.addView(cameras[i], match());
        }

        addLabel(root, "YOUTUBE RTMP/RTMPS URL");
        output = new EditText(this);
        output.setSingleLine(true);
        output.setTextSize(14);
        output.setHint("rtmp://a.rtmp.youtube.com/live2/STREAM_KEY");
        output.setText(prefs.getString("output", ""));
        root.addView(output, match());

        Button save = new Button(this);
        save.setText("SIMPAN PENGATURAN");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save, match());

        Button start = new Button(this);
        start.setText("MULAI STREAMING");
        start.setOnClickListener(v -> {
            saveSettings();
            if (!valid()) {
                setStatus("Isi keempat URL RTSP dan URL YouTube terlebih dahulu.");
                return;
            }
            Intent intent = new Intent(this, StreamService.class);
            intent.setAction(StreamService.ACTION_START);
            startService(intent);
            setStatus("Perintah streaming dikirim.");
        });
        root.addView(start, match());

        Button stop = new Button(this);
        stop.setText("HENTIKAN STREAMING");
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, StreamService.class);
            intent.setAction(StreamService.ACTION_STOP);
            startService(intent);
            setStatus("Perintah berhenti dikirim.");
        });
        root.addView(stop, match());

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(4, 16, 4, 8);
        status.setText("Siap. Masukkan URL RTSP kamera yang sudah diuji di ONVIER.");
        root.addView(status, match());
        setContentView(scroll);
    }

    private void addLabel(LinearLayout root, String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(14);
        t.setPadding(0, 10, 0, 2);
        root.addView(t, match());
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void saveSettings() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < 4; i++) e.putString("cam" + i, cameras[i].getText().toString().trim());
        e.putString("output", output.getText().toString().trim());
        e.apply();
        setStatus("Pengaturan tersimpan di perangkat.");
    }

    private boolean valid() {
        for (EditText c : cameras) if (c.getText().toString().trim().length() == 0) return false;
        String url = output.getText().toString().trim();
        return url.startsWith("rtmp://") || url.startsWith("rtmps://");
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }
}
