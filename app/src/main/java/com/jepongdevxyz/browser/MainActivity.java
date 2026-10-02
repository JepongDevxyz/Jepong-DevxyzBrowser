package com.jepongdevxyz.browser;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import android.content.Intent;
import android.net.VpnService;
import org.mozilla.geckoview.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
  private SharedPreferences prefs;
  private boolean isDesktop;

  // Desktop right-panel widgets (null on phones).
  private View vpnPanelRoot, extPanelRoot;
  private ImageButton vpnPower;
  private ImageView vpnSecureIcon;
  private TextView vpnStateText, vpnHostText, vpnSubText, vpnSecureTitle, vpnIpText;
  private SwitchMaterial swAuto, swKill, swTrack;
  private EditText extSearch;
  private LinearLayout extInstalledList, extRecommendedList;
  private LinearLayout tileRow;

  private boolean ipLookedUp;
  private String extFilter = "";
  private final ArrayList<WebExtension> installedExts = new ArrayList<>();
  private final Set<String> recInstalled = new HashSet<>();

  private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
      if (!DevxyzVpnService.ACTION_STATE.equals(intent.getAction())) return;
      String state = intent.getStringExtra("state");
      String detail = intent.getStringExtra("detail");
      boolean error = intent.getBooleanExtra("error", false);
      if ("CONNECTED".equals(state)) setVpnStatus("Connected • " + safe(detail));
      else if ("CONNECTING".equals(state) || "WAIT".equals(state) || "RECONNECTING".equals(state)) setVpnStatus("Connecting • " + safe(detail));
      else if (error) setVpnStatus("Failed • " + safe(detail));
      else setVpnStatus(VpnProfileStore.hasProfile(MainActivity.this) ? "Disconnected • profile ready" : "Disconnected • No profile imported");
      if (error) Toast.makeText(MainActivity.this, safe(detail), Toast.LENGTH_LONG).show();
      refreshVpnPanel();
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

  private static final class RecExt {
    final String name, desc, slug;
    final int icon;
    RecExt(String name, String desc, String slug, int icon) {
      this.name = name; this.desc = desc; this.slug = slug; this.icon = icon;
    }
  }

  private static final RecExt[] RECOMMENDED = {
    new RecExt("uBlock Origin", "Block ads and trackers", "ublock-origin", R.drawable.ic_ext_ublock),
    new RecExt("Dark Reader", "Dark mode for all websites", "darkreader", R.drawable.ic_ext_darkreader),
    new RecExt("SponsorBlock", "Skip sponsored content", "sponsorblock", R.drawable.ic_ext_sponsorblock),
    new RecExt("React Developer Tools", "Debug React apps", "react-devtools", R.drawable.ic_ext_react),
  };

  private static final class Tile {
    String name = "";
    String url = "";
  }
  private final ArrayList<Tile> customTiles = new ArrayList<>();

  private static final int[] SIDEBAR_IDS = {
    R.id.home, R.id.bookmarks, R.id.history, R.id.downloads, R.id.extensions, R.id.vpn, R.id.settings
  };

  private void setVpnStatus(String s) {
    if (vpnStatus != null) vpnStatus.setText(s);
  }

  private void onClick(int id, View.OnClickListener l) {
    View v = findViewById(id);
    if (v != null) v.setOnClickListener(l);
  }

  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density);
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("devxyz", MODE_PRIVATE);
    setContentView(R.layout.activity_main);
    isDesktop = findViewById(R.id.sidebar) != null;
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    View content = findViewById(android.R.id.content);
    ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return WindowInsetsCompat.CONSUMED;
    });
    ViewCompat.requestApplyInsets(content);
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
        setVpnStatus("Ready • " + endpoint);
        Toast.makeText(this, "OpenVPN profile imported", Toast.LENGTH_LONG).show();
      } catch (Exception ex) {
        Toast.makeText(this, "Profile rejected: " + ex.getMessage(), Toast.LENGTH_LONG).show();
      }
      refreshVpnPanel();
    });
    if (runtime == null) {
      GeckoRuntimeSettings.Builder rsb = new GeckoRuntimeSettings.Builder();
      if (prefs.getBoolean("block_trackers", false)) {
        rsb.enhancedTrackingProtectionLevel(GeckoRuntimeSettings.EnhancedTrackingProtectionLevel.STRICT);
      }
      runtime = GeckoRuntime.create(this, rsb.build());
    }
    geckoView = findViewById(R.id.gecko);
    session = new GeckoSession();
    session.open(runtime);
    activeTab = new BrowserTab(session);
    tabs.add(activeTab);
    address = findViewById(R.id.address);
    heroSearch = findViewById(R.id.heroSearch);
    progress = findViewById(R.id.pageProgress);
    startPage = findViewById(R.id.startPageWrap);
    if (startPage == null) startPage = findViewById(R.id.startPage);
    vpnStatus = findViewById(R.id.vpnStatus);
    tabStrip = findViewById(R.id.tabStrip);
    menuButton = findViewById(R.id.menu);
    addTabChip(activeTab);
    attachSession(activeTab);
    selectTab(activeTab);
    setVpnStatus(DevxyzVpnService.isConnected() ? "Connected • OpenVPN" :
      (VpnProfileStore.hasProfile(this) ? "Ready • profile imported" : "Disconnected • No profile imported"));
    IntentFilter vpnFilter = new IntentFilter(DevxyzVpnService.ACTION_STATE);
    ContextCompat.registerReceiver(this, vpnStateReceiver, vpnFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    vpnReceiverRegistered = true;

    onClick(R.id.back, v -> session.goBack());
    onClick(R.id.forward, v -> session.goForward());
    onClick(R.id.refresh, v -> session.reload());
    onClick(R.id.newTab, v -> newTab());
    onClick(R.id.menu, this::showBrowserMenu);
    onClick(R.id.home, v -> { showHome(); setSidebarActive(R.id.home); });
    onClick(R.id.bookmarks, v -> { showBookmarks(); setSidebarActive(R.id.bookmarks); });
    onClick(R.id.history, v -> { showList("History", historyItems); setSidebarActive(R.id.history); });
    onClick(R.id.downloads, v -> { showDownloads(); setSidebarActive(R.id.downloads); });
    onClick(R.id.dl_toolbar, v -> showDownloads());
    onClick(R.id.extensions, v -> showExtensions());
    onClick(R.id.ext_toolbar, v -> showExtensions());
    onClick(R.id.extensionsPanel, v -> showExtensions());
    onClick(R.id.vpn, v -> showVpn());
    onClick(R.id.vpn_chip, v -> showVpn());
    onClick(R.id.vpnPanel, v -> showVpn());
    onClick(R.id.settings, v -> { showSettings(); setSidebarActive(R.id.settings); });
    onClick(R.id.profile_btn, v -> showProfile());
    onClick(R.id.addSite, v -> showAddSite());

    View.OnClickListener quick = v -> {
      int id = v.getId();
      if (id == R.id.siteYoutube) browse("https://www.youtube.com");
      else if (id == R.id.siteFacebook) browse("https://www.facebook.com");
      else if (id == R.id.siteGithub) browse("https://github.com");
      else if (id == R.id.siteReddit) browse("https://www.reddit.com");
      else if (id == R.id.siteX) browse("https://x.com");
    };
    onClick(R.id.siteYoutube, quick);
    onClick(R.id.siteFacebook, quick);
    onClick(R.id.siteGithub, quick);
    onClick(R.id.siteReddit, quick);
    onClick(R.id.siteX, quick);

    address.setOnEditorActionListener((v, id, e) -> {
      if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) { browse(address.getText().toString()); return true; }
      return false;
    });
    heroSearch.setOnEditorActionListener((v, id, e) -> {
      if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) { browse(heroSearch.getText().toString()); return true; }
      return false;
    });
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() {
        if (startPage.getVisibility() != View.VISIBLE && activeTab != null && !activeTab.url.isEmpty()) session.goBack();
        else if (startPage.getVisibility() != View.VISIBLE) showHome();
        else finish();
      }
    });

    recInstalled.addAll(prefs.getStringSet("rec_exts", new HashSet<>()));
    initVpnPanel();
    initExtPanel();
    loadCustomTiles();
    setSidebarActive(R.id.home);

    DevxyzVpnService.killSwitch = prefs.getBoolean("kill_switch", false);
    if (prefs.getBoolean("auto_connect", false) && VpnProfileStore.hasProfile(this) && !DevxyzVpnService.isConnected()) {
      try {
        if (!VpnProfileStore.requiresCredentials(this)) {
          Intent permission = VpnService.prepare(this);
          if (permission != null) vpnPermission.launch(permission);
          else startOpenVpn("", "", "");
        }
      } catch (Exception ignored) { }
    }
    showHome();
  }

  // ---------- Sidebar ----------
  private void setSidebarActive(int activeId) {
    if (!isDesktop) return;
    for (int id : SIDEBAR_IDS) {
      View item = findViewById(id);
      if (!(item instanceof LinearLayout)) continue;
      LinearLayout row = (LinearLayout) item;
      boolean active = id == activeId;
      int color = getColor(active ? R.color.purple2 : R.color.muted);
      if (row.getChildCount() >= 2) {
        View icon = row.getChildAt(0);
        View label = row.getChildAt(1);
        if (icon instanceof ImageView) ((ImageView) icon).setImageTintList(ColorStateList.valueOf(color));
        if (label instanceof TextView) ((TextView) label).setTextColor(color);
      }
      row.setBackgroundColor(active ? 0x1A7C3CFF : 0x00000000);
    }
  }

  // ---------- Right panels (desktop) ----------
  private void togglePanel(int panelRootId) {
    View container = findViewById(R.id.right_panels);
    View target = findViewById(panelRootId);
    if (container == null || target == null) return;
    boolean show = target.getVisibility() != View.VISIBLE;
    target.setVisibility(show ? View.VISIBLE : View.GONE);
    updatePanelsContainer();
    if (show && panelRootId == R.id.vpn_panel_root) refreshVpnPanel();
    if (show && panelRootId == R.id.ext_panel_root) refreshExtensionLists();
  }

  private void updatePanelsContainer() {
    View container = findViewById(R.id.right_panels);
    View vpnR = findViewById(R.id.vpn_panel_root);
    View extR = findViewById(R.id.ext_panel_root);
    View gap = findViewById(R.id.panels_gap);
    if (container == null || vpnR == null || extR == null) return;
    boolean vpnVisible = vpnR.getVisibility() == View.VISIBLE;
    boolean extVisible = extR.getVisibility() == View.VISIBLE;
    container.setVisibility((vpnVisible || extVisible) ? View.VISIBLE : View.GONE);
    if (gap != null) gap.setVisibility((vpnVisible && extVisible) ? View.VISIBLE : View.GONE);
  }

  // ---------- VPN panel ----------
  private void initVpnPanel() {
    View root = findViewById(R.id.vpn_panel_root);
    if (root == null) return;
    vpnPanelRoot = root;
    vpnPower = root.findViewById(R.id.vpn_power);
    vpnSecureIcon = root.findViewById(R.id.vpn_secure_icon);
    vpnStateText = root.findViewById(R.id.vpn_state);
    vpnHostText = root.findViewById(R.id.vpn_location_host);
    vpnSubText = root.findViewById(R.id.vpn_location_sub);
    vpnSecureTitle = root.findViewById(R.id.vpn_secure_title);
    vpnIpText = root.findViewById(R.id.vpn_ip);
    swAuto = root.findViewById(R.id.sw_autoconnect);
    swKill = root.findViewById(R.id.sw_killswitch);
    swTrack = root.findViewById(R.id.sw_blocktrackers);
    vpnPower.setOnClickListener(v -> toggleVpnConnection());
    View.OnClickListener importProfile = v ->
      openVpnProfile.launch(new String[]{"application/x-openvpn-profile", "application/octet-stream", "text/plain"});
    root.findViewById(R.id.vpn_location_row).setOnClickListener(importProfile);
    root.findViewById(R.id.vpn_gear).setOnClickListener(importProfile);
    swAuto.setChecked(prefs.getBoolean("auto_connect", false));
    swKill.setChecked(prefs.getBoolean("kill_switch", false));
    swTrack.setChecked(prefs.getBoolean("block_trackers", false));
    swAuto.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("auto_connect", c).apply());
    swKill.setOnCheckedChangeListener((b, c) -> {
      prefs.edit().putBoolean("kill_switch", c).apply();
      DevxyzVpnService.killSwitch = c;
    });
    swTrack.setOnCheckedChangeListener((b, c) -> {
      prefs.edit().putBoolean("block_trackers", c).apply();
      Toast.makeText(this, c ? "Tracker blocking turns on after you restart the app"
        : "Tracker blocking turns off after you restart the app", Toast.LENGTH_LONG).show();
    });
    refreshVpnPanel();
  }

  private void refreshVpnPanel() {
    if (vpnPanelRoot == null) return;
    boolean connected = DevxyzVpnService.isConnected();
    boolean hasProfile = VpnProfileStore.hasProfile(this);
    vpnStateText.setText(connected ? "Connected" : "Disconnected");
    vpnStateText.setTextColor(getColor(connected ? R.color.green : R.color.muted));
    vpnPower.setBackgroundResource(connected ? R.drawable.vpn_power_bg_on : R.drawable.vpn_power_bg);
    String endpoint = VpnProfileStore.remoteEndpoint(this);
    vpnHostText.setText(hasProfile ? (endpoint.isEmpty() ? "OpenVPN profile" : endpoint) : "No profile");
    vpnSubText.setText(hasProfile ? "Tap to replace profile" : "Tap to import .ovpn");
    vpnSecureTitle.setText(connected ? "Your connection is secure" : "You're not protected");
    vpnSecureIcon.setImageTintList(ColorStateList.valueOf(getColor(connected ? R.color.green : R.color.muted)));
    if (connected) {
      if (!ipLookedUp) {
        ipLookedUp = true;
        vpnIpText.setText("IP: looking up…");
        fetchPublicIp();
      }
    } else {
      ipLookedUp = false;
      vpnIpText.setText("Connect to secure your connection");
    }
  }

  private void toggleVpnConnection() {
    if (DevxyzVpnService.isConnected()) {
      Intent stop = new Intent(this, DevxyzVpnService.class).setAction(DevxyzVpnService.ACTION_DISCONNECT);
      startService(stop);
      return;
    }
    DevxyzVpnService.killSwitch = prefs.getBoolean("kill_switch", false);
    if (!VpnProfileStore.hasProfile(this)) {
      openVpnProfile.launch(new String[]{"application/x-openvpn-profile", "application/octet-stream", "text/plain"});
      return;
    }
    Intent permission = VpnService.prepare(this);
    if (permission != null) vpnPermission.launch(permission);
    else requestVpnCredentials();
  }

  private void fetchPublicIp() {
    new Thread(() -> {
      String ip = null;
      HttpURLConnection conn = null;
      try {
        conn = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        ip = reader.readLine();
        reader.close();
      } catch (Exception ignored) { }
      finally { if (conn != null) conn.disconnect(); }
      final String result = ip;
      runOnUiThread(() -> {
        if (vpnIpText != null) {
          vpnIpText.setText(result == null || result.trim().isEmpty() ? "IP: unavailable" : "IP: " + result.trim());
        }
      });
    }).start();
  }

  // ---------- Extensions panel ----------
  private void initExtPanel() {
    View root = findViewById(R.id.ext_panel_root);
    if (root == null) return;
    extPanelRoot = root;
    extSearch = root.findViewById(R.id.ext_search);
    extInstalledList = root.findViewById(R.id.ext_installed_list);
    extRecommendedList = root.findViewById(R.id.ext_recommended_list);
    extSearch.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void afterTextChanged(Editable s) {
        extFilter = s.toString();
        renderExtensionLists();
      }
    });
    root.findViewById(R.id.ext_close).setOnClickListener(v -> togglePanel(R.id.ext_panel_root));
    root.findViewById(R.id.ext_browse_more).setOnClickListener(v -> newTab("https://addons.mozilla.org/en-US/firefox/"));
  }

  private void refreshExtensionLists() {
    if (extInstalledList == null) return;
    runtime.getWebExtensionController().list().accept(
      list -> {
        installedExts.clear();
        if (list != null) installedExts.addAll(list);
        runOnUiThread(this::renderExtensionLists);
      },
      err -> runOnUiThread(this::renderExtensionLists));
  }

  private String extName(WebExtension ext) {
    if (ext != null && ext.metaData != null && ext.metaData.name != null) return ext.metaData.name;
    return "Extension";
  }

  private void renderExtensionLists() {
    if (extInstalledList == null) return;
    extInstalledList.removeAllViews();
    extRecommendedList.removeAllViews();
    String f = extFilter.trim().toLowerCase(Locale.ROOT);
    boolean anyInstalled = false;
    for (WebExtension ext : installedExts) {
      String name = extName(ext);
      if (!f.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(f)) continue;
      anyInstalled = true;
      extInstalledList.addView(installedExtRow(ext, name));
    }
    if (!anyInstalled) extInstalledList.addView(emptyExtRow("No extensions installed yet."));
    for (RecExt rec : RECOMMENDED) {
      if (!f.isEmpty() && !rec.name.toLowerCase(Locale.ROOT).contains(f)) continue;
      extRecommendedList.addView(recommendedExtRow(rec, recInstalled.contains(rec.slug)));
    }
  }

  private LinearLayout extRowBase(int iconRes, String name, String desc) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    ImageView icon = new ImageView(this);
    icon.setImageResource(iconRes);
    row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    tp.setMargins(dp(12), 0, dp(8), 0);
    TextView nameView = new TextView(this);
    nameView.setText(name);
    nameView.setTextSize(14);
    nameView.setTextColor(getColor(R.color.text));
    nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
    TextView descView = new TextView(this);
    descView.setText(desc);
    descView.setTextSize(12);
    descView.setTextColor(getColor(R.color.muted));
    texts.addView(nameView);
    texts.addView(descView);
    row.addView(texts, tp);
    return row;
  }

  private View installedExtRow(WebExtension ext, String name) {
    LinearLayout row = extRowBase(R.drawable.ic_extension_puzzle, name, "Installed");
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(name)
      .setMessage("Remove this extension from DevxyzBrowser?")
      .setPositiveButton("Remove", (d, w) -> runtime.getWebExtensionController().uninstall(ext).accept(
        done -> {
          for (RecExt rec : RECOMMENDED) {
            if (rec.name.equalsIgnoreCase(name)) recInstalled.remove(rec.slug);
          }
          prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
          runOnUiThread(() -> {
            Toast.makeText(this, "Removed: " + name, Toast.LENGTH_SHORT).show();
            refreshExtensionLists();
          });
        },
        err -> runOnUiThread(() ->
          Toast.makeText(this, "Could not remove: " + err.getMessage(), Toast.LENGTH_LONG).show())))
      .setNegativeButton("Cancel", null).show());
    return row;
  }

  private View recommendedExtRow(RecExt rec, boolean installed) {
    LinearLayout row = extRowBase(rec.icon, rec.name, rec.desc);
    SwitchMaterial toggle = new SwitchMaterial(this);
    toggle.setChecked(installed);
    toggle.setContentDescription(rec.name);
    toggle.setOnCheckedChangeListener((button, checked) -> {
      if (checked == installed) return;
      if (checked) installRecommended(rec);
      else confirmRemoveRecommended(rec);
    });
    row.addView(toggle, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return row;
  }

  private View emptyExtRow(String text) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(12);
    view.setTextColor(getColor(R.color.muted));
    view.setPadding(0, dp(8), 0, dp(8));
    return view;
  }

  private void installRecommended(RecExt rec) {
    String url = "https://addons.mozilla.org/firefox/downloads/latest/" + rec.slug + "/latest.xpi";
    Toast.makeText(this, "Installing " + rec.name + "…", Toast.LENGTH_SHORT).show();
    runtime.getWebExtensionController().install(url).accept(
      ext -> {
        recInstalled.add(rec.slug);
        prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
        runOnUiThread(() -> {
          Toast.makeText(this, "Installed: " + extName(ext), Toast.LENGTH_LONG).show();
          refreshExtensionLists();
        });
      },
      err -> runOnUiThread(() -> {
        Toast.makeText(this, "Install failed: " + (err == null ? "unknown error" : err.getMessage()), Toast.LENGTH_LONG).show();
        renderExtensionLists();
      }));
  }

  private void confirmRemoveRecommended(RecExt rec) {
    WebExtension target = null;
    for (WebExtension ext : installedExts) {
      if (extName(ext).equalsIgnoreCase(rec.name)) { target = ext; break; }
    }
    if (target == null) {
      recInstalled.remove(rec.slug);
      prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
      renderExtensionLists();
      return;
    }
    final WebExtension found = target;
    new AlertDialog.Builder(this).setTitle(rec.name)
      .setMessage("Remove this extension from DevxyzBrowser?")
      .setPositiveButton("Remove", (d, w) -> runtime.getWebExtensionController().uninstall(found).accept(
        done -> {
          recInstalled.remove(rec.slug);
          prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
          runOnUiThread(() -> {
            Toast.makeText(this, "Removed: " + rec.name, Toast.LENGTH_SHORT).show();
            refreshExtensionLists();
          });
        },
        err -> runOnUiThread(() ->
          Toast.makeText(this, "Could not remove: " + err.getMessage(), Toast.LENGTH_LONG).show())))
      .setNegativeButton("Cancel", (d, w) -> renderExtensionLists())
      .show();
  }

  // ---------- Custom quick tiles ----------
  private void loadCustomTiles() {
    tileRow = findViewById(R.id.tileRow);
    customTiles.clear();
    String saved = prefs.getString("custom_tiles", "");
    for (String line : saved.split("\n")) {
      String[] parts = line.split("\\|", 2);
      if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
        Tile t = new Tile();
        t.name = parts[0];
        t.url = parts[1];
        customTiles.add(t);
      }
    }
    renderCustomTiles();
  }

  private void saveCustomTiles() {
    StringBuilder sb = new StringBuilder();
    for (Tile t : customTiles) {
      sb.append(t.name.replace("|", "").replace("\n", "")).append("|")
        .append(t.url.replace("|", "").replace("\n", "")).append("\n");
    }
    prefs.edit().putString("custom_tiles", sb.toString()).apply();
  }

  private void renderCustomTiles() {
    if (tileRow == null) return;
    for (int i = tileRow.getChildCount() - 1; i >= 0; i--) {
      if ("custom".equals(tileRow.getChildAt(i).getTag())) tileRow.removeViewAt(i);
    }
    int iconDp = isDesktop ? 56 : 50;
    int colDp = isDesktop ? 72 : 56;
    for (Tile t : customTiles) {
      final Tile tile = t;
      LinearLayout col = new LinearLayout(this);
      col.setTag("custom");
      col.setOrientation(LinearLayout.VERTICAL);
      col.setGravity(Gravity.CENTER);
      ImageView icon = new ImageView(this);
      icon.setImageResource(R.drawable.ic_globe);
      icon.setBackgroundResource(R.drawable.shortcut_bg);
      int pad = dp(13);
      icon.setPadding(pad, pad, pad, pad);
      col.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
      TextView label = new TextView(this);
      label.setText(tile.name);
      label.setTextSize(10);
      label.setTextColor(getColor(R.color.muted));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(5);
      col.addView(label, lp);
      int insertAt = Math.max(0, tileRow.getChildCount() - 1);
      tileRow.addView(col, insertAt, new LinearLayout.LayoutParams(dp(colDp), LinearLayout.LayoutParams.WRAP_CONTENT));
      icon.setOnClickListener(v -> browse(tile.url));
      col.setOnLongClickListener(v -> {
        new AlertDialog.Builder(this).setTitle(tile.name)
          .setMessage("Remove this shortcut?")
          .setPositiveButton("Remove", (d, w) -> {
            customTiles.remove(tile);
            saveCustomTiles();
            renderCustomTiles();
          })
          .setNegativeButton("Cancel", null).show();
        return true;
      });
    }
  }

  private void showAddSite() {
    LinearLayout fields = new LinearLayout(this);
    fields.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(20);
    fields.setPadding(pad, 0, pad, 0);
    EditText name = new EditText(this);
    name.setSingleLine(true);
    name.setHint("Name");
    EditText site = new EditText(this);
    site.setSingleLine(true);
    site.setHint("https://example.com");
    fields.addView(name);
    fields.addView(site);
    new AlertDialog.Builder(this).setTitle("Add site").setView(fields)
      .setPositiveButton("Add", (d, w) -> {
        String url = site.getText().toString().trim();
        if (url.isEmpty()) return;
        String label = name.getText().toString().trim();
        if (label.isEmpty()) {
          try {
            label = Uri.parse(url).getHost();
          } catch (Exception e) { label = null; }
          if (label == null || label.isEmpty()) label = url;
        }
        Tile t = new Tile();
        t.name = label;
        t.url = url;
        customTiles.add(t);
        saveCustomTiles();
        renderCustomTiles();
        browse(url);
      })
      .setNegativeButton("Cancel", null).show();
  }

  // ---------- Profile ----------
  private void showProfile() {
    String vpn = DevxyzVpnService.isConnected() ? "Connected" : "Disconnected";
    String message = "Tabs open: " + tabs.size()
      + "\nBookmarks: " + bookmarks.size()
      + "\nHistory entries: " + historyItems.size()
      + "\nCustom sites: " + customTiles.size()
      + "\nVPN: " + vpn;
    new AlertDialog.Builder(this).setTitle("Local profile")
      .setMessage(message).setPositiveButton("OK", null).show();
  }

  private void showHome() {
    if (activeTab != null) {
      activeTab.url = "";
      updateTabLabel(activeTab);
    }
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
    if (isDesktop) {
      ImageView fox = new ImageView(this);
      fox.setImageResource(R.drawable.ic_fox);
      LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(18), dp(18));
      fp.setMargins(dp(10), 0, 0, 0);
      chip.addView(fox, fp);
      tab.label = new TextView(this);
      tab.label.setTextSize(13);
      tab.label.setTextColor(getColor(R.color.text));
      tab.label.setSingleLine(true);
      tab.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(dp(8), 0, 0, 0);
      chip.addView(tab.label, lp);
    } else {
      tab.label = new TextView(this);
      tab.label.setTextSize(13);
      tab.label.setTextColor(getColor(R.color.text));
      tab.label.setGravity(Gravity.CENTER_VERTICAL);
      int pad = dp(13);
      tab.label.setPadding(pad, 0, 4, 0);
      chip.addView(tab.label, new LinearLayout.LayoutParams(0, dp(38), 1));
    }
    TextView close = new TextView(this);
    close.setText("×");
    close.setTextSize(18);
    close.setTextColor(getColor(R.color.muted));
    close.setGravity(Gravity.CENTER);
    close.setContentDescription("Close tab");
    chip.addView(close, new LinearLayout.LayoutParams(dp(isDesktop ? 32 : 34), dp(isDesktop ? 40 : 38)));
    close.setOnClickListener(v -> closeTab(tab));
    updateTabLabel(tab);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, dp(isDesktop ? 40 : 38));
    lp.setMargins(dp(3), 0, dp(5), 0);
    tabStrip.addView(chip, lp);
    chip.setOnClickListener(v -> selectTab(tab));
  }

  private void updateTabLabel(BrowserTab tab) {
    if (tab.chip == null) return;
    String title = tab.url.isEmpty() ? "New Tab" : Uri.parse(tab.url).getHost();
    if (title == null || title.isEmpty()) title = "Page";
    if (isDesktop) {
      tab.label.setText(title);
      tab.label.setTypeface(tab.label.getTypeface(), tab == activeTab
        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      GradientDrawable bg = new GradientDrawable();
      if (tab == activeTab) {
        bg.setColor(getColor(R.color.panel2));
        bg.setStroke(dp(1), getColor(R.color.purple));
      } else {
        bg.setColor(0x00000000);
      }
      bg.setCornerRadius(dp(10));
      tab.chip.setBackground(bg);
    } else {
      tab.label.setText((tab == activeTab ? "●  " : "◉  ") + title);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(getColor(R.color.panel2));
      bg.setCornerRadius(dp(14));
      bg.setStroke(dp(1), getColor(R.color.stroke));
      tab.chip.setBackground(bg);
    }
    tab.chip.setContentDescription(title + ". Tap to switch to this tab.");
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
    if (q.isEmpty()) return;
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
    String current = address.getText().toString().trim();
    AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Bookmarks");
    if (bookmarks.isEmpty()) b.setMessage("No saved pages yet.");
    else b.setItems(bookmarks.toArray(new String[0]), (d, which) -> browse(bookmarks.get(which)));
    if (current.startsWith("http")) b.setPositiveButton("Save current", (d, w) -> { if (!bookmarks.contains(current)) bookmarks.add(0, current); });
    b.setNegativeButton("Close", null).show();
  }

  private void showList(String title, ArrayList<String> items) {
    if (items.isEmpty()) { showInfo(title, "Nothing here yet."); return; }
    new AlertDialog.Builder(this).setTitle(title).setItems(items.toArray(new String[0]), (d, w) -> browse(items.get(w))).setNegativeButton("Close", null).show();
  }

  private void showExtensions() {
    if (isDesktop) {
      togglePanel(R.id.ext_panel_root);
      setSidebarActive(R.id.extensions);
      return;
    }
    final EditText xpi = new EditText(this);
    xpi.setHint("Mozilla-signed .xpi URL");
    new AlertDialog.Builder(this).setTitle("Extensions")
      .setMessage("Install a Mozilla-signed WebExtension. Installed add-ons persist across restarts.")
      .setView(xpi).setPositiveButton("Install", (d, w) -> {
        String uri = xpi.getText().toString().trim();
        if (uri.startsWith("https://")) runtime.getWebExtensionController().install(uri)
          .accept(ext -> runOnUiThread(() -> Toast.makeText(this, "Installed: " + extName(ext), Toast.LENGTH_LONG).show()),
                  err -> runOnUiThread(() -> Toast.makeText(this, "Install failed: " + err.getMessage(), Toast.LENGTH_LONG).show()));
        else Toast.makeText(this, "Use a secure HTTPS .xpi URL", Toast.LENGTH_LONG).show();
      }).setNeutralButton("Manage", (d, w) -> runtime.getWebExtensionController().list()
        .accept(list -> runOnUiThread(() -> {
          if (list == null || list.isEmpty()) {
            Toast.makeText(this, "No extensions installed", Toast.LENGTH_SHORT).show();
            return;
          }
          String[] names = new String[list.size()];
          for (int i = 0; i < list.size(); i++) names[i] = extName(list.get(i));
          new AlertDialog.Builder(this).setTitle("Installed extensions").setItems(names, null).setPositiveButton("Done", null).show();
        }), err -> runOnUiThread(() -> Toast.makeText(this, "Unable to list extensions", Toast.LENGTH_LONG).show())))
      .setNegativeButton("Cancel", null).show();
  }

  private void showVpn() {
    if (isDesktop) {
      togglePanel(R.id.vpn_panel_root);
      setSidebarActive(R.id.vpn);
      return;
    }
    AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("OpenVPN");
    if (!VpnProfileStore.hasProfile(this)) {
      b.setMessage("Import an authorized .ovpn profile. DevxyzBrowser validates and stores it privately on this device.")
       .setPositiveButton("Import .ovpn", (d, w) -> openVpnProfile.launch(new String[]{"application/x-openvpn-profile", "application/octet-stream", "text/plain"}));
    } else if (DevxyzVpnService.isConnected()) {
      b.setMessage("Connected through OpenVPN.")
       .setPositiveButton("Disconnect", (d, w) -> {
         Intent stop = new Intent(this, DevxyzVpnService.class).setAction(DevxyzVpnService.ACTION_DISCONNECT);
         startService(stop); setVpnStatus("Disconnecting • stopping OpenVPN");
       });
    } else {
      b.setMessage("Connect with the imported OpenVPN profile. DevxyzBrowser will show Connected only after the OpenVPN core reports a connected tunnel.")
       .setPositiveButton("Connect", (d, w) -> {
         Intent permission = VpnService.prepare(this);
         if (permission != null) vpnPermission.launch(permission);
         else requestVpnCredentials();
       })
       .setNeutralButton("Replace profile", (d, w) -> openVpnProfile.launch(new String[]{"application/x-openvpn-profile", "application/octet-stream", "text/plain"}));
    }
    b.setNegativeButton("Close", null).show();
  }

  private void showInfo(String title, String message) {
    new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
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
    int pad = dp(20);
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
      setVpnStatus("Connecting • starting OpenVPN");
    } catch (Exception e) {
      Toast.makeText(this, "Could not start VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
    refreshVpnPanel();
  }

  private String safe(String value) { return value == null || value.trim().isEmpty() ? "OpenVPN" : value; }

  @Override protected void onDestroy() {
    if (vpnReceiverRegistered) { unregisterReceiver(vpnStateReceiver); vpnReceiverRegistered = false; }
    for (BrowserTab tab : tabs) tab.session.close();
    tabs.clear();
    super.onDestroy();
  }
}
