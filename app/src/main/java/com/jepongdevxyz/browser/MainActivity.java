package com.jepongdevxyz.browser;

import android.app.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import android.content.Intent;
import android.net.VpnService;
import org.mozilla.geckoview.*;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
  private static GeckoRuntime runtime;
  private GeckoSession session;
  private EditText address, heroSearch;
  private ProgressBar progress;
  private View startPage;
  private TextView vpnStatus;
  private ActivityResultLauncher<String[]> openVpnProfile;
  private ActivityResultLauncher<Intent> vpnPermission;
  private final ArrayList<String> historyItems = new ArrayList<>();
  private final ArrayList<String> bookmarks = new ArrayList<>();

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    vpnPermission = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      if (result.getResultCode() == RESULT_OK) {
        Toast.makeText(this, "VPN permission granted", Toast.LENGTH_SHORT).show();
        showVpn();
      } else {
        Toast.makeText(this, "VPN permission is required to connect", Toast.LENGTH_LONG).show();
      }
    });
    openVpnProfile = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
      if (uri == null) return;
      try {
        String endpoint = VpnProfileStore.importProfile(this, uri);
        vpnStatus.setText("Ready • " + endpoint);
        Toast.makeText(this, "OpenVPN profile imported", Toast.LENGTH_LONG).show();
      } catch (Exception ex) {
        Toast.makeText(this, "Profile rejected: " + ex.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
    if (runtime == null) runtime = GeckoRuntime.create(this);
    session = new GeckoSession();
    session.open(runtime);
    ((GeckoView)findViewById(R.id.gecko)).setSession(session);
    address = findViewById(R.id.address);
    heroSearch = findViewById(R.id.heroSearch);
    progress = findViewById(R.id.progress);
    startPage = findViewById(R.id.startPage);
    vpnStatus = findViewById(R.id.vpnStatus);
    vpnStatus.setText(VpnProfileStore.hasProfile(this) ? "Ready • profile imported" : "Disconnected • No profile imported");

    session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
      @Override public void onPageStart(GeckoSession s, String url) {
        address.setText(url); progress.setProgress(5);
        if (!historyItems.contains(url)) historyItems.add(0, url);
      }
      @Override public void onPageStop(GeckoSession s, boolean ok) {
        progress.setProgress(100); progress.postDelayed(() -> progress.setProgress(0), 250);
      }
    });

    findViewById(R.id.back).setOnClickListener(v -> session.goBack());
    findViewById(R.id.refresh).setOnClickListener(v -> session.reload());
    findViewById(R.id.home).setOnClickListener(v -> showHome());
    findViewById(R.id.bookmarks).setOnClickListener(v -> showBookmarks());
    findViewById(R.id.history).setOnClickListener(v -> showList("History", historyItems));
    findViewById(R.id.downloads).setOnClickListener(v -> showInfo("Downloads", "Downloads requested by web pages are handled by GeckoView. A dedicated download manager is the next browser-service stage."));
    findViewById(R.id.extensions).setOnClickListener(v -> showExtensions());
    findViewById(R.id.extensionsSide).setOnClickListener(v -> showExtensions());
    findViewById(R.id.vpn).setOnClickListener(v -> showVpn());
    findViewById(R.id.vpnSide).setOnClickListener(v -> showVpn());
    findViewById(R.id.vpnPanel).setOnClickListener(v -> showVpn());
    findViewById(R.id.settings).setOnClickListener(v -> showInfo("Settings", "DevxyzBrowser • GeckoView 156\nPrivacy and browser preferences will remain device-local."));

    View.OnClickListener quick = v -> {
      int id=v.getId();
      if(id==R.id.siteYoutube) browse("https://www.youtube.com");
      else if(id==R.id.siteFacebook) browse("https://www.facebook.com");
      else if(id==R.id.siteGithub) browse("https://github.com");
      else if(id==R.id.siteReddit) browse("https://www.reddit.com");
    };
    findViewById(R.id.siteYoutube).setOnClickListener(quick);
    findViewById(R.id.siteFacebook).setOnClickListener(quick);
    findViewById(R.id.siteGithub).setOnClickListener(quick);
    findViewById(R.id.siteReddit).setOnClickListener(quick);

    address.setOnEditorActionListener((v,id,e) -> {
      if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) { browse(address.getText().toString()); return true; }
      return false;
    });
    heroSearch.setOnEditorActionListener((v,id,e) -> {
      if(id==EditorInfo.IME_ACTION_SEARCH || id==EditorInfo.IME_ACTION_GO || id==EditorInfo.IME_ACTION_DONE) { browse(heroSearch.getText().toString()); return true; }
      return false;
    });
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() {
        if(startPage.getVisibility()==View.VISIBLE) return;
        session.goBack();
      }
    });
    showHome();
  }

  private void showHome() {
    startPage.setVisibility(View.VISIBLE);
    address.setText("");
  }

  private void browse(String raw) {
    String q = raw.trim();
    if(q.isEmpty()) return;
    startPage.setVisibility(View.GONE);
    load(q);
  }

  private void load(String raw) {
    String q = raw.trim();
    if (!q.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
      if (q.contains(".") && !q.contains(" ")) q = "https://" + q;
      else q = "https://www.google.com/search?q=" + Uri.encode(q);
    }
    session.loadUri(q);
  }

  private void showBookmarks() {
    String current=address.getText().toString().trim();
    AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("Bookmarks");
    if(bookmarks.isEmpty()) b.setMessage("No saved pages yet.");
    else b.setItems(bookmarks.toArray(new String[0]), (d,which)->browse(bookmarks.get(which)));
    if(current.startsWith("http")) b.setPositiveButton("Save current", (d,w)->{ if(!bookmarks.contains(current)) bookmarks.add(0,current); });
    b.setNegativeButton("Close",null).show();
  }

  private void showList(String title, ArrayList<String> items) {
    if(items.isEmpty()) { showInfo(title,"Nothing here yet."); return; }
    new AlertDialog.Builder(this).setTitle(title).setItems(items.toArray(new String[0]),(d,w)->browse(items.get(w))).setNegativeButton("Close",null).show();
  }

  private void showExtensions() {
    final EditText xpi = new EditText(this);
    xpi.setHint("Mozilla-signed .xpi URL");
    new AlertDialog.Builder(this).setTitle("Extensions")
      .setMessage("Install a Mozilla-signed WebExtension. Installed add-ons persist across restarts.")
      .setView(xpi).setPositiveButton("Install", (d,w) -> {
        String uri=xpi.getText().toString().trim();
        if(uri.startsWith("https://")) runtime.getWebExtensionController().install(uri)
          .accept(ext -> Toast.makeText(this,"Installed: "+ext.metaData.name,Toast.LENGTH_LONG).show(),
                  err -> Toast.makeText(this,"Install failed: "+err.getMessage(),Toast.LENGTH_LONG).show());
        else Toast.makeText(this,"Use a secure HTTPS .xpi URL",Toast.LENGTH_LONG).show();
      }).setNeutralButton("Manage", (d,w) -> runtime.getWebExtensionController().list()
        .accept(list -> {
          String[] names=new String[list.size()];
          for(int i=0;i<list.size();i++) names[i]=list.get(i).metaData.name;
          new AlertDialog.Builder(this).setTitle("Installed extensions").setItems(names,null).setPositiveButton("Done",null).show();
        }, err -> Toast.makeText(this,"Unable to list extensions",Toast.LENGTH_LONG).show()))
      .setNegativeButton("Cancel",null).show();
  }

  private void showVpn() {
    AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("OpenVPN");
    if (!VpnProfileStore.hasProfile(this)) {
      b.setMessage("Import an authorized .ovpn profile. DevxyzBrowser validates and stores it privately on this device.")
       .setPositiveButton("Import .ovpn", (d,w) -> openVpnProfile.launch(new String[]{"application/x-openvpn-profile","application/octet-stream","text/plain"}));
    } else if (DevxyzVpnService.isConnected()) {
      b.setMessage("Connected through OpenVPN.")
       .setPositiveButton("Disconnect", (d,w) -> {
         Intent stop = new Intent(this, DevxyzVpnService.class).setAction(DevxyzVpnService.ACTION_DISCONNECT);
         startService(stop); vpnStatus.setText("Disconnected • profile ready");
       });
    } else {
      b.setMessage("Profile ready. Android VPN permission can now be granted. Connection will only be reported after the OpenVPN native transport establishes the tunnel.")
       .setPositiveButton("Connect", (d,w) -> {
         Intent permission = VpnService.prepare(this);
         if (permission != null) vpnPermission.launch(permission);
         else Toast.makeText(this, "VPN permission ready; native OpenVPN transport is the remaining connection stage.", Toast.LENGTH_LONG).show();
       })
       .setNeutralButton("Replace profile", (d,w) -> openVpnProfile.launch(new String[]{"application/x-openvpn-profile","application/octet-stream","text/plain"}));
    }
    b.setNegativeButton("Close",null).show();
  }

  private void showInfo(String title,String message) {
    new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();
  }

  @Override protected void onDestroy() { if(session!=null) session.close(); super.onDestroy(); }
}