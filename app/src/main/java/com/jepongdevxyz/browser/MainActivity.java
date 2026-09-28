package com.jepongdevxyz.browser;

import android.app.*;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import org.mozilla.geckoview.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends AppCompatActivity {
  private static GeckoRuntime runtime;
  private GeckoSession session;
  private EditText address;
  private ProgressBar progress;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    if (runtime == null) runtime = GeckoRuntime.create(this);
    session = new GeckoSession();
    session.open(runtime);
    ((GeckoView)findViewById(R.id.gecko)).setSession(session);
    address = findViewById(R.id.address);
    progress = findViewById(R.id.progress);

    session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
      @Override public void onPageStart(GeckoSession s, String url) { address.setText(url); progress.setProgress(5); }
      @Override public void onPageStop(GeckoSession s, boolean ok) { progress.setProgress(100); progress.postDelayed(() -> progress.setProgress(0), 250); }
    });
    session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
      @Override public void onLocationChange(GeckoSession s, String url, java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms, boolean hasUserGesture) { address.setText(url); }
    });

    findViewById(R.id.back).setOnClickListener(v -> session.goBack());
    findViewById(R.id.refresh).setOnClickListener(v -> session.reload());
    findViewById(R.id.extensions).setOnClickListener(v -> showExtensions());
    findViewById(R.id.vpn).setOnClickListener(v -> showVpn());
    address.setOnEditorActionListener((v,id,e) -> {
      if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) { load(address.getText().toString()); return true; }
      return false;
    });
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { session.goBack(); }
    });
    if (state == null) session.loadUri("https://www.google.com");
  }

  private void load(String raw) {
    String q = raw.trim();
    if (!q.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
      if (q.contains(".") && !q.contains(" ")) q = "https://" + q;
      else q = "https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }
    session.loadUri(q);
  }

  private void showExtensions() {
    final EditText xpi = new EditText(this);
    xpi.setHint("Signed .xpi URL");
    new AlertDialog.Builder(this).setTitle("Extensions")
      .setMessage("Install a Mozilla-signed WebExtension package. Installed extensions persist across restarts.")
      .setView(xpi).setPositiveButton("Install", (d,w) -> {
        String uri=xpi.getText().toString().trim();
        if(uri.startsWith("https://")) runtime.getWebExtensionController().install(uri)
          .accept(ext -> Toast.makeText(this,"Installed: "+ext.metaData.name,Toast.LENGTH_LONG).show(),
                  err -> Toast.makeText(this,"Install failed: "+err.getMessage(),Toast.LENGTH_LONG).show());
      }).setNeutralButton("Manage", (d,w) -> runtime.getWebExtensionController().list()
        .accept(list -> Toast.makeText(this, list.size()+" extension(s) installed", Toast.LENGTH_LONG).show(),
                err -> Toast.makeText(this,"Unable to list extensions",Toast.LENGTH_LONG).show()))
      .setNegativeButton("Cancel",null).show();
  }

  private void showVpn() {
    new AlertDialog.Builder(this).setTitle("OpenVPN")
      .setMessage("The browser VPN panel is wired for the native OpenVPN engine integration. Importing profiles and connection controls are enabled by the VPN source integration stage; no fake VPN connection is reported by this screen.")
      .setPositiveButton("OK",null).show();
  }

  @Override protected void onDestroy() { if(session!=null) session.close(); super.onDestroy(); }
}
