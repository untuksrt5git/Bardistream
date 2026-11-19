package com.bardi.livestreamer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
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

    private static final String TAG = "BardiStreamer";
    private static final int NOTIFICATION_ID = 4101;
    private static final String CHANNEL_ID = "bardi_live_stream";

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler handler = new Handler();

    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private FFmpeg ffmpeg;
    private boolean binaryLoaded = false;
    private boolean binaryLoading = false;
    private boolean foreground = false;
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
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopPipeline(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences("bardi_stream", MODE_PRIVATE);
        if (intent == null && !prefs.getBoolean("streamRequested", false)) {
            return START_NOT_STICKY;
        }

        if (stopping.get()) stopping.set(false);
        prefs.edit().putBoolean("streamRequested", true).apply();

        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        }

        if (!cpuLock.isHeld()) cpuLock.acquire();
        if (!wifiLock.isHeld()) wifiLock.acquire();

        if (running.get() || binaryLoading) return START_STICKY;

        if (binaryLoaded) {
            launch();
        } else {
            loadBinary();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "BARDI Live Stream", NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, StreamService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("BARDI LIVE STREAMER")
            .setContentText("Layanan streaming aktif")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPending);
        return builder.build();
    }

    private void loadBinary() {
        if (binaryLoading || stopping.get()) return;
        binaryLoading = true;
        setStatus("Memuat encoder FFmpeg untuk Android ARMv7...");
        try {
            ffmpeg.loadBinary(new LoadBinaryResponseHandler() {
                @Override public void onSuccess() {
                    binaryLoading = false;
                    binaryLoaded = true;
                    if (!stopping.get()) {
                        setStatus("FFmpeg siap. Menghubungkan ke empat kamera...");
                        launch();
                    }
                }

                @Override public void onFailure() {
                    binaryLoading = false;
                    Log.e(TAG, "FFmpeg binary tidak dapat dimuat pada perangkat ini.");
                    setStatus("ERROR: FFmpeg tidak mendukung perangkat atau ABI ini.");
                    clearRequestedState();
                    releaseLocks();
                    stopForegroundCompat();
                    stopSelf();
                }
            });
        } catch (FFmpegNotSupportedException e) {
            binaryLoading = false;
            Log.e(TAG, "Arsitektur perangkat tidak didukung", e);
            setStatus("ERROR: Arsitektur perangkat tidak didukung oleh FFmpeg.");
            clearRequestedState();
            releaseLocks();
            stopForegroundCompat();
            stopSelf();
        } catch (Exception e) {
            binaryLoading = false;
            Log.e(TAG, "Gagal memuat FFmpeg", e);
            setStatus("ERROR memuat FFmpeg: " + e.getMessage());
            clearRequestedState();
            releaseLocks();
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void launch() {
        if (stopping.get() || running.get()) return;

        SharedPreferences p = getSharedPreferences("bardi_stream", MODE_PRIVATE);
        String[] cameras = new String[4];
        for (int i = 0; i < 4; i++) cameras[i] = p.getString("cam" + i, "");
        String output = p.getString("output", "");

        for (int i = 0; i < 4; i++) {
            if (cameras[i] == null || !cameras[i].startsWith("rtsp://")) {
                setStatus("ERROR: URL RTSP kamera " + (i + 1) + " belum benar.");
                clearRequestedState();
                releaseLocks();
                stopForegroundCompat();
                stopSelf();
                return;
            }
        }
        if (output == null || !(output.startsWith("rtmp://") || output.startsWith("rtmps://"))) {
            setStatus("ERROR: URL YouTube RTMP belum benar.");
            clearRequestedState();
            releaseLocks();
            stopForegroundCompat();
            stopSelf();
            return;
        }

        String filter =
            "[0:v]scale=640:360:force_original_aspect_ratio=decrease," +
                "pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1[a];" +
            "[1:v]scale=640:360:force_original_aspect_ratio=decrease," +
                "pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1[b];" +
            "[2:v]scale=640:360:force_original_aspect_ratio=decrease," +
                "pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1[c];" +
            "[3:v]scale=640:360:force_original_aspect_ratio=decrease," +
                "pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1[d];" +
            "[a][b]hstack=inputs=2[top];" +
            "[c][d]hstack=inputs=2[bottom];" +
            "[top][bottom]vstack=inputs=2,format=yuv420p[v]";

        String[] args = {
            "-hide_banner", "-loglevel", "warning",
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", cameras[0],
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", cameras[1],
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", cameras[2],
            "-rtsp_transport", "tcp", "-stimeout", "8000000", "-i", cameras[3],
            "-filter_complex", filter,
            "-map", "[v]", "-an", "-r", "15",
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-profile:v", "baseline", "-pix_fmt", "yuv420p",
            "-threads", "2",
            "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "5000k",
            "-g", "30", "-f", "flv", output
        };

        running.set(true);
        setStatus("Menyambungkan kamera dan mengirim video ke YouTube...");
        try {
            ffmpeg.execute(args, new ExecuteBinaryResponseHandler() {
                @Override public void onStart() {
                    Log.i(TAG, "FFmpeg mulai streaming.");
                    setStatus("FFmpeg berjalan. Buka YouTube Studio untuk memeriksa preview siaran.");
                }

                @Override public void onProgress(String message) {
                    Log.d(TAG, message);
                    retrySeconds = 3;
                }

                @Override public void onSuccess(String message) {
                    Log.i(TAG, "FFmpeg selesai: " + message);
                }

                @Override public void onFailure(String message) {
                    Log.e(TAG, "FFmpeg gagal: " + message);
                    setStatus("Koneksi streaming terputus. Menyiapkan sambungan ulang...");
                }

                @Override public void onFinish() {
                    running.set(false);
                    if (!stopping.get()) {
                        scheduleReconnect();
                    } else {
                        setStatus("Streaming dihentikan.");
                    }
                }
            });
        } catch (FFmpegCommandAlreadyRunningException e) {
            running.set(false);
            Log.e(TAG, "Perintah FFmpeg lain masih berjalan", e);
            setStatus("FFmpeg masih menjalankan perintah lain. Mencoba ulang...");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopping.get()) return;
        handler.removeCallbacks(reconnectRunnable);
        int delay = retrySeconds;
        retrySeconds = Math.min(60, retrySeconds * 2);
        setStatus("Sambungan terputus. Mencoba ulang dalam " + delay + " detik...");
        handler.postDelayed(reconnectRunnable, delay * 1000L);
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!stopping.get() && !running.get()) launch();
        }
    };

    private void stopPipeline(boolean manual) {
        stopping.set(true);
        handler.removeCallbacksAndMessages(null);
        binaryLoading = false;
        if (ffmpeg != null) ffmpeg.killRunningProcesses();
        running.set(false);
        retrySeconds = 3;
        getSharedPreferences("bardi_stream", MODE_PRIVATE).edit()
            .putBoolean("streamRequested", false).apply();
        if (manual) setStatus("Streaming dihentikan.");
        releaseLocks();
        stopForegroundCompat();
    }

    private void clearRequestedState() {
        getSharedPreferences("bardi_stream", MODE_PRIVATE).edit()
            .putBoolean("streamRequested", false).apply();
    }

    private void setStatus(String value) {
        getSharedPreferences("bardi_stream", MODE_PRIVATE).edit()
            .putString("status", value).apply();
    }

    private void releaseLocks() {
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private void stopForegroundCompat() {
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
    }

    @Override public void onDestroy() {
        stopPipeline(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
