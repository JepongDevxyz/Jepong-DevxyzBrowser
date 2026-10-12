package com.jepongdevxyz.browser.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Brave-style "Parallel Downloading": splits a file into parts and downloads
 * them concurrently with HTTP Range requests, then merges. Falls back to a
 * single connection when the server doesn't support ranges.
 */
public final class ParallelDownloader {
  private ParallelDownloader() {}

  public interface Callback {
    void onProgress(int percent);
    void onDone(Uri fileUri);
    void onError(String message);
  }

  private static final int PARTS = 4;
  private static final String CHANNEL = "devxyz_downloads";

  public static void download(Context ctx, String url, String filename, Uri safTarget,
      boolean showNotification, Callback cb) {
    new Thread(() -> {
      NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      int notifId = (int) (System.currentTimeMillis() % 100000);
      try {
        if (Build.VERSION.SDK_INT >= 26) {
          nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads",
              NotificationManager.IMPORTANCE_LOW));
        }
        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(filename)
            .setContentText("Downloading…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, false)
            .setOngoing(true);
        if (showNotification) nm.notify(notifId, nb.build());

        // Probe size + range support.
        HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
        head.setRequestMethod("HEAD");
        head.setConnectTimeout(15000);
        head.setReadTimeout(15000);
        head.setInstanceFollowRedirects(true);
        long total = head.getContentLengthLong();
        String acceptRanges = head.getHeaderField("Accept-Ranges");
        head.disconnect();

        Uri outUri;
        java.io.File outFile = null;
        if (safTarget != null) {
          outUri = safTarget;
        } else {
          java.io.File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
          if (dir == null) dir = ctx.getFilesDir();
          outFile = new java.io.File(dir, filename);
          outUri = Uri.fromFile(outFile);
        }

        boolean useParallel = total > 1024 * 1024 && "bytes".equalsIgnoreCase(acceptRanges) && safTarget == null;
        AtomicLong done = new AtomicLong(0);
        final java.io.File parallelOutFile = outFile;
        if (useParallel) {
          // Pre-allocate.
          try (RandomAccessFile raf = new RandomAccessFile(parallelOutFile, "rw")) {
            raf.setLength(total);
          }
          ExecutorService pool = Executors.newFixedThreadPool(PARTS);
          CountDownLatch latch = new CountDownLatch(PARTS);
          AtomicLong errors = new AtomicLong(0);
          long partSize = total / PARTS;
          for (int i = 0; i < PARTS; i++) {
            final long start = i * partSize;
            final long end = (i == PARTS - 1) ? total - 1 : (start + partSize - 1);
            pool.execute(() -> {
              try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                c.setConnectTimeout(20000);
                c.setReadTimeout(20000);
                try (InputStream in = c.getInputStream();
                     RandomAccessFile raf = new RandomAccessFile(parallelOutFile, "rw")) {
                  raf.seek(start);
                  byte[] buf = new byte[32768];
                  int n;
                  while ((n = in.read(buf)) != -1) {
                    raf.write(buf, 0, n);
                    long d = done.addAndGet(n);
                    int pct = (int) (d * 100 / total);
                    cb.onProgress(pct);
                    if (showNotification) {
                      nb.setProgress(100, pct, false);
                      nm.notify(notifId, nb.build());
                    }
                  }
                }
                c.disconnect();
              } catch (Exception e) {
                errors.incrementAndGet();
              } finally {
                latch.countDown();
              }
            });
          }
          latch.await();
          pool.shutdown();
          if (errors.get() > 0) throw new Exception("A download part failed");
        } else {
          // Single connection (also used for SAF targets).
          HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
          c.setConnectTimeout(20000);
          c.setReadTimeout(20000);
          c.setInstanceFollowRedirects(true);
          long len = c.getContentLengthLong();
          try (InputStream in = c.getInputStream();
               OutputStream out = safTarget != null
                   ? ctx.getContentResolver().openOutputStream(safTarget)
                   : new java.io.FileOutputStream(outFile)) {
            byte[] buf = new byte[32768];
            int n;
            long d = 0;
            while ((n = in.read(buf)) != -1) {
              out.write(buf, 0, n);
              d += n;
              if (len > 0) {
                int pct = (int) (d * 100 / len);
                cb.onProgress(pct);
                if (showNotification) {
                  nb.setProgress(100, pct, false);
                  nm.notify(notifId, nb.build());
                }
              }
            }
          }
          c.disconnect();
        }

        nb.setContentText("Download complete").setProgress(0, 0, false).setOngoing(false)
            .setSmallIcon(android.R.drawable.stat_sys_download_done);
        if (showNotification) nm.notify(notifId, nb.build());
        else nm.cancel(notifId);
        cb.onDone(outUri);
      } catch (Exception e) {
        nm.cancel(notifId);
        cb.onError(e.getMessage());
      }
    }).start();
  }
}
