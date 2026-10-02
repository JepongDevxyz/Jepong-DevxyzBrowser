package com.jepongdevxyz.browser;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** First-run hero: "DevxyzBrowser — Fast. Private. Flexible." with the four feature cards. */
public final class WelcomeActivity extends AppCompatActivity {
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_welcome);
    findViewById(R.id.welcome_go).setOnClickListener(v -> finishWelcome());
    findViewById(R.id.welcome_skip).setOnClickListener(v -> finishWelcome());
  }

  private void finishWelcome() {
    getSharedPreferences("devxyz", MODE_PRIVATE).edit().putBoolean("welcome_seen", true).apply();
    startActivity(new Intent(this, MainActivity.class));
    finish();
  }
}
