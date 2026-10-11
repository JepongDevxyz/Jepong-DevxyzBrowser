package com.jepongdevxyz.browser.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

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
}
