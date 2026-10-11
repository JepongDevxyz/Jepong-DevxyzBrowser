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
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.jepongdevxyz.browser.util.ImgUtil;
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
    final boolean searchOnly;
    RecExt(String name, String desc, String slug, int icon) {
      this(name, desc, slug, icon, false);
    }
    RecExt(String name, String desc, String slug, int icon, boolean searchOnly) {
      this.name = name; this.desc = desc; this.slug = slug; this.icon = icon;
      this.searchOnly = searchOnly;
    }
  }

  private static final RecExt[] RECOMMENDED = {
    new RecExt("uBlock Origin", "Block ads and trackers", "ublock-origin", R.drawable.ic_ext_ublock),
    new RecExt("Dark Reader", "Dark mode for all websites", "darkreader", R.drawable.ic_ext_darkreader),
    new RecExt("Grammarly", "Write better everywhere", "grammarly", R.drawable.ic_ext_grammarly, true),
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
        VpnProfileStore.importProfile(this, uri);
        Toast.makeText(this, "OpenVPN profile imported", Toast.LENGTH_LONG).show();
      } catch (Exception ex) {
        Toast.makeText(this, "Profile rejected: " + ex.getMessage(), Toast.LENGTH_LONG).show();
      }
      refreshVpnPanel();
    });
    if (runtime == null) {
      GeckoRuntimeSettings.Builder rsb = new GeckoRuntimeSettings.Builder();
      // Firefox-style Enhanced Tracking Protection: 0=Off, 1=Standard (default), 2=Strict.
      // Migrates the legacy block_trackers boolean (true -> Strict).
      int etp = prefs.getInt("etp_level", -1);
      if (etp < 0) {
        etp = prefs.getBoolean("block_trackers", false) ? 2 : 1;
        prefs.edit().putInt("etp_level", etp).apply();
      }
      if (etp == 1) {
        rsb.contentBlocking(new ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            .build());
      } else if (etp == 2) {
        rsb.contentBlocking(new ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .build());
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
    ImgUtil.setB64((ImageView) findViewById(R.id.home_bg), this, "img/bg_home.b64");
    tabStrip = findViewById(R.id.tabStrip);
    menuButton = findViewById(R.id.menu);
    addTabChip(activeTab);
    attachSession(activeTab);
    selectTab(activeTab);
    // Firefox-style: restore tabs from the previous session (default ON).
    if (prefs.getBoolean("restore_tabs", true)) {
      String saved = prefs.getString("saved_tabs", "");
      if (!saved.isEmpty()) {
        String[] urls = saved.split("\n");
        if (urls.length > 0 && !urls[0].isEmpty()) {
          newTab(); load(urls[0]);
          for (int i = 1; i < urls.length; i++) {
            if (!urls[i].isEmpty()) { newTab(); load(urls[i]); }
          }
          if (tabs.size() > 1) selectTab(tabs.get(0));
        }
      }
    }
    IntentFilter vpnFilter = new IntentFilter(DevxyzVpnService.ACTION_STATE);
    ContextCompat.registerReceiver(this, vpnStateReceiver, vpnFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    vpnReceiverRegistered = true;

    // Chrome-style: omnibox + tab count + menu. All actions live in the menu.
    onClick(R.id.menu, v -> showChromeMenu(v));
    onClick(R.id.tabCount, v -> showTabs());
    ImgUtil.setB64((ImageView) findViewById(R.id.ntpLogo), this, "img/fox.b64");
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
    initVpnPanel(findViewById(R.id.vpn_panel_root));
    initExtPanel(findViewById(R.id.ext_panel_root), () -> togglePanel(R.id.ext_panel_root));
    loadCustomTiles();
    loadDownloads();
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
  private BottomSheetDialog vpnDialog, extDialog;

  /** Wraps an inflated panel view in a dark bottom sheet on phones. */
  private BottomSheetDialog makeSheet(View root) {
    BottomSheetDialog dialog = new BottomSheetDialog(this);
    dialog.setContentView(root);
    View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
    if (sheet != null) sheet.setBackgroundResource(R.drawable.bottomsheet_bg);
    return dialog;
  }

  private void initVpnPanel(View root) {
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
    swTrack.setChecked(prefs.getInt("etp_level", 1) == 2);
    swAuto.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("auto_connect", c).apply());
    swKill.setOnCheckedChangeListener((b, c) -> {
      prefs.edit().putBoolean("kill_switch", c).apply();
      DevxyzVpnService.killSwitch = c;
    });
    swTrack.setOnCheckedChangeListener((b, c) -> {
      prefs.edit().putInt("etp_level", c ? 2 : 1).apply();
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
  private void initExtPanel(View root, Runnable onClose) {
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
    extSearch.setOnTouchListener((v, event) -> {
      if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
        android.graphics.drawable.Drawable[] ds = extSearch.getCompoundDrawablesRelative();
        if (ds[2] != null) {
          int x = (int) event.getX();
          int w = extSearch.getWidth() - extSearch.getPaddingEnd() - ds[2].getIntrinsicWidth();
          if (x >= w) {
            showExtSortMenu();
            return true;
          }
        }
      }
      return false;
    });
    root.findViewById(R.id.ext_close).setOnClickListener(v -> onClose.run());
    root.findViewById(R.id.ext_browse_more).setOnClickListener(v -> newTab("https://addons.mozilla.org/en-US/firefox/"));
  }

  private void showExtSortMenu() {
    android.widget.PopupMenu menu = new android.widget.PopupMenu(this, extSearch);
    boolean az = prefs.getBoolean("ext_sort_az", false);
    menu.getMenu().add(0, 1, 0, (az ? "✓ " : "") + "Default order");
    menu.getMenu().add(0, 2, 1, (!az ? "✓ " : "") + "Name A–Z");
    menu.setOnMenuItemClickListener(item -> {
      prefs.edit().putBoolean("ext_sort_az", item.getItemId() == 2).apply();
      renderExtensionLists();
      return true;
    });
    menu.show();
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
    if (!anyInstalled && !f.isEmpty()) extInstalledList.addView(emptyExtRow("No extensions match your search."));
    java.util.List<RecExt> recs = new java.util.ArrayList<>(java.util.Arrays.asList(RECOMMENDED));
    if (prefs.getBoolean("ext_sort_az", false)) {
      java.util.Collections.sort(recs, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }
    for (RecExt rec : recs) {
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
    toggle.setChecked(installed && !rec.searchOnly);
    toggle.setContentDescription(rec.name);
    if (rec.searchOnly) {
      android.view.View.OnClickListener openSearch = v -> {
        newTab("https://addons.mozilla.org/en-US/firefox/search/?q=" + rec.slug);
        Toast.makeText(this, "Find " + rec.name + " on addons.mozilla.org", Toast.LENGTH_LONG).show();
      };
      toggle.setOnCheckedChangeListener((button, checked) -> {
        if (checked) {
          openSearch.onClick(button);
          button.setChecked(false);
        }
      });
      row.setClickable(true);
      row.setFocusable(true);
      row.setOnClickListener(openSearch);
      return row;
    }
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
    int iconDp = isDesktop ? 56 : 48;
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
      LinearLayout.LayoutParams tlp = isDesktop
          ? new LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
          : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      tileRow.addView(col, insertAt, tlp);
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
    if ("Blank page".equals(prefs.getString("homepage", "Devxyz Home"))) {
      startPage.setVisibility(View.GONE);
      session.loadUri("about:blank");
    } else {
      startPage.setVisibility(View.VISIBLE);
    }
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

  private void newTab(String url) {
    newTab();
    load(url);
  }

  private void attachSession(BrowserTab tab) {
    tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
      @Override public void onPageStart(GeckoSession s, String url) {
        tab.url = url;
        if (tab == activeTab) {
          address.setText(url); progress.setVisibility(View.VISIBLE); progress.setProgress(5);
        }
        updateTabLabel(tab);
        if (prefs.getBoolean("remember_history", true) && !historyItems.contains(url)) historyItems.add(0, url);
      }
      @Override public void onPageStop(GeckoSession s, boolean ok) {
        if (tab == activeTab) {
          progress.setProgress(100);
          progress.postDelayed(() -> { progress.setProgress(0); progress.setVisibility(View.GONE); }, 350);
        }
      }
    });
    tab.session.setContentDelegate(new GeckoSession.ContentDelegate() {
      @Override public void onExternalResponse(GeckoSession s, GeckoSession.WebResponseInfo response) {
        String url = response.uri;
        String name = null;
        try {
          String disp = response.headers != null ? response.headers.get("Content-Disposition") : null;
          if (disp != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename=\"?([^\";]+)\"?").matcher(disp);
            if (m.find()) name = m.group(1);
          }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
          try { name = Uri.parse(url).getLastPathSegment(); } catch (Exception ignored) {}
        }
        if (name == null || name.isEmpty()) name = "download";
        startDownload(url, name);
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
    if (!isDesktop) {
      // Chrome-style mobile: no tab strip chips, just the count badge.
      updateTabCount();
      return;
    }
    LinearLayout chip = new LinearLayout(this);
    chip.setGravity(Gravity.CENTER_VERTICAL);
    chip.setOrientation(LinearLayout.HORIZONTAL);
    tab.chip = chip;
    if (isDesktop) {
      ImageView fox = new ImageView(this);
      fox.setImageResource(R.drawable.devxyz_launcher_foreground);
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
    if (tab.chip != null && tabStrip != null) tabStrip.removeView(tab.chip);
    tab.session.close();
    if (tabs.isEmpty()) newTab();
    else if (activeTab == tab) selectTab(tabs.get(Math.max(0, index - 1)));
    updateTabCount();
  }

  private void updateTabCount() {
    if (isDesktop) return;
    TextView tc = findViewById(R.id.tabCount);
    if (tc != null) tc.setText(String.valueOf(tabs.size()));
    refreshTabsSheet();
  }

  private BottomSheetDialog tabsDialog;

  private void showTabs() {
    if (isDesktop) return;
    if (tabsDialog == null) {
      View root = getLayoutInflater().inflate(R.layout.panel_tabs, null);
      tabsDialog = makeSheet(root);
      root.findViewById(R.id.tabs_close).setOnClickListener(v -> tabsDialog.dismiss());
      root.findViewById(R.id.tabs_new).setOnClickListener(v -> { newTab(); tabsDialog.dismiss(); });
    }
    refreshTabsSheet();
    tabsDialog.show();
  }

  private void refreshTabsSheet() {
    if (tabsDialog == null) return;
    LinearLayout list = tabsDialog.findViewById(R.id.tabs_list);
    if (list == null) return;
    list.removeAllViews();
    for (BrowserTab tab : new ArrayList<>(tabs)) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(10), dp(12), dp(10));
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(getColor(tab == activeTab ? R.color.panel2 : 0x00000000));
      bg.setCornerRadius(dp(12));
      if (tab == activeTab) bg.setStroke(dp(1), getColor(R.color.purple));
      row.setBackground(bg);

      TextView label = new TextView(this);
      String title = tab.url.isEmpty() ? "New Tab" : Uri.parse(tab.url).getHost();
      if (title == null || title.isEmpty()) title = "Page";
      label.setText(title);
      label.setTextSize(14);
      label.setTextColor(getColor(R.color.text));
      label.setSingleLine(true);
      label.setEllipsize(android.text.TextUtils.TruncateAt.END);
      row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

      TextView close = new TextView(this);
      close.setText("×");
      close.setTextSize(20);
      close.setTextColor(getColor(R.color.muted));
      close.setGravity(Gravity.CENTER);
      close.setPadding(dp(12), 0, dp(4), 0);
      close.setContentDescription("Close tab");
      close.setOnClickListener(v -> { closeTab(tab); });
      row.addView(close);

      row.setOnClickListener(v -> { selectTab(tab); tabsDialog.dismiss(); });
      list.addView(row);
    }
  }

  private void showChromeMenu(View anchor) {
    // Chrome-style popup menu with icons, anchored top-right like Chrome.
    final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
    View root = getLayoutInflater().inflate(R.layout.panel_menu, null);
    dialog.setContentView(root);
    android.view.Window w = dialog.getWindow();
    if (w != null) {
      w.setGravity(Gravity.TOP | Gravity.END);
      android.view.WindowManager.LayoutParams lp = w.getAttributes();
      lp.y = dp(64); lp.x = dp(8);
      w.setAttributes(lp);
      w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
    }
    dialog.setCanceledOnTouchOutside(true);

    // Top circular action row
    root.findViewById(R.id.m_back).setOnClickListener(v -> { dialog.dismiss(); session.goBack(); });
    root.findViewById(R.id.m_forward).setOnClickListener(v -> { dialog.dismiss(); session.goForward(); });
    root.findViewById(R.id.m_bookmark).setOnClickListener(v -> { dialog.dismiss(); bookmarkCurrent(); });
    root.findViewById(R.id.m_download).setOnClickListener(v -> { dialog.dismiss(); showDownloads(); });
    root.findViewById(R.id.m_refresh).setOnClickListener(v -> { dialog.dismiss(); session.reload(); });

    // Menu items
    root.findViewById(R.id.m_newtab).setOnClickListener(v -> { dialog.dismiss(); newTab(); });
    root.findViewById(R.id.m_history).setOnClickListener(v -> { dialog.dismiss(); showList("History", historyItems); });
    root.findViewById(R.id.m_cleardata).setOnClickListener(v -> { dialog.dismiss(); confirmClearData(); });
    root.findViewById(R.id.m_downloads).setOnClickListener(v -> { dialog.dismiss(); showDownloads(); });
    root.findViewById(R.id.m_bookmarks).setOnClickListener(v -> { dialog.dismiss(); showBookmarks(); });
    root.findViewById(R.id.m_extensions).setOnClickListener(v -> { dialog.dismiss(); showExtensions(); });
    root.findViewById(R.id.m_vpn).setOnClickListener(v -> { dialog.dismiss(); showVpn(); });
    root.findViewById(R.id.m_settings).setOnClickListener(v -> { dialog.dismiss(); showSettings(); });
    root.findViewById(R.id.m_about).setOnClickListener(v -> { dialog.dismiss(); showInfo("DevxyzBrowser",
        "DevxyzBrowser • GeckoView 156\nFast. Private. Flexible.\nFirefox-compatible settings, built-in VPN and extensions."); });

    dialog.show();
    // Size the dialog to wrap the menu card
    if (w != null) {
      w.setLayout(dp(300), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }
  }

  private void bookmarkCurrent() {
    String current = address.getText().toString().trim();
    if (current.startsWith("http") && !bookmarks.contains(current)) {
      bookmarks.add(0, current);
      Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show();
    } else if (current.startsWith("http")) {
      Toast.makeText(this, "Already bookmarked", Toast.LENGTH_SHORT).show();
    } else {
      Toast.makeText(this, "Open a page first to bookmark it", Toast.LENGTH_SHORT).show();
    }
  }

  private void confirmClearData() {
    new AlertDialog.Builder(this).setTitle("Delete browsing data")
        .setMessage("Clear browsing history?")
        .setPositiveButton("Delete", (d, which) -> {
          historyItems.clear();
          Toast.makeText(this, "Browsing data deleted", Toast.LENGTH_SHORT).show();
        })
        .setNegativeButton("Cancel", null).show();
  }

  // ---- Download manager ----
  private static final class DownloadItem {
    String name, url, time; long id;
    DownloadItem(String name, String url, String time, long id) {
      this.name = name; this.url = url; this.time = time; this.id = id;
    }
  }
  private final ArrayList<DownloadItem> downloads = new ArrayList<>();
  private BottomSheetDialog downloadsDialog;

  private void loadDownloads() {
    downloads.clear();
    String saved = prefs.getString("downloads", "");
    if (!saved.isEmpty()) {
      for (String line : saved.split("\n")) {
        String[] p = line.split("\t", -1);
        if (p.length == 4) {
          try { downloads.add(new DownloadItem(p[0], p[1], p[2], Long.parseLong(p[3]))); }
          catch (Exception ignored) {}
        }
      }
    }
  }

  private void saveDownloads() {
    StringBuilder sb = new StringBuilder();
    for (DownloadItem d : downloads) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(d.name.replace("\t", " ")).append('\t').append(d.url).append('\t').append(d.time).append('\t').append(d.id);
    }
    prefs.edit().putString("downloads", sb.toString()).apply();
  }

  private void startDownload(String url, String filename) {
    runOnUiThread(() -> {
      try {
        // Storage permission needed only on Android 8-9 (API 26-28).
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          pendingDownloadUrl = url; pendingDownloadName = filename;
          requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9001);
          return;
        }
        enqueueDownload(url, filename);
      } catch (Exception e) {
        Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
  }

  private String pendingDownloadUrl, pendingDownloadName;

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    super.onRequestPermissionsResult(code, perms, results);
    if (code == 9001 && results.length > 0 &&
        results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        pendingDownloadUrl != null) {
      enqueueDownload(pendingDownloadUrl, pendingDownloadName);
      pendingDownloadUrl = null;
    }
  }

  private void enqueueDownload(String url, String filename) {
    try {
      android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
      android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
      req.setTitle(filename);
      req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename);
      req.allowScanningByMediaScanner();
      long id = dm.enqueue(req);
      String time = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
          .format(new java.util.Date());
      downloads.add(0, new DownloadItem(filename, url, time, id));
      saveDownloads();
      Toast.makeText(this, "Downloading " + filename, Toast.LENGTH_SHORT).show();
      refreshDownloadsSheet();
    } catch (Exception e) {
      Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void showDownloads() {
    if (isDesktop) { showInfo("Downloads", downloads.isEmpty() ? "No downloads yet." : downloads.size() + " file(s) downloaded."); return; }
    loadDownloads();
    if (downloadsDialog == null) {
      View root = getLayoutInflater().inflate(R.layout.panel_downloads, null);
      downloadsDialog = makeSheet(root);
      root.findViewById(R.id.dl_close).setOnClickListener(v -> downloadsDialog.dismiss());
    }
    refreshDownloadsSheet();
    downloadsDialog.show();
  }

  private void refreshDownloadsSheet() {
    if (downloadsDialog == null) return;
    LinearLayout list = downloadsDialog.findViewById(R.id.dl_list);
    TextView empty = downloadsDialog.findViewById(R.id.dl_empty);
    if (list == null) return;
    list.removeAllViews();
    empty.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);
    android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
    for (DownloadItem d : new ArrayList<>(downloads)) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(4), dp(10), dp(4), dp(10));

      ImageView icon = new ImageView(this);
      icon.setImageResource(R.drawable.ic_nav_download);
      icon.setColorFilter(getColor(R.color.muted));
      row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

      LinearLayout texts = new LinearLayout(this);
      texts.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
      tp.setMargins(dp(12), 0, dp(8), 0);
      row.addView(texts, tp);

      TextView name = new TextView(this);
      name.setText(d.name); name.setTextSize(14); name.setTextColor(getColor(R.color.text));
      name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      texts.addView(name);

      TextView sub = new TextView(this);
      sub.setText(downloadStatus(dm, d.id) + " • " + d.time);
      sub.setTextSize(12); sub.setTextColor(getColor(R.color.muted));
      texts.addView(sub);

      list.addView(row);
    }
  }

  private String downloadStatus(android.app.DownloadManager dm, long id) {
    try (android.database.Cursor c = dm.query(new android.app.DownloadManager.Query().setFilterById(id))) {
      if (c != null && c.moveToFirst()) {
        int st = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
        switch (st) {
          case android.app.DownloadManager.STATUS_SUCCESSFUL: return "Completed";
          case android.app.DownloadManager.STATUS_FAILED: return "Failed";
          case android.app.DownloadManager.STATUS_RUNNING: return "Downloading";
          case android.app.DownloadManager.STATUS_PAUSED: return "Paused";
          default: return "Pending";
        }
      }
    } catch (Exception ignored) {}
    return "Unknown";
  }

  private BottomSheetDialog settingsDialog;

  private void showSettings() {
    if (isDesktop) {
      showInfo("Settings", "DevxyzBrowser • GeckoView 156\nFirefox-compatible settings are available on the mobile layout.");
      return;
    }
    if (settingsDialog == null) {
      View root = getLayoutInflater().inflate(R.layout.panel_settings, null);
      settingsDialog = makeSheet(root);
      initSettingsPanel(root);
    } else {
      refreshSettingsPanel();
    }
    settingsDialog.show();
  }

  private void initSettingsPanel(View root) {
    root.findViewById(R.id.settings_close).setOnClickListener(v -> settingsDialog.dismiss());
    com.google.android.material.switchmaterial.SwitchMaterial swRestore = root.findViewById(R.id.sw_restore_tabs);
    com.google.android.material.switchmaterial.SwitchMaterial swHist = root.findViewById(R.id.sw_remember_history);
    com.google.android.material.switchmaterial.SwitchMaterial swClear = root.findViewById(R.id.sw_clear_on_quit);
    swRestore.setChecked(prefs.getBoolean("restore_tabs", true));
    swHist.setChecked(prefs.getBoolean("remember_history", true));
    swClear.setChecked(prefs.getBoolean("clear_on_quit", false));
    swRestore.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("restore_tabs", c).apply());
    swHist.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("remember_history", c).apply());
    swClear.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("clear_on_quit", c).apply());
    root.findViewById(R.id.set_search_engine_row).setOnClickListener(v -> pickOption(
        "Default search engine",
        new String[]{"Google", "Bing", "DuckDuckGo"},
        prefs.getString("search_engine", "Google"),
        val -> { prefs.edit().putString("search_engine", val).apply(); refreshSettingsPanel(); }));
    root.findViewById(R.id.set_homepage_row).setOnClickListener(v -> pickOption(
        "Homepage",
        new String[]{"Devxyz Home", "Blank page"},
        prefs.getString("homepage", "Devxyz Home"),
        val -> { prefs.edit().putString("homepage", val).apply(); refreshSettingsPanel(); }));
    root.findViewById(R.id.set_etp_row).setOnClickListener(v -> {
      String[] labels = {"Off", "Standard", "Strict"};
      int cur = prefs.getInt("etp_level", 1);
      pickOption("Enhanced Tracking Protection", labels, labels[cur], val -> {
        int level = val.equals("Strict") ? 2 : val.equals("Off") ? 0 : 1;
        prefs.edit().putInt("etp_level", level).apply();
        refreshSettingsPanel();
        Toast.makeText(this, "Applies after you restart the app", Toast.LENGTH_SHORT).show();
      });
    });
    refreshSettingsPanel();
  }

  private void refreshSettingsPanel() {
    if (settingsDialog == null) return;
    TextView se = settingsDialog.findViewById(R.id.set_search_engine_val);
    TextView hp = settingsDialog.findViewById(R.id.set_homepage_val);
    TextView etp = settingsDialog.findViewById(R.id.set_etp_val);
    if (se != null) se.setText(prefs.getString("search_engine", "Google"));
    if (hp != null) hp.setText(prefs.getString("homepage", "Devxyz Home"));
    if (etp != null) {
      String[] labels = {"Off", "Standard", "Strict"};
      etp.setText(labels[prefs.getInt("etp_level", 1)]);
    }
  }

  private void pickOption(String title, String[] options, String current, java.util.function.Consumer<String> onPick) {
    int checked = 0;
    for (int i = 0; i < options.length; i++) if (options[i].equals(current)) checked = i;
    new AlertDialog.Builder(this).setTitle(title)
        .setSingleChoiceItems(options, checked, (d, which) -> {
          onPick.accept(options[which]);
          d.dismiss();
        })
        .setNegativeButton("Cancel", null).show();
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
      else q = searchUrl(prefs.getString("search_engine", "Google")) + Uri.encode(q);
    }
    session.loadUri(q);
  }

  private String searchUrl(String engine) {
    if ("Bing".equals(engine)) return "https://www.bing.com/search?q=";
    if ("DuckDuckGo".equals(engine)) return "https://duckduckgo.com/?q=";
    return "https://www.google.com/search?q=";
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
    if (extDialog == null) {
      View root = getLayoutInflater().inflate(R.layout.panel_extensions, null);
      extDialog = makeSheet(root);
      initExtPanel(root, extDialog::dismiss);
    }
    refreshExtensionLists();
    extDialog.show();
  }

  private void showVpn() {
    if (isDesktop) {
      togglePanel(R.id.vpn_panel_root);
      setSidebarActive(R.id.vpn);
      return;
    }
    if (vpnDialog == null) {
      View root = getLayoutInflater().inflate(R.layout.panel_vpn, null);
      vpnDialog = makeSheet(root);
      initVpnPanel(root);
    } else {
      refreshVpnPanel();
    }
    vpnDialog.show();
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
    } catch (Exception e) {
      Toast.makeText(this, "Could not start VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
    refreshVpnPanel();
  }

  private String safe(String value) { return value == null || value.trim().isEmpty() ? "OpenVPN" : value; }

  @Override protected void onPause() {
    super.onPause();
    // Firefox-style: remember open tabs so they can be restored on startup.
    StringBuilder sb = new StringBuilder();
    for (BrowserTab tab : tabs) {
      if (tab.url != null && !tab.url.isEmpty() && !tab.url.startsWith("about:")) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(tab.url);
      }
    }
    prefs.edit().putString("saved_tabs", sb.toString()).apply();
  }

  @Override protected void onDestroy() {
    if (vpnReceiverRegistered) { unregisterReceiver(vpnStateReceiver); vpnReceiverRegistered = false; }
    if (prefs.getBoolean("clear_on_quit", false)) {
      historyItems.clear();
      prefs.edit().remove("saved_tabs").apply();
    }
    for (BrowserTab tab : tabs) tab.session.close();
    tabs.clear();
    super.onDestroy();
  }
}
