package com.bardi.livestreamer;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Handler;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException;

import java.util.concurrent.atomic.AtomicBoolean;

public class StreamService extends Service {
    public static final String ACTION_START = "com.bardi.livestreamer.START";
    public static final String ACTION_STOP = "com.bardi.livestreamer.STOP";

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler handler = new Handler();
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private FFmpeg ffmpeg;
    private boolean binaryLoaded = false;
    private int retrySeconds = 3;

    @Override public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BardiStreamer:CPU");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BardiStreamer:WiFi");
        ffmpeg = FFmpeg.getInstance(getApplicationContext());
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
            if (binaryLoaded) {
                launch();
            } else {
                loadBinary();
            }
        }
        return START_STICKY;
    }

    private void loadBinary() {
        try {
            ffmpeg.loadBinary(new LoadBinaryResponseHandler() {
                @Override public void onSuccess() {
                    binaryLoaded = true;
                    if (!stopping.get()) launch();
                }
                @Override public void onFailure() {
                    Log.e("BardiStreamer", "FFmpeg binary tidak dapat dimuat pada perangkat ini.");
                    releaseLocks();
                    stopSelf();
                }
            });
        } catch (FFmpegNotSupportedException e) {
            Log.e("BardiStreamer", "Arsitektur perangkat tidak didukung", e);
            releaseLocks();
            stopSelf();
        } catch (Exception e) {
            Log.e("BardiStreamer", "Gagal memuat FFmpeg", e);
            releaseLocks();
            stopSelf();
        }
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

        running.set(true);
        final boolean[] succeeded = {false};
        try {
            ffmpeg.execute(args, new ExecuteBinaryResponseHandler() {
                @Override public void onStart() {
                    Log.i("BardiStreamer", "FFmpeg mulai streaming.");
                }
                @Override public void onProgress(String message) {
                    Log.d("BardiStreamer", message);
                }
                @Override public void onSuccess(String message) {
                    succeeded[0] = true;
                    Log.i("BardiStreamer", "FFmpeg selesai: " + message);
                }
                @Override public void onFailure(String message) {
                    Log.e("BardiStreamer", "FFmpeg gagal: " + message);
                    scheduleReconnect();
                }
                @Override public void onFinish() {
                    running.set(false);
                    if (!succeeded[0] && !stopping.get()) scheduleReconnect();
                }
            });
        } catch (FFmpegCommandAlreadyRunningException e) {
            running.set(false);
            Log.e("BardiStreamer", "Perintah FFmpeg lain masih berjalan", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopping.get()) return;
        handler.removeCallbacks(reconnectRunnable);
        int delay = retrySeconds;
        retrySeconds = Math.min(60, retrySeconds * 2);
        handler.postDelayed(reconnectRunnable, delay * 1000L);
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!stopping.get() && !running.get()) launch();
        }
    };

    private void stopPipeline() {
        stopping.set(true);
        handler.removeCallbacksAndMessages(null);
        if (ffmpeg != null) ffmpeg.killRunningProcesses();
        running.set(false);
        retrySeconds = 3;
        releaseLocks();
    }

    private void releaseLocks() {
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override public void onDestroy() {
        stopPipeline();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
