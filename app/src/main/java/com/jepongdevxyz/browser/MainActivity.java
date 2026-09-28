package com.jepongdevxyz.browser;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.graphics.drawable.GradientDrawable;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import android.content.Intent;
import android.net.VpnService;
import org.mozilla.geckoview.*;
import java.util.*;
import androidx.appcompat.widget.PopupMenu;

public final class MainActivity extends AppCompatActivity {
  private static GeckoRuntime runtime;
  private GeckoSession session;
  private EditText address, heroSearch;
  private ProgressBar progress;
  private View startPage;
  private View menuButton;
  private TextView vpnStatus;
  private LinearLayout tabStrip;
  private org.mozilla.geckoview.GeckoView geckoView;
  private ActivityResultLauncher<String[]> openVpnProfile;
  private ActivityResultLauncher<Intent> vpnPermission;
  private boolean vpnReceiverRegistered;
  private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
      if (!DevxyzVpnService.ACTION_STATE.equals(intent.getAction())) return;
      String state = intent.getStringExtra("state");
      String detail = intent.getStringExtra("detail");
      boolean error = intent.getBooleanExtra("error", false);
      if ("CONNECTED".equals(state)) vpnStatus.setText("Connected • " + safe(detail));
      else if ("CONNECTING".equals(state) || "WAIT".equals(state) || "RECONNECTING".equals(state)) vpnStatus.setText("Connecting • " + safe(detail));
      else if (error) vpnStatus.setText("Failed • " + safe(detail));
      else vpnStatus.setText(VpnProfileStore.hasProfile(MainActivity.this) ? "Disconnected • profile ready" : "Disconnected • No profile imported");
      if (error) Toast.makeText(MainActivity.this, safe(detail), Toast.LENGTH_LONG).show();
    }
  };
  private final ArrayList<String> historyItems = new ArrayList<>();
  private final ArrayList<String> bookmarks = new ArrayList<>();
  private final ArrayList<BrowserTab> tabs = new ArrayList<>();
  private BrowserTab activeTab;

  private static final class BrowserTab {
    final GeckoSession session;
    String url = "";
    LinearLayout chip;
    TextView label;
    BrowserTab(GeckoSession session) { this.session = session; }
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    vpnPermission = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      if (result.getResultCode() == RESULT_OK) {
        requestVpnCredentials();
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
    geckoView = findViewById(R.id.gecko);
    session = new GeckoSession();
    session.open(runtime);
    activeTab = new BrowserTab(session);
    tabs.add(activeTab);
    address = findViewById(R.id.address);
    heroSearch = findViewById(R.id.heroSearch);
    progress = findViewById(R.id.pageProgress);
    startPage = findViewById(R.id.startPage);
    vpnStatus = findViewById(R.id.vpnStatus);
    tabStrip = findViewById(R.id.tabStrip);
    menuButton = findViewById(R.id.menu);
    addTabChip(activeTab);
    attachSession(activeTab);
    selectTab(activeTab);
    vpnStatus.setText(DevxyzVpnService.isConnected() ? "Connected • OpenVPN" :
      (VpnProfileStore.hasProfile(this) ? "Ready • profile imported" : "Disconnected • No profile imported"));
    IntentFilter vpnFilter = new IntentFilter(DevxyzVpnService.ACTION_STATE);
    ContextCompat.registerReceiver(this, vpnStateReceiver, vpnFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    vpnReceiverRegistered = true;

    findViewById(R.id.back).setOnClickListener(v -> session.goBack());
    findViewById(R.id.refresh).setOnClickListener(v -> session.reload());
    findViewById(R.id.newTab).setOnClickListener(v -> newTab());
    menuButton.setOnClickListener(this::showBrowserMenu);
    findViewById(R.id.home).setOnClickListener(v -> showHome());
    findViewById(R.id.bookmarks).setOnClickListener(v -> showBookmarks());
    findViewById(R.id.history).setOnClickListener(v -> showList("History", historyItems));
    findViewById(R.id.downloads).setOnClickListener(v -> showDownloads());
    findViewById(R.id.extensions).setOnClickListener(v -> showExtensions());
    findViewById(R.id.vpn).setOnClickListener(v -> showVpn());
    findViewById(R.id.vpnPanel).setOnClickListener(v -> showVpn());
    findViewById(R.id.settings).setOnClickListener(v -> showSettings());
    findViewById(R.id.addSite).setOnClickListener(v -> {
      EditText site = new EditText(this);
      site.setSingleLine(true); site.setHint("https://example.com");
      new AlertDialog.Builder(this).setTitle("Open a site").setView(site)
        .setPositiveButton("Open", (d,w) -> browse(site.getText().toString()))
        .setNegativeButton("Cancel", null).show();
    });

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
        if(startPage.getVisibility()!=View.VISIBLE && activeTab != null && !activeTab.url.isEmpty()) session.goBack();
        else if(startPage.getVisibility()!=View.VISIBLE) showHome();
        else finish();
      }
    });
    showHome();
  }

  private void showHome() {
    startPage.setVisibility(View.VISIBLE);
    address.setText("");
    progress.setVisibility(View.GONE);
    progress.setProgress(0);
  }

  private void newTab() {
    GeckoSession newSession = new GeckoSession();
    newSession.open(runtime);
    BrowserTab tab = new BrowserTab(newSession);
    tabs.add(tab);
    attachSession(tab);
    addTabChip(tab);
    selectTab(tab);
  }

  private void attachSession(BrowserTab tab) {
    tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
      @Override public void onPageStart(GeckoSession s, String url) {
        tab.url = url;
        if (tab == activeTab) {
          address.setText(url); progress.setVisibility(View.VISIBLE); progress.setProgress(5);
        }
        updateTabLabel(tab);
        if (!historyItems.contains(url)) historyItems.add(0, url);
      }
      @Override public void onPageStop(GeckoSession s, boolean ok) {
        if (tab == activeTab) {
          progress.setProgress(100);
          progress.postDelayed(() -> { progress.setProgress(0); progress.setVisibility(View.GONE); }, 350);
        }
      }
    });
  }

  private void selectTab(BrowserTab tab) {
    activeTab = tab;
    session = tab.session;
    geckoView.setSession(session);
    startPage.setVisibility(tab.url.isEmpty() ? View.VISIBLE : View.GONE);
    address.setText(tab.url.isEmpty() ? "" : tab.url);
    progress.setVisibility(View.GONE);
    for (BrowserTab item : tabs) updateTabLabel(item);
  }

  private void addTabChip(BrowserTab tab) {
    LinearLayout chip = new LinearLayout(this);
    chip.setGravity(Gravity.CENTER_VERTICAL);
    chip.setOrientation(LinearLayout.HORIZONTAL);
    tab.chip = chip;
    tab.label = new TextView(this);
    tab.label.setTextSize(13);
    tab.label.setTextColor(getColor(R.color.text));
    tab.label.setGravity(Gravity.CENTER_VERTICAL);
    int pad = (int) (13 * getResources().getDisplayMetrics().density);
    tab.label.setPadding(pad, 0, 4, 0);
    chip.addView(tab.label, new LinearLayout.LayoutParams(0,
      (int) (38 * getResources().getDisplayMetrics().density), 1));
    TextView close = new TextView(this);
    close.setText("×");
    close.setTextSize(18);
    close.setTextColor(getColor(R.color.muted));
    close.setGravity(Gravity.CENTER);
    close.setContentDescription("Close tab");
    chip.addView(close, new LinearLayout.LayoutParams(
      (int) (34 * getResources().getDisplayMetrics().density),
      (int) (38 * getResources().getDisplayMetrics().density)));
    close.setOnClickListener(v -> closeTab(tab));
    updateTabLabel(tab);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(getColor(R.color.panel2));
    bg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
    bg.setStroke((int) getResources().getDisplayMetrics().density, getColor(R.color.stroke));
    chip.setBackground(bg);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, (int) (38 * getResources().getDisplayMetrics().density));
    lp.setMargins(3, 0, 5, 0);
    tabStrip.addView(chip, lp);
    chip.setOnClickListener(v -> selectTab(tab));
  }

  private void updateTabLabel(BrowserTab tab) {
    if (tab.chip == null) return;
    String title = tab.url.isEmpty() ? "New Tab" : Uri.parse(tab.url).getHost();
    if (title == null || title.isEmpty()) title = "Page";
    tab.label.setText((tab == activeTab ? "●  " : "◉  ") + title);
    tab.chip.setContentDescription(title + ". Long press to close tab.");
  }

  private void closeTab(BrowserTab tab) {
    int index = tabs.indexOf(tab);
    if (index < 0) return;
    tabs.remove(index);
    tabStrip.removeView(tab.chip);
    tab.session.close();
    if (tabs.isEmpty()) newTab();
    else if (activeTab == tab) selectTab(tabs.get(Math.max(0, index - 1)));
  }

  private void showBrowserMenu(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(0, 1, 0, "New tab");
    menu.getMenu().add(0, 2, 1, "Bookmarks");
    menu.getMenu().add(0, 3, 2, "History");
    menu.getMenu().add(0, 4, 3, "Downloads");
    menu.getMenu().add(0, 5, 4, "Extensions");
    menu.getMenu().add(0, 6, 5, "VPN");
    menu.getMenu().add(0, 7, 6, "Settings");
    menu.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 1: newTab(); return true;
        case 2: showBookmarks(); return true;
        case 3: showList("History", historyItems); return true;
        case 4: showDownloads(); return true;
        case 5: showExtensions(); return true;
        case 6: showVpn(); return true;
        case 7: showSettings(); return true;
        default: return false;
      }
    });
    menu.show();
  }

  private void showDownloads() {
    showInfo("Downloads", "This browser build does not yet include a download manager. No download list is being shown as if it were available.");
  }

  private void showSettings() {
    String vpn = DevxyzVpnService.isConnected() ? "Connected" : "Disconnected";
    showInfo("Settings", "DevxyzBrowser • GeckoView 156\nVPN: " + vpn +
      "\nExtensions are managed from the toolbar. Site data and app preferences are stored on this device.");
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
         startService(stop); vpnStatus.setText("Disconnecting • stopping OpenVPN");
       });
    } else {
      b.setMessage("Connect with the imported OpenVPN profile. DevxyzBrowser will show Connected only after the OpenVPN core reports a connected tunnel.")
       .setPositiveButton("Connect", (d,w) -> {
         Intent permission = VpnService.prepare(this);
         if (permission != null) vpnPermission.launch(permission);
         else requestVpnCredentials();
       })
       .setNeutralButton("Replace profile", (d,w) -> openVpnProfile.launch(new String[]{"application/x-openvpn-profile","application/octet-stream","text/plain"}));
    }
    b.setNegativeButton("Close",null).show();
  }

  private void showInfo(String title,String message) {
    new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();
  }

  private void requestVpnCredentials() {
    try {
      if (!VpnProfileStore.requiresCredentials(this)) {
        startOpenVpn("", "", "");
        return;
      }
    } catch (Exception e) {
      Toast.makeText(this, "Could not read VPN profile: " + e.getMessage(), Toast.LENGTH_LONG).show();
      return;
    }
    LinearLayout fields = new LinearLayout(this);
    fields.setOrientation(LinearLayout.VERTICAL);
    int pad = (int)(20 * getResources().getDisplayMetrics().density);
    fields.setPadding(pad, 0, pad, 0);
    EditText username = new EditText(this);
    username.setSingleLine(true); username.setHint("Username (if required)");
    EditText password = new EditText(this);
    password.setSingleLine(true); password.setHint("VPN password (if required)");
    password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    EditText keyPassword = new EditText(this);
    keyPassword.setSingleLine(true); keyPassword.setHint("Private key password (if required)");
    keyPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    fields.addView(username); fields.addView(password); fields.addView(keyPassword);
    new AlertDialog.Builder(this).setTitle("VPN credentials")
      .setMessage("These credentials are used for this connection and are not saved by the app.")
      .setView(fields)
      .setPositiveButton("Connect", (dialog, which) -> startOpenVpn(
        username.getText().toString(), password.getText().toString(), keyPassword.getText().toString()))
      .setNegativeButton("Cancel", null).show();
  }

  private void startOpenVpn(String username, String password, String keyPassword) {
    Intent connect = new Intent(this, DevxyzVpnService.class).setAction(DevxyzVpnService.ACTION_CONNECT)
      .putExtra(DevxyzVpnService.EXTRA_USERNAME, username)
      .putExtra(DevxyzVpnService.EXTRA_PASSWORD, password)
      .putExtra(DevxyzVpnService.EXTRA_KEY_PASSWORD, keyPassword);
    try {
      ContextCompat.startForegroundService(this, connect);
      vpnStatus.setText("Connecting • starting OpenVPN");
    } catch (Exception e) {
      Toast.makeText(this, "Could not start VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private String safe(String value) { return value == null || value.trim().isEmpty() ? "OpenVPN" : value; }

  @Override protected void onDestroy() {
    if (vpnReceiverRegistered) { unregisterReceiver(vpnStateReceiver); vpnReceiverRegistered = false; }
    if(session!=null) session.close();
    super.onDestroy();
  }
}
