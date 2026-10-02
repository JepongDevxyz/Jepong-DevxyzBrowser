package com.jepongdevxyz.browser;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

/** Branded launch screen: fox logo, tagline and loading bar over the Earth backdrop. */
public final class SplashActivity extends AppCompatActivity {
  private static final int DURATION_MS = 1700;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ProgressBar progress;
  private long startedAt;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_splash);
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
    boolean seen = prefs.getBoolean("welcome_seen", false);
    Intent next = new Intent(this, seen ? MainActivity.class : WelcomeActivity.class);
    startActivity(next);
    finish();
  }

  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
