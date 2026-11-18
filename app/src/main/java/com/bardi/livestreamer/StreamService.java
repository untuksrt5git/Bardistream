package com.bardi.livestreamer;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Handler;
import android.net.wifi.WifiManager;
import android.util.Log;
import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.arthenica.mobileffmpeg.ExecuteCallback;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamService extends Service {
    public static final String ACTION_START = "com.bardi.livestreamer.START";
    public static final String ACTION_STOP = "com.bardi.livestreamer.STOP";
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler handler = new Handler();
    private int retrySeconds = 3;

    @Override public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BardiStreamer:CPU");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BardiStreamer:WiFi");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopPipeline();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction()) && !running.get()) {
            stopping.set(false);
            if (!cpuLock.isHeld()) cpuLock.acquire();
            if (!wifiLock.isHeld()) wifiLock.acquire();
            launch();
        }
        return START_STICKY;
    }

    private void launch() {
        if (stopping.get() || running.get()) return;
        SharedPreferences p = getSharedPreferences("bardi_stream", MODE_PRIVATE);
        String[] c = new String[4];
        for (int i = 0; i < 4; i++) c[i] = p.getString("cam" + i, "");
        String out = p.getString("output", "");
        String[] args = {
            "-hide_banner", "-loglevel", "warning",
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", c[0],
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", c[1],
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", c[2],
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", c[3],
            "-filter_complex",
            "[0:v]scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2[a];" +
            "[1:v]scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2[b];" +
            "[2:v]scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2[c];" +
            "[3:v]scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2[d];" +
            "[a][b]hstack=inputs=2[top];[c][d]hstack=inputs=2[bottom];" +
            "[top][bottom]vstack=inputs=2,format=yuv420p[v]",
            "-map", "[v]", "-an", "-r", "15",
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-profile:v", "baseline", "-pix_fmt", "yuv420p",
            "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "5000k",
            "-g", "30", "-f", "flv", out
        };

        Config.enableLogCallback(message -> Log.d("BardiFFmpeg", message.getText()));
        running.set(true);
        FFmpeg.executeAsync(args, new ExecuteCallback() {
            @Override public void apply(String executionId, int returnCode) {
                running.set(false);
                if (stopping.get()) return;
                Log.w("BardiStreamer", "FFmpeg stopped rc=" + returnCode + "; reconnect in " + retrySeconds + "s");
                final int delay = retrySeconds;
                retrySeconds = Math.min(60, retrySeconds * 2);
                handler.postDelayed(() -> launch(), delay * 1000L);
            }
        });
    }

    private void stopPipeline() {
        stopping.set(true);
        handler.removeCallbacksAndMessages(null);
        FFmpeg.cancel();
        running.set(false);
        retrySeconds = 3;
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override public void onDestroy() {
        stopPipeline();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
