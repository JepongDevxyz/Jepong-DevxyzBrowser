package com.jepongdevxyz.browser;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import com.jepongdevxyz.browser.util.ImgUtil;
import com.jepongdevxyz.browser.util.TvUtil;

/** Branded launch screen: fox logo, tagline and loading bar over the Earth backdrop. */
public final class SplashActivity extends AppCompatActivity {
  private static final int DURATION_MS = 1700;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ProgressBar progress;
  private long startedAt;

  @Override protected void onCreate(Bundle state) {
    SharedPreferences earlyPrefs = getSharedPreferences("devxyz", MODE_PRIVATE);
    com.jepongdevxyz.browser.util.Appearance.applyTheme(earlyPrefs);
    com.jepongdevxyz.browser.util.Appearance.applyDynamicColors(this, earlyPrefs);
    super.onCreate(state);
    setContentView(R.layout.activity_splash);
    ImgUtil.setB64((ImageView) findViewById(R.id.splash_bg), this, "img/bg_splash.b64");
    ImgUtil.setB64((ImageView) findViewById(R.id.splash_fox), this, "img/fox.b64");
    progress = findViewById(R.id.splash_progress);
    startedAt = System.currentTimeMillis();
    tick();
    handler.postDelayed(this::goNext, DURATION_MS);
  }

  private void tick() {
    if (progress == null) return;
    long elapsed = System.currentTimeMillis() - startedAt;
    int value = (int) Math.min(100, (elapsed * 100) / DURATION_MS);
    progress.setProgress(value);
    if (value < 100) handler.postDelayed(this::tick, 50);
  }

  private void goNext() {
    SharedPreferences prefs = getSharedPreferences("devxyz", MODE_PRIVATE);
    // TV detected -> full browser (MainActivity is TV-aware); phone -> normal flow.
    if (TvUtil.isTv(this)) {
      startActivity(new Intent(this, MainActivity.class));
      finish();
      return;
    }
    boolean seen = prefs.getBoolean("welcome_seen", false);
    Intent next = new Intent(this, seen ? MainActivity.class : WelcomeActivity.class);
    // Forward web links (we're a BROWSABLE browser) so they open in MainActivity.
    Intent src = getIntent();
    if (src != null && Intent.ACTION_VIEW.equals(src.getAction()) && src.getData() != null) {
      next.setAction(Intent.ACTION_VIEW);
      next.setData(src.getData());
    }
    startActivity(next);
    finish();
  }

  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
