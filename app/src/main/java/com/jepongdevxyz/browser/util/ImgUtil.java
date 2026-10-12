package com.jepongdevxyz.browser.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads images stored as base64 text assets. The repository cannot host binary
 * drawables, so photographic assets (fox logo, backdrops) ship as .b64 text
 * files under assets/img and are decoded at runtime.
 */
public final class ImgUtil {
  private ImgUtil() {}

  /** Decodes a base64 asset into the given ImageView. Safe to call with nulls; failures are silent. */
  public static void setB64(ImageView view, Context ctx, String assetPath) {
    if (view == null || ctx == null || assetPath == null) return;
    try (InputStream in = ctx.getAssets().open(assetPath);
         ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
      byte[] tmp = new byte[8192];
      int n;
      while ((n = in.read(tmp)) != -1) {
        for (int i = 0; i < n; i++) {
          byte b = tmp[i];
          if (b != '\n' && b != '\r' && b != ' ' && b != '\t') buf.write(b);
        }
      }
      byte[] decoded = Base64.decode(buf.toByteArray(), Base64.DEFAULT);
      Bitmap bm = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
      if (bm != null) view.setImageBitmap(bm);
    } catch (Exception ignored) { }
  }

  private static final LruCache<String, Bitmap> MEM = new LruCache<>(64);
  private static final ExecutorService IO = Executors.newFixedThreadPool(3);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  /**
   * Loads a remote image URL into an ImageView on a background thread, with a
   * small in-memory cache. Safe to call with nulls; failures are silent.
   * The view's tag is used to avoid setting a stale image on a reused view.
   */
  public static void loadUrl(ImageView view, String url) {
    if (view == null || url == null || url.isEmpty()) return;
    view.setTag(url);
    Bitmap cached = MEM.get(url);
    if (cached != null) {
      if (url.equals(view.getTag())) view.setImageBitmap(cached);
      return;
    }
    IO.execute(() -> {
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "DevxyzBrowser");
        Bitmap bm;
        try (InputStream in = c.getInputStream()) {
          bm = BitmapFactory.decodeStream(in);
        } finally {
          c.disconnect();
        }
        if (bm == null) return;
        MEM.put(url, bm);
        MAIN.post(() -> {
          try {
            if (url.equals(view.getTag())) view.setImageBitmap(bm);
          } catch (Exception ignored) {}
        });
      } catch (Exception ignored) { }
    });
  }
}
