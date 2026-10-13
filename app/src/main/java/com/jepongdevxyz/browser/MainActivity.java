package com.jepongdevxyz.browser;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Gravity;
import android.graphics.Color;
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
import com.jepongdevxyz.browser.util.Shields;
import com.jepongdevxyz.browser.util.Appearance;
import com.jepongdevxyz.browser.util.TvUtil;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import com.jepongdevxyz.browser.vpn.VpnGateApi;
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
  static GeckoRuntime runtime;
  private GeckoSession session;
  EditText address, heroSearch;
  ProgressBar progress;
  private View startPage;
  private View menuButton;
  private LinearLayout tabStrip;
  private org.mozilla.geckoview.GeckoView geckoView;
  private ActivityResultLauncher<String[]> openVpnProfile;
  private ActivityResultLauncher<Intent> vpnPermission;
  ActivityResultLauncher<Intent> safDownloadPicker;
  ActivityResultLauncher<Intent> voiceSearchLauncher;
  android.content.BroadcastReceiver downloadCompleteReceiver;
  private boolean vpnReceiverRegistered;
  SharedPreferences prefs;
  final SettingsScreens settingsScreens = new SettingsScreens(this);
  private boolean isDesktop;

  // Desktop right-panel widgets (null on phones).
  private View vpnPanelRoot, extPanelRoot;
  private ImageButton vpnPower;
  private ImageView vpnSecureIcon;
  private TextView vpnStateText, vpnHostText, vpnSubText, vpnSecureTitle, vpnIpText, vpnStatsText;
  private final java.util.List<VpnGateApi.VpnServer> vpnServers = new java.util.ArrayList<>();
  private String vpnServerFilter = "";
  private final java.util.Set<String> vpnFavorites = new java.util.HashSet<>();
  private final android.os.Handler vpnStatsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private Runnable vpnStatsTick;
  private long vpnRxBase, vpnTxBase;
  private SwitchMaterial swAuto, swKill, swTrack;
  private EditText extSearch;
  private LinearLayout extInstalledList, extRecommendedList, extSearchResults;
  private final java.util.List<AmoExt> amoResults = new java.util.ArrayList<>();
  private String amoQuery = "";
  private final android.os.Handler amoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private Runnable amoPending;

  /** One extension from the addons.mozilla.org public search API. */
  private static final class AmoExt {
    String name = "", slug = "", author = "", version = "", iconUrl = "", fileUrl = "", summary = "";
    long users;
    double rating;
  }
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
      if (error) Ui.toastLong(MainActivity.this, safe(detail));
      refreshVpnPanel();
    }
  };
  final ArrayList<String> historyItems = new ArrayList<>();
  final ArrayList<String> bookmarks = new ArrayList<>();
  final ArrayList<BrowserTab> tabs = new ArrayList<>();
  BrowserTab activeTab;

  static final class BrowserTab {
    final GeckoSession session;
    String url = "";
    LinearLayout chip;
    TextView label;
    boolean incognito = false;
    BrowserTab(GeckoSession session) { this.session = session; }
  }

  static final class RecExt {
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

  static final RecExt[] RECOMMENDED = {
    // Matches Firefox for Android's own add-ons manager (see reference video).
    new RecExt("uBlock Origin", "Finally, an efficient wide-spectrum content blocker. Easy on CPU and memory.", "ublock-origin", R.drawable.ic_ext_ublock),
    new RecExt("AdGuard AdBlocker", "Unmatched adblock extension against advertising and pop-ups. Blocks ads on Facebook, YouTube and all other websites.", "adguard-adblocker", R.drawable.ic_ext_adguard),
    new RecExt("Ghostery", "Fast, private, and ad-free browsing you can trust!", "ghostery", R.drawable.ic_ext_ghostery),
    new RecExt("Privacy Badger", "Automatically learns to block hidden trackers. Made by leading digital rights nonprofit EFF to stop companies from spying on you.", "privacybadger17", R.drawable.ic_ext_privacybadger),
    new RecExt("Dark Reader", "Dark mode for every website. Take care of your eyes, use dark theme for night and daily browsing.", "darkreader", R.drawable.ic_ext_darkreader),
    new RecExt("FoxyProxy Standard", "FoxyProxy is an open-source, advanced proxy management tool that completely replaces Firefox's limited proxying capabilities.", "foxyproxy-standard", R.drawable.ic_ext_foxyproxy),
    new RecExt("Search by Image", "A powerful reverse image search tool, with support for various search engines, such as Google, Bing, Yandex, Baidu and TinEye.", "search_by_image", R.drawable.ic_ext_searchbyimage),
    new RecExt("NoScript Security Suite", "The best security you can get in a web browser! Allow potentially malicious web content to run only from sites you trust.", "noscript", R.drawable.ic_ext_noscript),
    new RecExt("Read Aloud", "Read out loud the current web-page article with one click. Supports 40+ languages.", "read-aloud", R.drawable.ic_ext_readaloud),
    new RecExt("ClearURLs", "Automatically removes tracking parameters from URLs.", "clearurls", R.drawable.ic_ext_clearurls),
    new RecExt("Decentraleyes", "Protects you against tracking through 'free', centralized, content delivery. Prevents requests from reaching networks like Google Hosted Libraries.", "decentraleyes", R.drawable.ic_ext_decentraleyes),
    new RecExt("Firefox Relay", "Firefox Relay lets you generate email aliases that forward to your real inbox. Hide your real email address from hackers and unwanted mail.", "private-relay", R.drawable.ic_ext_relay),
    new RecExt("YouTube High Definition", "Automatically plays all YouTube videos in HD, changes video player size, offers auto-stop, mute, and much more.", "youtube-high-definition", R.drawable.ic_ext_ythd),
    new RecExt("Tomato Clock", "A simple browser extension for managing your productivity.", "tomato-clock", R.drawable.ic_ext_tomato),
    new RecExt("Web Archives", "View archived and cached versions of web pages on various search engines, such as the Wayback Machine and Archive.is.", "view-page-archive", R.drawable.ic_ext_webarchives),
    new RecExt("Video Background Play Fix", "Fix for sites that don't work with Firefox for Android video background play. Blocks the Page Visibility API and the Fullscreen API.", "video-background-play-fix", R.drawable.ic_ext_videofix),
    new RecExt("Google Search Fixer", "Override the user-agent string presented to Google Search pages to receive the search experience shown to Chrome.", "google-youtube-search-fixer", R.drawable.ic_ext_gsearchfix),
    new RecExt("SponsorBlock", "Skip sponsored content", "sponsorblock", R.drawable.ic_ext_sponsorblock),
    new RecExt("Tampermonkey", "Run userscripts on any site", "tampermonkey", R.drawable.ic_ext_tampermonkey),
    new RecExt("Bitwarden", "Free password manager", "bitwarden-password-manager", R.drawable.ic_ext_bitwarden),
    new RecExt("Grammarly", "Write better everywhere", "grammarly", R.drawable.ic_ext_grammarly, true),
    new RecExt("React Developer Tools", "Debug React apps", "react-devtools", R.drawable.ic_ext_react, true),
  };

  private static final class Tile {
    String name = "";
    String url = "";
  }
  private final ArrayList<Tile> customTiles = new ArrayList<>();

  private static final int[] SIDEBAR_IDS = {
    R.id.home, R.id.bookmarks, R.id.history, R.id.downloads, R.id.extensions, R.id.vpn, R.id.settings
  };

  void onClick(int id, View.OnClickListener l) {
    View v = findViewById(id);
    if (v != null) v.setOnClickListener(l);
  }

  int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density);
  }

  @Override public void onCreate(Bundle state) {
    // Appearance: theme + dynamic colors must apply before super.onCreate.
    SharedPreferences earlyPrefs = getSharedPreferences("devxyz", MODE_PRIVATE);
    Appearance.applyTheme(earlyPrefs);
    Appearance.applyDynamicColors(this, earlyPrefs);
    super.onCreate(state);
    // TV detected -> hand off to the D-pad friendly TV home.
    if (TvUtil.isTv(this)) {
      startActivity(new Intent(this, TvMainActivity.class));
      finish();
      return;
    }
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
        Ui.toastLong(this, "VPN permission is required to connect");
      }
    });
    openVpnProfile = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
      if (uri == null) return;
      try {
        VpnProfileStore.importProfile(this, uri);
        Ui.toastLong(this, "OpenVPN profile imported");
      } catch (Exception ex) {
        Ui.toastLong(this, "Profile rejected: " + ex.getMessage());
      }
      refreshVpnPanel();
    });
    settingsScreens.initDownloadLaunchers();
    if (runtime == null) {
      GeckoRuntimeSettings.Builder rsb = new GeckoRuntimeSettings.Builder();
      // Brave-style Shields: real GeckoView content blocking from prefs.
      // Trackers/ads mode, fingerprinting and cookie blocking apply on restart;
      // script blocking is applied live per session (see applyBlockScripts).
      rsb.contentBlocking(Shields.build(prefs));
      runtime = GeckoRuntime.create(this, rsb.build());
    }
    // Firefox-like extension install/update prompts: tapping "Add to Firefox" on
    // addons.mozilla.org (or any site-triggered install) shows our themed dialog.
    runtime.getWebExtensionController().setPromptDelegate(new WebExtensionController.PromptDelegate() {
      private String permText(String[] perms, String[] origins) {
        java.util.List<String> p = new java.util.ArrayList<>();
        if (perms != null) for (String s : perms) p.add(s);
        if (origins != null) for (String s : origins) p.add("Access " + s);
        return p.isEmpty() ? null : "It will need permission to:\n• " + String.join("\n• ", p);
      }
      @Override
      public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
          WebExtension ext, String[] perms, String[] origins, String[] dataCollection) {
        GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
        runOnUiThread(() -> {
          try {
            String name = extName(ext);
            StringBuilder msg = new StringBuilder();
            if (ext.metaData != null && ext.metaData.description != null) {
              msg.append(ext.metaData.description).append("\n\n");
            }
            String pt = permText(perms, origins);
            msg.append(pt != null ? pt : "Add this extension to DevxyzBrowser?");
            Ui.dialog(MainActivity.this).setTitle("Add " + name + "?")
              .setMessage(msg.toString())
              .setPositiveButton("Add", (d, w) -> {
                result.complete(new WebExtension.PermissionPromptResponse(true, false, true));
                Ui.toast(MainActivity.this, "Installing " + name + "…");
                refreshExtensionLists();
                // The install finishes asynchronously; refresh again to pick it up.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> refreshExtensionLists(), 4000);
              })
              .setNegativeButton("Cancel", (d, w) ->
                result.complete(new WebExtension.PermissionPromptResponse(false, false, false)))
              .setOnCancelListener(d -> {
                try { result.complete(new WebExtension.PermissionPromptResponse(false, false, false)); }
                catch (Exception ignored) {}
              })
              .show();
          } catch (Exception e) {
            try { result.complete(new WebExtension.PermissionPromptResponse(false, false, false)); }
            catch (Exception ignored) {}
          }
        });
        return result;
      }
      private GeckoResult<AllowOrDeny> askAllowDeny(WebExtension ext, String title, String[] perms, String[] origins) {
        GeckoResult<AllowOrDeny> result = new GeckoResult<>();
        runOnUiThread(() -> {
          try {
            String name = extName(ext);
            StringBuilder msg = new StringBuilder();
            if (ext.metaData != null && ext.metaData.description != null) {
              msg.append(ext.metaData.description).append("\n\n");
            }
            String pt = permText(perms, origins);
            msg.append(pt != null ? pt : title + " " + name + "?");
            Ui.dialog(MainActivity.this).setTitle(title + " " + name + "?")
              .setMessage(msg.toString())
              .setPositiveButton("Allow", (d, w) -> {
                result.complete(AllowOrDeny.ALLOW);
                refreshExtensionLists();
              })
              .setNegativeButton("Deny", (d, w) -> result.complete(AllowOrDeny.DENY))
              .setOnCancelListener(d -> { try { result.complete(AllowOrDeny.DENY); } catch (Exception ignored) {} })
              .show();
          } catch (Exception e) {
            try { result.complete(AllowOrDeny.DENY); } catch (Exception ignored) {}
          }
        });
        return result;
      }
      @Override
      public GeckoResult<AllowOrDeny> onUpdatePrompt(WebExtension ext, String[] perms, String[] origins, String[] dataCollection) {
        return askAllowDeny(ext, "Update", perms, origins);
      }
      @Override
      public GeckoResult<AllowOrDeny> onOptionalPrompt(WebExtension ext, String[] perms, String[] origins, String[] dataCollection) {
        return askAllowDeny(ext, "Grant permissions to", perms, origins);
      }
    });
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
    ContextCompat.registerReceiver(this, downloadCompleteReceiver,
        new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_NOT_EXPORTED);

    // Open web links when we're launched as the default browser.
    Intent launchIntent = getIntent();
    if (launchIntent != null && Intent.ACTION_VIEW.equals(launchIntent.getAction())
        && launchIntent.getData() != null) {
      final String linkUrl = launchIntent.getData().toString();
      newTab(linkUrl);
    }

    // Chrome-style: omnibox + tab count + menu. All actions live in the menu.
    onClick(R.id.menu, v -> showChromeMenu(v));
    onClick(R.id.tabCount, v -> showTabs());
    settingsScreens.applyAppearance();
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
    vpnStatsText = root.findViewById(R.id.vpn_stats);
    vpnFavorites.addAll(prefs.getStringSet("vpn_favorites", new java.util.HashSet<>()));
    swAuto = root.findViewById(R.id.sw_autoconnect);
    swKill = root.findViewById(R.id.sw_killswitch);
    swTrack = root.findViewById(R.id.sw_blocktrackers);
    vpnPower.setOnClickListener(v -> toggleVpnConnection());
    root.findViewById(R.id.vpn_location_row).setOnClickListener(v -> showVpnServerPicker());
    root.findViewById(R.id.vpn_gear).setOnClickListener(v -> showVpnServerPicker());
    swAuto.setChecked(prefs.getBoolean("auto_connect", false));
    swKill.setChecked(prefs.getBoolean("kill_switch", false));
    swTrack.setChecked(Shields.mode(prefs) == 2);
    swAuto.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("auto_connect", c).apply());
    swKill.setOnCheckedChangeListener((b, c) -> {
      prefs.edit().putBoolean("kill_switch", c).apply();
      DevxyzVpnService.killSwitch = c;
    });
    swTrack.setOnCheckedChangeListener((b, c) -> {
      prefs.edit().putInt("shields_mode", c ? 2 : 1).apply();
      refreshSettingsPanel();
      Ui.toastLong(this, c ? "Tracker blocking turns on after you restart the app"
        : "Tracker blocking turns off after you restart the app");
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
    String serverId = VpnProfileStore.getActiveServerId(this);
    if (!serverId.isEmpty()) {
      VpnGateApi.VpnServer s = findVpnServer(serverId);
      vpnHostText.setText(s != null ? s.flag() + " " + s.country : serverId);
      vpnSubText.setText(s != null ? s.hostname + " • Tap to change server" : "Tap to change server");
    } else {
      String endpoint = VpnProfileStore.remoteEndpoint(this);
      vpnHostText.setText(hasProfile ? (endpoint.isEmpty() ? "OpenVPN profile" : endpoint) : "No profile");
      vpnSubText.setText(hasProfile ? "Tap to change server" : "Tap to choose a server");
    }
    vpnSecureTitle.setText(connected ? "Your connection is secure" : "You're not protected");
    vpnSecureIcon.setImageTintList(ColorStateList.valueOf(getColor(connected ? R.color.green : R.color.muted)));
    if (connected) {
      if (!ipLookedUp) {
        ipLookedUp = true;
        vpnIpText.setText("IP: looking up…");
        fetchPublicIp();
      }
      vpnStatsText.setVisibility(View.VISIBLE);
      startVpnStatsTicker();
    } else {
      ipLookedUp = false;
      vpnIpText.setText("IP:...");
      fetchPublicIp();
      vpnStatsText.setVisibility(View.GONE);
      vpnStatsText.setText("");
      stopVpnStatsTicker();
    }
  }

  private VpnGateApi.VpnServer findVpnServer(String id) {
    for (VpnGateApi.VpnServer s : vpnServers) {
      if (s.id().equals(id)) return s;
    }
    return null;
  }

  private void startVpnStatsTicker() {
    stopVpnStatsTicker();
    vpnStatsTick = new Runnable() {
      @Override public void run() {
        if (!DevxyzVpnService.isConnected() || vpnStatsText == null) return;
        long dur = System.currentTimeMillis() - DevxyzVpnService.getConnectTimeMs();
        long rx = DevxyzVpnService.getSessionRxBytes();
        long tx = DevxyzVpnService.getSessionTxBytes();
        String traffic = (rx < 0 || tx < 0) ? "—"
          : "↓ " + formatBytes(rx) + "   ↑ " + formatBytes(tx);
        vpnStatsText.setText("⏱ " + formatDuration(dur) + "   •   " + traffic);
        vpnStatsHandler.postDelayed(this, 1000);
      }
    };
    vpnStatsHandler.post(vpnStatsTick);
  }

  private void stopVpnStatsTicker() {
    if (vpnStatsTick != null) {
      vpnStatsHandler.removeCallbacks(vpnStatsTick);
      vpnStatsTick = null;
    }
  }

  private static String formatDuration(long ms) {
    long s = Math.max(0, ms / 1000);
    long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
    if (h > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec);
    return String.format(java.util.Locale.ROOT, "%02d:%02d", m, sec);
  }

  private static String formatBytes(long b) {
    if (b < 1024) return b + " B";
    if (b < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0);
    if (b < 1024L * 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0);
    return String.format(java.util.Locale.ROOT, "%.2f GB", b / 1073741824.0);
  }

  private void toggleVpnConnection() {
    if (DevxyzVpnService.isConnected()) {
      Intent stop = new Intent(this, DevxyzVpnService.class).setAction(DevxyzVpnService.ACTION_DISCONNECT);
      startService(stop);
      return;
    }
    DevxyzVpnService.killSwitch = prefs.getBoolean("kill_switch", false);
    if (!VpnProfileStore.hasProfile(this)) {
      showVpnServerPicker();
      return;
    }
    Intent permission = VpnService.prepare(this);
    if (permission != null) vpnPermission.launch(permission);
    else requestVpnCredentials();
  }

  // ---------- Built-in VPN servers (VPNGate public list) ----------

  private AlertDialog vpnPickerDialog;
  private LinearLayout vpnPickerList;
  private TextView vpnPickerStatus;

  private int vpnPickerSession = 0;
  private void showVpnServerPicker() {
    vpnPickerSession++;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    root.setPadding(pad, dp(4), pad, 0);

    // Search + refresh row.
    LinearLayout topRow = new LinearLayout(this);
    topRow.setOrientation(LinearLayout.HORIZONTAL);
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    EditText search = new EditText(this);
    search.setHint("Search country or server…");
    search.setSingleLine(true);
    search.setTextSize(14);
    topRow.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    TextView refreshBtn = new TextView(this);
    refreshBtn.setText("↻");
    refreshBtn.setTextSize(22);
    refreshBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
    refreshBtn.setClickable(true);
    refreshBtn.setFocusable(true);
    topRow.addView(refreshBtn);
    root.addView(topRow);

    vpnPickerStatus = new TextView(this);
    vpnPickerStatus.setTextSize(12);
    vpnPickerStatus.setTextColor(getColor(R.color.muted));
    vpnPickerStatus.setPadding(0, dp(4), 0, dp(4));
    root.addView(vpnPickerStatus);

    ScrollView sv = new ScrollView(this);
    vpnPickerList = new LinearLayout(this);
    vpnPickerList.setOrientation(LinearLayout.VERTICAL);
    sv.addView(vpnPickerList, new ScrollView.LayoutParams(
      ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, dp(380));
    root.addView(sv, svp);

    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void afterTextChanged(Editable s) {
        vpnServerFilter = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
        renderVpnPickerList();
      }
    });
    refreshBtn.setOnClickListener(v -> loadVpnServers(true));

    vpnPickerDialog = Ui.dialog(this)
      .setTitle("Choose VPN server")
      .setView(root)
      .setNegativeButton("Close", null)
      .show();
    loadVpnServers(false);
  }

  private void loadVpnServers(boolean forceRefresh) {
    if (vpnPickerList == null) return;
    final int session = vpnPickerSession;
    if (!forceRefresh && !vpnServers.isEmpty()) { renderVpnPickerList(); return; }
    vpnPickerStatus.setText(forceRefresh ? "Refreshing server list…" : "Loading servers…");
    vpnPickerList.removeAllViews();
    TextView loading = new TextView(this);
    loading.setText("Fetching public servers…");
    loading.setTextColor(getColor(R.color.muted));
    loading.setPadding(0, dp(16), 0, dp(16));
    loading.setGravity(Gravity.CENTER);
    vpnPickerList.addView(loading);
    VpnGateApi.fetch(this, forceRefresh, new VpnGateApi.Callback() {
      @Override public void onResult(java.util.List<VpnGateApi.VpnServer> servers, boolean fromCache) {
        if (session != vpnPickerSession || vpnPickerList == null) return;
        if (!fromCache && !vpnServers.isEmpty()) return;
        vpnServers.clear();
        vpnServers.addAll(servers);
        java.util.Set<String> countries = new java.util.TreeSet<>();
        for (VpnGateApi.VpnServer s : servers) countries.add(s.country);
        vpnPickerStatus.setText(servers.size() + " servers • " + countries.size() + " countries"
          + (fromCache ? " (cached)" : ""));
        renderVpnPickerList();
        refreshVpnPanel();
      }
      @Override public void onError(String message) {
        vpnPickerStatus.setText(message);
        vpnPickerList.removeAllViews();
      }
    });
  }

  private void renderVpnPickerList() {
    if (vpnPickerList == null) return;
    vpnPickerList.removeAllViews();
    String activeId = VpnProfileStore.getActiveServerId(this);
    boolean hasCustom = VpnProfileStore.hasProfile(this) && activeId.isEmpty();

    // "My .ovpn profile" row.
    LinearLayout customRow = new LinearLayout(this);
    customRow.setOrientation(LinearLayout.HORIZONTAL);
    customRow.setGravity(Gravity.CENTER_VERTICAL);
    customRow.setPadding(0, dp(10), 0, dp(10));
    TextView customIcon = new TextView(this);
    customIcon.setText("\uD83D\uDCC4");
    customIcon.setTextSize(24);
    customRow.addView(customIcon);
    LinearLayout customTexts = new LinearLayout(this);
    customTexts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    ctp.setMargins(dp(12), 0, dp(8), 0);
    TextView customName = new TextView(this);
    customName.setText("My .ovpn profile");
    customName.setTextSize(14);
    customName.setTypeface(customName.getTypeface(), android.graphics.Typeface.BOLD);
    customName.setTextColor(getColor(R.color.text));
    TextView customSub = new TextView(this);
    customSub.setText(hasCustom ? VpnProfileStore.remoteEndpoint(this) + " • Tap to use" : "Tap Import to add your own");
    customSub.setTextSize(12);
    customSub.setTextColor(getColor(R.color.muted));
    customTexts.addView(customName);
    customTexts.addView(customSub);
    customRow.addView(customTexts, ctp);
    TextView importBtn = new TextView(this);
    importBtn.setText(hasCustom ? "Replace" : "Import");
    importBtn.setTextSize(13);
    importBtn.setTextColor(getColor(R.color.orange));
    importBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
    importBtn.setClickable(true);
    importBtn.setFocusable(true);
    importBtn.setOnClickListener(v ->
      openVpnProfile.launch(new String[]{"application/x-openvpn-profile", "application/octet-stream", "text/plain"}));
    customRow.addView(importBtn);
    if (hasCustom) {
      customRow.setClickable(true);
      customRow.setFocusable(true);
      customRow.setOnClickListener(v -> {
        if (vpnPickerDialog != null) vpnPickerDialog.dismiss();
        Ui.toast(this, "Using your .ovpn profile");
        toggleVpnConnection();
      });
    }
    vpnPickerList.addView(customRow);

    // Server rows: favorites first, then by ping.
    java.util.List<VpnGateApi.VpnServer> favs = new java.util.ArrayList<>();
    java.util.List<VpnGateApi.VpnServer> rest = new java.util.ArrayList<>();
    for (VpnGateApi.VpnServer s : vpnServers) {
      if (!matchesVpnFilter(s)) continue;
      if (vpnFavorites.contains(s.id())) favs.add(s); else rest.add(s);
    }
    if (!favs.isEmpty()) {
      vpnPickerList.addView(vpnPickerSectionHeader("★ Favorites"));
      for (VpnGateApi.VpnServer s : favs) vpnPickerList.addView(vpnServerRow(s, activeId));
    }
    if (!rest.isEmpty()) {
      vpnPickerList.addView(vpnPickerSectionHeader(
        vpnServerFilter.isEmpty() ? "All servers (fastest first)" : "Results (" + rest.size() + ")"));
      for (VpnGateApi.VpnServer s : rest) vpnPickerList.addView(vpnServerRow(s, activeId));
    }
    if (favs.isEmpty() && rest.isEmpty() && !vpnServers.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText("No servers match your search.");
      empty.setTextColor(getColor(R.color.muted));
      empty.setPadding(0, dp(16), 0, dp(16));
      empty.setGravity(Gravity.CENTER);
      vpnPickerList.addView(empty);
    }
  }

  private boolean matchesVpnFilter(VpnGateApi.VpnServer s) {
    if (vpnServerFilter.isEmpty()) return true;
    String f = vpnServerFilter;
    return s.country.toLowerCase(java.util.Locale.ROOT).contains(f)
      || s.hostname.toLowerCase(java.util.Locale.ROOT).contains(f)
      || s.ip.contains(f)
      || s.countryCode.toLowerCase(java.util.Locale.ROOT).contains(f);
  }

  private TextView vpnPickerSectionHeader(String text) {
    TextView h = new TextView(this);
    h.setText(text);
    h.setTextSize(12);
    h.setTextColor(getColor(R.color.muted));
    h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
    h.setPadding(0, dp(12), 0, dp(4));
    return h;
  }

  private View vpnServerRow(VpnGateApi.VpnServer s, String activeId) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    TextView flag = new TextView(this);
    flag.setText(s.flag());
    flag.setTextSize(26);
    row.addView(flag);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    tp.setMargins(dp(12), 0, dp(8), 0);
    TextView nameView = new TextView(this);
    nameView.setText(s.country + (s.id().equals(activeId) ? "  •  active" : ""));
    nameView.setTextSize(14);
    nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
    nameView.setTextColor(getColor(s.id().equals(activeId) ? R.color.green : R.color.text));
    TextView subView = new TextView(this);
    subView.setText(s.hostname + " • " + s.pingMs + " ms • " + s.speedLabel()
      + " • " + s.sessions + " sessions");
    subView.setTextSize(11);
    subView.setTextColor(getColor(R.color.muted));
    texts.addView(nameView);
    texts.addView(subView);
    row.addView(texts, tp);
    TextView star = new TextView(this);
    boolean fav = vpnFavorites.contains(s.id());
    star.setText(fav ? "★" : "☆");
    star.setTextSize(22);
    star.setTextColor(getColor(fav ? R.color.orange : R.color.muted));
    star.setPadding(dp(8), dp(8), dp(8), dp(8));
    star.setClickable(true);
    star.setFocusable(true);
    star.setOnClickListener(v -> {
      if (vpnFavorites.contains(s.id())) vpnFavorites.remove(s.id());
      else vpnFavorites.add(s.id());
      prefs.edit().putStringSet("vpn_favorites", new java.util.HashSet<>(vpnFavorites)).apply();
      renderVpnPickerList();
    });
    row.addView(star);
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> connectToVpnServer(s));
    return row;
  }

  private void connectToVpnServer(VpnGateApi.VpnServer s) {
    if (DevxyzVpnService.isConnected()) {
      Ui.toastLong(this, "Disconnect first to switch servers");
      return;
    }
    Ui.toast(this, "Preparing " + s.country + " server…");
    new Thread(() -> {
      try {
        byte[] decoded = android.util.Base64.decode(s.configBase64, android.util.Base64.DEFAULT);
        String config = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        VpnProfileStore.writeActiveProfile(this, config);
        VpnProfileStore.setActiveServerId(this, s.id());
        runOnUiThread(() -> {
          if (vpnPickerDialog != null) vpnPickerDialog.dismiss();
          refreshVpnPanel();
          // Auto-connect using the same path as the power button
          toggleVpnConnection();
        });
      } catch (Exception e) {
        runOnUiThread(() -> Ui.toastLong(this, "Could not prepare server: " + e.getMessage()));
      }
    }).start();
  }

  private void fetchPublicIp() {
    new Thread(() -> {
      String r = null;
      try {
        String j = new java.util.Scanner(new URL("https://ip-api.com/json/?fields=query,city,country").openStream(), "UTF-8").useDelimiter("\\A").next();
        String ip = jv(j, "query");
        if (ip != null) r = "IP: " + ip + " (" + jv(j, "city") + ", " + jv(j, "country") + ")";
      } catch (Exception ignored) { }
      final String f = r;
      runOnUiThread(() -> { if (vpnIpText != null) vpnIpText.setText(f != null ? f : "IP: unavailable"); });
    }).start();
  }

  private String jv(String j, String k) {
    int i = j.indexOf(k + "\":\""); if (i < 0) return "?";
    int s = j.indexOf("\"", i + k.length() + 3) + 1, e = j.indexOf("\"", s);
    return j.substring(s, e);
  }

  // ---------- Extensions panel ----------
  private void initExtPanel(View root, Runnable onClose) {
    if (root == null) return;
    extPanelRoot = root;
    extSearch = root.findViewById(R.id.ext_search);
    extInstalledList = root.findViewById(R.id.ext_installed_list);
    extRecommendedList = root.findViewById(R.id.ext_recommended_list);
    extSearchResults = root.findViewById(R.id.ext_search_results);
    extSearch.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void afterTextChanged(Editable s) {
        extFilter = s.toString();
        renderExtensionLists();
        // Debounced search across ALL of addons.mozilla.org, like Firefox.
        if (amoPending != null) amoHandler.removeCallbacks(amoPending);
        final String q = s.toString().trim();
        amoPending = () -> searchAmo(q);
        amoHandler.postDelayed(amoPending, 600);

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

  private final android.os.Handler extRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private Runnable extRefreshPending = null;

  void refreshExtensionLists() {
    if (extInstalledList == null) return;
    if (extRefreshPending != null) extRefreshHandler.removeCallbacks(extRefreshPending);
    extRefreshPending = () -> {
      extRefreshPending = null;
      if (extInstalledList == null) return;
      runtime.getWebExtensionController().list().accept(
        list -> {
          installedExts.clear();
          if (list != null) installedExts.addAll(list);
          runOnUiThread(this::renderExtensionLists);
        },
        err -> runOnUiThread(this::renderExtensionLists));
    };
    extRefreshHandler.postDelayed(extRefreshPending, 120);
  }

  private String extName(WebExtension ext) {
    if (ext != null && ext.metaData != null && ext.metaData.name != null) return ext.metaData.name;
    return "Extension";
  }

  void renderExtensionLists() {
    if (extInstalledList == null) return;
    extInstalledList.removeAllViews();
    extRecommendedList.removeAllViews();
    String f = extFilter.trim().toLowerCase(Locale.ROOT);
    boolean anyInstalled = false;
    for (WebExtension ext : installedExts) {
      String name = extName(ext);
      if (!f.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(f)) continue;
      if (!anyInstalled) extInstalledList.addView(extSectionHeader("Installed (" + installedExts.size() + ")"));
      anyInstalled = true;
      extInstalledList.addView(installedExtRow(ext, name));
    }
    if (!anyInstalled && !f.isEmpty()) extInstalledList.addView(emptyExtRow("No extensions match your search."));
    java.util.List<RecExt> recs = new java.util.ArrayList<>(java.util.Arrays.asList(RECOMMENDED));
    if (prefs.getBoolean("ext_sort_az", false)) {
      java.util.Collections.sort(recs, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }
    boolean headerAdded = false;
    for (RecExt rec : recs) {
      if (!f.isEmpty() && !rec.name.toLowerCase(Locale.ROOT).contains(f)) continue;
      if (!headerAdded) { extRecommendedList.addView(extSectionHeader("Recommended")); headerAdded = true; }
      extRecommendedList.addView(recommendedExtRow(rec, recInstalled.contains(rec.slug)));
    }
  }

  private View extSectionHeader(String text) {
    TextView h = new TextView(this);
    h.setText(text);
    h.setTextSize(12);
    h.setTextColor(getColor(R.color.muted));
    h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
    h.setPadding(0, dp(12), 0, dp(4));
    return h;
  }

  /** Searches ALL extensions on addons.mozilla.org via the public API, like Firefox. */
  private void searchAmo(String q) {
    amoQuery = q;
    amoResults.clear();
    if (extSearchResults == null) return;
    if (q.length() < 2) {
      runOnUiThread(this::renderAmoResults);
      return;
    }
    runOnUiThread(() -> {
      extSearchResults.removeAllViews();
      extSearchResults.addView(extSectionHeader("Searching addons.mozilla.org…"));
    });
    new Thread(() -> {
      try {
        String url = "https://addons.mozilla.org/api/v5/addons/search/?q="
            + java.net.URLEncoder.encode(q, "UTF-8")
            + "&app=firefox&type=extension&page_size=20&sort=relevance";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "DevxyzBrowser");
        c.setRequestProperty("Accept", "application/json");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
          String line;
          while ((line = br.readLine()) != null) sb.append(line);
        } finally {
          c.disconnect();
        }
        java.util.List<AmoExt> out = new java.util.ArrayList<>();
        org.json.JSONObject root = new org.json.JSONObject(sb.toString());
        org.json.JSONArray arr = root.optJSONArray("results");
        if (arr != null) {
          for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            AmoExt a = new AmoExt();
            a.slug = o.optString("slug", "");
            a.name = localized(o.optJSONObject("name"), a.slug);
            a.summary = localized(o.optJSONObject("summary"), "");
            org.json.JSONArray authors = o.optJSONArray("authors");
            if (authors != null && authors.length() > 0) {
              a.author = authors.optJSONObject(0).optString("name", "");
            }
            a.iconUrl = o.optString("icon_url", "");
            a.users = o.optLong("average_daily_users", 0);
            org.json.JSONObject ratings = o.optJSONObject("ratings");
            if (ratings != null) a.rating = ratings.optDouble("average", 0);
            org.json.JSONObject cv = o.optJSONObject("current_version");
            if (cv != null) {
              a.version = cv.optString("version", "");
              org.json.JSONObject file = cv.optJSONObject("file");
              if (file != null) a.fileUrl = file.optString("url", "");
            }
            if (!a.fileUrl.isEmpty()) out.add(a);
          }
        }
        final java.util.List<AmoExt> done = out;
        runOnUiThread(() -> {
          // Ignore stale responses if the user typed something else meanwhile.
          if (!amoQuery.equals(q)) return;
          amoResults.clear();
          amoResults.addAll(done);
          renderAmoResults();
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          if (!amoQuery.equals(q) || extSearchResults == null) return;
          extSearchResults.removeAllViews();
          extSearchResults.addView(extSectionHeader("Search failed — check your connection"));
        });
      }
    }).start();
  }

  private static String localized(org.json.JSONObject o, String fallback) {
    if (o == null) return fallback;
    String v = o.optString("en-US", null);
    if (v != null && !v.isEmpty()) return v;
    java.util.Iterator<String> keys = o.keys();
    if (keys.hasNext()) {
      String k = keys.next();
      v = o.optString(k, null);
      if (v != null && !v.isEmpty()) return v;
    }
    return fallback;
  }

  private void renderAmoResults() {
    if (extSearchResults == null) return;
    extSearchResults.removeAllViews();
    if (amoQuery.length() < 2 || amoResults.isEmpty()) return;
    extSearchResults.addView(extSectionHeader("All extensions (" + amoResults.size() + ")"));
    for (AmoExt a : amoResults) {
      extSearchResults.addView(amoExtRow(a));
    }
  }

  private View amoExtRow(AmoExt a) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_extension_puzzle);
    row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
    if (!a.iconUrl.isEmpty()) ImgUtil.loadUrl(icon, a.iconUrl);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    tp.setMargins(dp(12), 0, dp(8), 0);
    TextView nameView = new TextView(this);
    nameView.setText(a.name);
    nameView.setTextSize(14);
    nameView.setTextColor(getColor(R.color.text));
    nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
    TextView descView = new TextView(this);
    StringBuilder sub = new StringBuilder();
    if (!a.author.isEmpty()) sub.append(a.author);
    if (!a.version.isEmpty()) { if (sub.length() > 0) sub.append(" • "); sub.append("v").append(a.version); }
    if (a.rating > 0) { if (sub.length() > 0) sub.append(" • "); sub.append("★ ").append(String.format(Locale.ROOT, "%.1f", a.rating)); }
    descView.setText(sub.toString());
    descView.setTextSize(12);
    descView.setTextColor(getColor(R.color.muted));
    texts.addView(nameView);
    texts.addView(descView);
    row.addView(texts, tp);
    // Install button (or state if already installed).
    boolean installed = false;
    for (WebExtension ext : installedExts) {
      if (a.slug.equalsIgnoreCase(ext.id) || extName(ext).equalsIgnoreCase(a.name)) { installed = true; break; }
    }
    if (installed) {
      TextView st = new TextView(this);
      st.setText("Installed");
      st.setTextSize(12);
      st.setTextColor(getColor(R.color.green));
      row.addView(st);
    } else {
      com.google.android.material.button.MaterialButton btn = new com.google.android.material.button.MaterialButton(this);
      btn.setText("Get");
      btn.setTextSize(12);
      btn.setCornerRadius(dp(16));
      btn.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.orange)));
      btn.setTextColor(getColor(R.color.text));
      btn.setPadding(dp(8), 0, dp(8), 0);
      btn.setMinWidth(0);
      btn.setMinimumWidth(0);
      btn.setOnClickListener(v -> installAmo(a, btn));
      row.addView(btn, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
    }
    // Tap the row for full details like the AMO listing page.
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> {
      Ui.toast(this, "Loading details…");
      fetchAmoDetail(a.slug, d -> {
        if (d == null) { Ui.toastLong(this, "Could not load details"); return; }
        showRichAmoDialog(d, findInstalledByName(a.name), a);
      });
    });
    return row;
  }

  private void installAmo(AmoExt a, View btn) {
    btn.setEnabled(false);
    Ui.toast(this, "Installing " + a.name + "…");
    try {
      runtime.getWebExtensionController().install(a.fileUrl).accept(
        ext -> runOnUiThread(() -> {
          Ui.toastLong(this, "Installed: " + extName(ext));
          refreshExtensionLists();
          renderAmoResults();
        }),
        err -> runOnUiThread(() -> {
          btn.setEnabled(true);
          Ui.toastLong(this, "Install failed: " + (err == null ? "unknown error" : err.getMessage()));
        }));
    } catch (Exception e) {
      btn.setEnabled(true);
      Ui.toastLong(this, "Install failed: " + e.getMessage());
    }
  }

  // ---------- Rich AMO details (like the Firefox/AMO listing page) ----------

  private static final class AmoDetail {
    String name = "", slug = "", summary = "", descriptionHtml = "", version = "",
           author = "", iconUrl = "", homepage = "", lastUpdated = "", fileUrl = "";
    double rating; int ratingCount; long users, weeklyDownloads;
    final java.util.List<String> permissions = new java.util.ArrayList<>();
  }

  private void fetchAmoDetail(String slug, java.util.function.Consumer<AmoDetail> cb) {
    new Thread(() -> {
      AmoDetail d = null;
      try {
        String url = "https://addons.mozilla.org/api/v5/addons/addon/"
            + java.net.URLEncoder.encode(slug, "UTF-8") + "/";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "DevxyzBrowser");
        c.setRequestProperty("Accept", "application/json");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
          String line;
          while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        org.json.JSONObject o = new org.json.JSONObject(sb.toString());
        d = new AmoDetail();
        d.slug = o.optString("slug", slug);
        d.name = localized(o.optJSONObject("name"), slug);
        d.summary = localized(o.optJSONObject("summary"), "");
        d.descriptionHtml = localized(o.optJSONObject("description"), "");
        d.iconUrl = o.optString("icon_url", "");
        d.lastUpdated = o.optString("last_updated", "");
        d.users = o.optLong("average_daily_users", 0);
        d.weeklyDownloads = o.optLong("weekly_downloads", 0);
        org.json.JSONArray authors = o.optJSONArray("authors");
        if (authors != null && authors.length() > 0) d.author = authors.optJSONObject(0).optString("name", "");
        org.json.JSONObject hp = o.optJSONObject("homepage");
        if (hp != null) d.homepage = localized(hp.optJSONObject("url"), "");
        org.json.JSONObject ratings = o.optJSONObject("ratings");
        if (ratings != null) {
          d.rating = ratings.optDouble("average", 0);
          d.ratingCount = ratings.optInt("count", 0);
        }
        org.json.JSONObject cv = o.optJSONObject("current_version");
        if (cv != null) {
          d.version = cv.optString("version", "");
          org.json.JSONObject file = cv.optJSONObject("file");
          if (file != null) {
            d.fileUrl = file.optString("url", "");
            org.json.JSONArray perms = file.optJSONArray("permissions");
            if (perms != null) for (int i = 0; i < perms.length(); i++) d.permissions.add(perms.optString(i));
            org.json.JSONArray hosts = file.optJSONArray("host_permissions");
            if (hosts != null) for (int i = 0; i < hosts.length(); i++) d.permissions.add(hosts.optString(i));
          }
        }
      } catch (Exception ignored) { }
      final AmoDetail done = d;
      runOnUiThread(() -> cb.accept(done));
    }).start();
  }

  /** Finds the AMO slug for an installed extension via its GUID. */
  private void findAmoSlug(WebExtension ext, java.util.function.Consumer<String> cb) {
    new Thread(() -> {
      String slug = null;
      try {
        String url = "https://addons.mozilla.org/api/v5/addons/search/?guid="
            + java.net.URLEncoder.encode(ext.id == null ? "" : ext.id, "UTF-8") + "&page_size=1";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "DevxyzBrowser");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
          String line;
          while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        org.json.JSONArray arr = new org.json.JSONObject(sb.toString()).optJSONArray("results");
        if (arr != null && arr.length() > 0) slug = arr.optJSONObject(0).optString("slug", null);
      } catch (Exception ignored) { }
      final String s = slug;
      runOnUiThread(() -> cb.accept(s));
    }).start();
  }

  private TextView richLabel(String text) {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextSize(12);
    t.setTextColor(getColor(R.color.muted));
    t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
    t.setPadding(0, dp(14), 0, dp(4));
    return t;
  }

  private TextView richValue(String text) {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextSize(13);
    t.setTextColor(getColor(R.color.text));
    return t;
  }

  /** Rich details dialog like the AMO listing page in her video. */
  private void showRichAmoDialog(AmoDetail d, WebExtension installed, AmoExt basic) {
    String name = !d.name.isEmpty() ? d.name : (basic != null ? basic.name : (installed != null ? extName(installed) : "Extension"));
    ScrollView sv = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(4), dp(4), dp(4), dp(4));
    sv.addView(body);

    // Header: icon + name + author
    LinearLayout head = new LinearLayout(this);
    head.setOrientation(LinearLayout.HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_extension_puzzle);
    head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
    String iconUrl = !d.iconUrl.isEmpty() ? d.iconUrl : (basic != null ? basic.iconUrl : "");
    if (!iconUrl.isEmpty()) ImgUtil.loadUrl(icon, iconUrl);
    LinearLayout ht = new LinearLayout(this);
    ht.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    hp.setMargins(dp(12), 0, 0, 0);
    TextView nt = new TextView(this);
    nt.setText(name);
    nt.setTextSize(17);
    nt.setTypeface(nt.getTypeface(), android.graphics.Typeface.BOLD);
    nt.setTextColor(getColor(R.color.text));
    ht.addView(nt);
    String author = !d.author.isEmpty() ? d.author : (basic != null ? basic.author : "");
    if (!author.isEmpty()) {
      TextView at = new TextView(this);
      at.setText("by " + author);
      at.setTextSize(12);
      at.setTextColor(getColor(R.color.muted));
      ht.addView(at);
    }
    head.addView(ht, hp);
    body.addView(head);

    // Rating / version / users row
    StringBuilder stats = new StringBuilder();
    if (d.rating > 0) stats.append("★ ").append(String.format(Locale.ROOT, "%.1f", d.rating));
    if (d.ratingCount > 0) stats.append(" (").append(formatCount(d.ratingCount)).append(" reviews)");
    String ver = !d.version.isEmpty() ? d.version : (basic != null ? basic.version : "");
    if (!ver.isEmpty()) { if (stats.length() > 0) stats.append("\n"); stats.append("Version ").append(ver); }
    if (d.users > 0) { if (stats.length() > 0) stats.append("\n"); stats.append(formatCount(d.users)).append(" users"); }
    if (d.lastUpdated != null && d.lastUpdated.length() >= 10) {
      if (stats.length() > 0) stats.append("\n");
      stats.append("Updated ").append(d.lastUpdated.substring(0, 10));
    }
    if (stats.length() > 0) {
      TextView st = richValue(stats.toString());
      st.setPadding(0, dp(10), 0, 0);
      body.addView(st);
    }

    // Description (HTML like the AMO page)
    if (!d.descriptionHtml.isEmpty()) {
      body.addView(richLabel("About this extension"));
      TextView desc = new TextView(this);
      try {
        desc.setText(android.text.Html.fromHtml(d.descriptionHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
      } catch (Exception e) {
        desc.setText(d.descriptionHtml.replaceAll("<[^>]+>", ""));
      }
      desc.setTextSize(13);
      desc.setTextColor(getColor(R.color.text));
      body.addView(desc);
    } else if (installed != null && installed.metaData != null && installed.metaData.description != null) {
      body.addView(richLabel("About this extension"));
      body.addView(richValue(installed.metaData.description));
    }

    // Permissions
    java.util.List<String> perms = d.permissions.isEmpty() && installed != null && installed.metaData != null
        && installed.metaData.requiredPermissions != null
        ? java.util.Arrays.asList(installed.metaData.requiredPermissions)
        : d.permissions;
    if (!perms.isEmpty()) {
      body.addView(richLabel("Permissions"));
      // Show a readable subset; full list can be long.
      int show = Math.min(perms.size(), 12);
      StringBuilder pb = new StringBuilder();
      for (int i = 0; i < show; i++) pb.append("• ").append(perms.get(i)).append("\n");
      if (perms.size() > show) pb.append("• …and ").append(perms.size() - show).append(" more");
      body.addView(richValue(pb.toString().trim()));
    }

    // Homepage
    if (!d.homepage.isEmpty()) {
      body.addView(richLabel("Homepage"));
      TextView hl = new TextView(this);
      hl.setText(d.homepage);
      hl.setTextSize(13);
      hl.setTextColor(getColor(R.color.blue));
      hl.setOnClickListener(v -> newTab(d.homepage));
      body.addView(hl);
    }

    // Installed-extension controls (Firefox-style)
    if (installed != null) {
      WebExtension.MetaData md = installed.metaData;
      body.addView(richLabel("Settings"));
      LinearLayout enRow = new LinearLayout(this);
      enRow.setOrientation(LinearLayout.HORIZONTAL);
      enRow.setGravity(Gravity.CENTER_VERTICAL);
      TextView enLabel = new TextView(this);
      enLabel.setText("Enabled");
      enLabel.setTextSize(14);
      enLabel.setTextColor(getColor(R.color.text));
      enRow.addView(enLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
      SwitchMaterial enSw = new SwitchMaterial(this);
      enSw.setChecked(md == null || md.enabled);
      enRow.addView(enSw);
      body.addView(enRow);
      LinearLayout pbRow = new LinearLayout(this);
      pbRow.setOrientation(LinearLayout.HORIZONTAL);
      pbRow.setGravity(Gravity.CENTER_VERTICAL);
      TextView pbLabel = new TextView(this);
      pbLabel.setText("Allow in private browsing");
      pbLabel.setTextSize(14);
      pbLabel.setTextColor(getColor(R.color.text));
      pbRow.addView(pbLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
      SwitchMaterial pbSw = new SwitchMaterial(this);
      pbSw.setChecked(md != null && md.allowedInPrivateBrowsing);
      pbRow.addView(pbSw);
      body.addView(pbRow);
      enSw.setOnCheckedChangeListener((b, c) -> { setExtEnabled(installed, c); enSw.setEnabled(false); });
      pbSw.setOnCheckedChangeListener((b, c) -> {
        try {
          runtime.getWebExtensionController().setAllowedInPrivateBrowsing(installed, c).accept(
            done -> runOnUiThread(() -> Ui.toast(this, c ? "Allowed in private browsing" : "Blocked in private browsing")),
            err -> runOnUiThread(() -> Ui.toastLong(this, "Could not change: " + err.getMessage())));
        } catch (Exception e) {
          Ui.toastLong(this, "Could not change: " + e.getMessage());
        }
      });
    }

    AlertDialog.Builder b = Ui.dialog(this).setTitle(name).setView(sv);
    if (installed != null) {
      WebExtension.MetaData md = installed.metaData;
      if (md != null && md.optionsPageUrl != null) {
        b.setPositiveButton("Extension settings", (dd, w) -> newTab(md.optionsPageUrl));
      } else {
        b.setPositiveButton("Close", null);
      }
      b.setNeutralButton("Check for updates", (dd, w) -> {
        Ui.toast(this, "Checking for updates…");
        try {
          runtime.getWebExtensionController().update(installed).accept(
            u -> runOnUiThread(() -> { Ui.toast(this, "Update check finished"); refreshExtensionLists(); }),
            err -> runOnUiThread(() -> Ui.toastLong(this, "Update failed: " + err.getMessage())));
        } catch (Exception e) {
          Ui.toastLong(this, "Update failed: " + e.getMessage());
        }
      });
      b.setNegativeButton("Remove", (dd, w) -> confirmRemoveInstalled(installed, name));
    } else {
      String fileUrl = !d.fileUrl.isEmpty() ? d.fileUrl : (basic != null ? basic.fileUrl : "");
      if (!fileUrl.isEmpty()) {
        b.setPositiveButton("Add to DevxyzBrowser", (dd, w) -> {
          Ui.toast(this, "Installing " + name + "…");
          try {
            runtime.getWebExtensionController().install(fileUrl).accept(
              ext -> runOnUiThread(() -> {
                Ui.toastLong(this, "Installed: " + extName(ext));
                if (basic != null) {
                  for (RecExt rec : RECOMMENDED) {
                    if (rec.slug.equalsIgnoreCase(basic.slug)) recInstalled.add(rec.slug);
                  }
                  prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
                }
                refreshExtensionLists();
                renderAmoResults();
              }),
              err -> runOnUiThread(() ->
                Ui.toastLong(this, "Install failed: " + (err == null ? "unknown error" : err.getMessage()))));
          } catch (Exception e) {
            Ui.toastLong(this, "Install failed: " + e.getMessage());
          }
        });
        b.setNegativeButton("Close", null);
      } else {
        b.setPositiveButton("Close", null);
      }
    }
    b.show();
  }

  private void confirmRemoveInstalled(WebExtension ext, String name) {
    Ui.dialog(this).setTitle(name)
      .setMessage("Remove this extension from DevxyzBrowser?")
      .setPositiveButton("Remove", (d2, w2) ->
        runtime.getWebExtensionController().uninstall(ext).accept(
          done -> {
            for (RecExt rec : RECOMMENDED) {
              if (rec.name.equalsIgnoreCase(name)) recInstalled.remove(rec.slug);
            }
            prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
            runOnUiThread(() -> { Ui.toast(this, "Removed: " + name); refreshExtensionLists(); });
          },
          err -> runOnUiThread(() ->
            Ui.toastLong(this, "Could not remove: " + err.getMessage()))))
      .setNegativeButton("Cancel", null).show();
  }

  private static String formatCount(long n) {
    if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
    return String.valueOf(n);
  }

  /** Tap on an installed extension: rich AMO details when available, else local details. */
  private void showExtDetailsRich(WebExtension ext) {
    String name = extName(ext);
    // Show immediate local details, then enrich with AMO data.
    findAmoSlug(ext, slug -> {
      if (slug == null) {
        showExtDetails(ext); // offline or not on AMO: local details dialog
        return;
      }
      Ui.toast(this, "Loading details…");
      fetchAmoDetail(slug, d -> {
        if (d == null) { showExtDetails(ext); return; }
        showRichAmoDialog(d, ext, null);
      });
    });
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
    boolean enabled = ext.metaData != null && ext.metaData.enabled;
    LinearLayout row = extRowBase(R.drawable.ic_extension_puzzle, name,
        (enabled ? "Enabled" : "Disabled") + (ext.metaData != null && ext.metaData.version != null ? " • v" + ext.metaData.version : ""));
    SwitchMaterial toggle = new SwitchMaterial(this);
    toggle.setChecked(enabled);
    toggle.setContentDescription(name + " enabled");
    toggle.setOnCheckedChangeListener((button, checked) -> setExtEnabled(ext, checked));
    row.addView(toggle, new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    row.setClickable(true);
    row.setFocusable(true);
    // Tapping the row (not the switch) opens Firefox-style extension details.
    row.setOnClickListener(v -> showExtDetailsRich(ext));
    // Keep switch taps from opening details.
    toggle.setOnTouchListener((v, e) -> { v.getParent().requestDisallowInterceptTouchEvent(true); return false; });
    return row;
  }

  private void setExtEnabled(WebExtension ext, boolean enabled) {
    try {
      (enabled
        ? runtime.getWebExtensionController().enable(ext, WebExtensionController.EnableSource.USER)
        : runtime.getWebExtensionController().disable(ext, WebExtensionController.EnableSource.USER)
      ).accept(
        done -> runOnUiThread(() -> {
          Ui.toast(this, extName(ext) + (enabled ? " enabled" : " disabled"));
          refreshExtensionLists();
        }),
        err -> runOnUiThread(() -> {
          Ui.toastLong(this, "Could not " + (enabled ? "enable" : "disable") + ": " + err.getMessage());
          refreshExtensionLists();
        }));
    } catch (Exception e) {
      Ui.toastLong(this, "Could not " + (enabled ? "enable" : "disable") + ": " + e.getMessage());
      refreshExtensionLists();
    }
  }

  /** Firefox-style extension details: description, author, version, options, permissions, remove. */
  private void showExtDetails(WebExtension ext) {
    String name = extName(ext);
    WebExtension.MetaData md = ext.metaData;
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(4);
    body.setPadding(pad, pad, pad, pad);

    if (md != null) {
      if (md.description != null && !md.description.isEmpty()) {
        TextView desc = new TextView(this);
        desc.setText(md.description);
        desc.setTextSize(13);
        desc.setTextColor(getColor(R.color.text));
        body.addView(desc);
      }
      StringBuilder meta = new StringBuilder();
      if (md.version != null) meta.append("Version ").append(md.version).append("\n");
      if (md.creatorName != null && !md.creatorName.isEmpty()) meta.append("By ").append(md.creatorName).append("\n");
      if (meta.length() > 0) {
        TextView mv = new TextView(this);
        mv.setText(meta.toString().trim());
        mv.setTextSize(12);
        mv.setTextColor(getColor(R.color.muted));
        mv.setPadding(0, dp(8), 0, 0);
        body.addView(mv);
      }
    }

    // Enable / disable row
    LinearLayout enRow = new LinearLayout(this);
    enRow.setOrientation(LinearLayout.HORIZONTAL);
    enRow.setGravity(Gravity.CENTER_VERTICAL);
    enRow.setPadding(0, dp(12), 0, dp(4));
    TextView enLabel = new TextView(this);
    enLabel.setText("Enabled");
    enLabel.setTextSize(14);
    enLabel.setTextColor(getColor(R.color.text));
    enRow.addView(enLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    SwitchMaterial enSw = new SwitchMaterial(this);
    enSw.setChecked(md == null || md.enabled);
    enRow.addView(enSw);
    body.addView(enRow);

    // Allow in private browsing row
    LinearLayout pbRow = new LinearLayout(this);
    pbRow.setOrientation(LinearLayout.HORIZONTAL);
    pbRow.setGravity(Gravity.CENTER_VERTICAL);
    pbRow.setPadding(0, dp(4), 0, dp(4));
    TextView pbLabel = new TextView(this);
    pbLabel.setText("Allow in private browsing");
    pbLabel.setTextSize(14);
    pbLabel.setTextColor(getColor(R.color.text));
    pbRow.addView(pbLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    SwitchMaterial pbSw = new SwitchMaterial(this);
    pbSw.setChecked(md != null && md.allowedInPrivateBrowsing);
    pbRow.addView(pbSw);
    body.addView(pbRow);

    final boolean[] dirty = {false};
    enSw.setOnCheckedChangeListener((b, c) -> { setExtEnabled(ext, c); dirty[0] = true; enSw.setEnabled(false); });
    pbSw.setOnCheckedChangeListener((b, c) -> {
      try {
        runtime.getWebExtensionController().setAllowedInPrivateBrowsing(ext, c).accept(
          done -> runOnUiThread(() -> Ui.toast(this, c ? "Allowed in private browsing" : "Blocked in private browsing")),
          err -> runOnUiThread(() -> Ui.toastLong(this, "Could not change: " + err.getMessage())));
      } catch (Exception e) {
        Ui.toastLong(this, "Could not change: " + e.getMessage());
      }
    });

    // Permissions
    if (md != null && md.requiredPermissions != null && md.requiredPermissions.length > 0) {
      TextView ph = new TextView(this);
      ph.setText("Permissions");
      ph.setTextSize(12);
      ph.setTextColor(getColor(R.color.muted));
      ph.setPadding(0, dp(12), 0, dp(4));
      body.addView(ph);
      TextView pl = new TextView(this);
      pl.setText("• " + String.join("\n• ", md.requiredPermissions));
      pl.setTextSize(12);
      pl.setTextColor(getColor(R.color.text));
      body.addView(pl);
    }

    Ui.dialog(this).setTitle(name)
      .setView(body)
      .setPositiveButton(md != null && md.optionsPageUrl != null ? "Extension settings" : "Close",
        (d, w) -> {
          if (md != null && md.optionsPageUrl != null) newTab(md.optionsPageUrl);
          if (dirty[0]) refreshExtensionLists();
        })
      .setNeutralButton("Check for updates", (d, w) -> {
        try {
          Ui.toast(this, "Checking for updates…");
          runtime.getWebExtensionController().update(ext).accept(
            u -> runOnUiThread(() -> { Ui.toast(this, "Update check finished"); refreshExtensionLists(); }),
            err -> runOnUiThread(() -> Ui.toastLong(this, "Update failed: " + err.getMessage())));
        } catch (Exception e) {
          Ui.toastLong(this, "Update failed: " + e.getMessage());
        }
      })
      .setNegativeButton("Remove", (d, w) ->
        Ui.dialog(this).setTitle(name)
          .setMessage("Remove this extension from DevxyzBrowser?")
          .setPositiveButton("Remove", (d2, w2) ->
            runtime.getWebExtensionController().uninstall(ext).accept(
              done -> {
                for (RecExt rec : RECOMMENDED) {
                  if (rec.name.equalsIgnoreCase(name)) recInstalled.remove(rec.slug);
                }
                prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
                runOnUiThread(() -> { Ui.toast(this, "Removed: " + name); refreshExtensionLists(); });
              },
              err -> runOnUiThread(() ->
                Ui.toastLong(this, "Could not remove: " + err.getMessage()))))
          .setNegativeButton("Cancel", null).show())
      .show();
  }

  private View recommendedExtRow(RecExt rec, boolean installed) {
    LinearLayout row = extRowBase(rec.icon, rec.name, rec.desc);
    SwitchMaterial toggle = new SwitchMaterial(this);
    toggle.setChecked(installed && !rec.searchOnly);
    toggle.setContentDescription(rec.name);
    if (rec.searchOnly) {
      android.view.View.OnClickListener openSearch = v -> {
        newTab("https://addons.mozilla.org/en-US/firefox/search/?q=" + rec.slug);
        Ui.toastLong(this, "Find " + rec.name + " on addons.mozilla.org");
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
    // Tap the row for rich AMO details (like the Firefox listing page).
    if (!rec.searchOnly) {
      row.setClickable(true);
      row.setFocusable(true);
      row.setOnClickListener(v -> {
        Ui.toast(this, "Loading details…");
        AmoExt basic = new AmoExt();
        basic.slug = rec.slug;
        basic.name = rec.name;
        fetchAmoDetail(rec.slug, d -> {
          if (d == null) { Ui.toastLong(this, "Could not load details"); return; }
          showRichAmoDialog(d, findInstalledByName(rec.name), basic);
        });
      });
    }
    return row;
  }

  private WebExtension findInstalledByName(String name) {
    for (WebExtension ext : installedExts) {
      if (extName(ext).equalsIgnoreCase(name)) return ext;
    }
    return null;
  }

  private View emptyExtRow(String text) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(12);
    view.setTextColor(getColor(R.color.muted));
    view.setPadding(0, dp(8), 0, dp(8));
    return view;
  }

  void installRecommended(RecExt rec) {
    String url = "https://addons.mozilla.org/firefox/downloads/latest/" + rec.slug + "/latest.xpi";
    Ui.toast(this, "Installing " + rec.name + "…");
    runtime.getWebExtensionController().install(url).accept(
      ext -> {
        recInstalled.add(rec.slug);
        prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
        runOnUiThread(() -> {
          Ui.toastLong(this, "Installed: " + extName(ext));
          refreshExtensionLists();
        });
      },
      err -> runOnUiThread(() -> {
        Ui.toastLong(this, "Install failed: " + (err == null ? "unknown error" : err.getMessage()));
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
    Ui.dialog(this).setTitle(rec.name)
      .setMessage("Remove this extension from DevxyzBrowser?")
      .setPositiveButton("Remove", (d, w) -> runtime.getWebExtensionController().uninstall(found).accept(
        done -> {
          recInstalled.remove(rec.slug);
          prefs.edit().putStringSet("rec_exts", new HashSet<>(recInstalled)).apply();
          runOnUiThread(() -> {
            Ui.toast(this, "Removed: " + rec.name);
            refreshExtensionLists();
          });
        },
        err -> runOnUiThread(() ->
          Ui.toastLong(this, "Could not remove: " + err.getMessage()))))
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
        Ui.dialog(this).setTitle(tile.name)
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
    Ui.dialog(this).setTitle("Add site").setView(fields)
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
    Ui.dialog(this).setTitle("Local profile")
      .setMessage(message).setPositiveButton("OK", null).show();
  }

  void showHome() {
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

  void newTab() {
    try {
      GeckoSession newSession = new GeckoSession();
      newSession.open(runtime);
      if (prefs.getBoolean("desktop_site", false)) {
        try {
          newSession.getSettings().setUserAgentOverride(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        } catch (Exception ignored) {}
      }
      BrowserTab tab = new BrowserTab(newSession);
      tabs.add(tab);
      attachSession(tab);
      addTabChip(tab);
      selectTab(tab);
    } catch (Exception e) {
      Ui.toastLong(this, "Could not open new tab: " + e.getMessage());
    }
  }

  private void newTab(String url) {
    newTab();
    load(url);
  }

  private void newIncognitoTab() {
    try {
      // Private mode is per-session in GeckoView (no separate runtime needed).
      GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
          .usePrivateMode(true)
          .build();
      GeckoSession newSession = new GeckoSession(settings);
      newSession.open(runtime);
      BrowserTab tab = new BrowserTab(newSession);
      tab.incognito = true;
      tabs.add(tab);
      attachSession(tab);
      addTabChip(tab);
      selectTab(tab);
      Ui.toast(this, "Incognito tab opened");
    } catch (Exception e) {
      Ui.toastLong(this, "Could not open incognito tab: " + e.getMessage());
    }
  }

  void applyShieldsToSessions() {
    boolean block = Shields.blockScripts(prefs);
    for (BrowserTab t : tabs) {
      try { t.session.getSettings().setAllowJavascript(!block); } catch (Exception ignored) { }
    }
  }

  private void attachSession(BrowserTab tab) {
    // Block scripts applies immediately, per session.
    try { tab.session.getSettings().setAllowJavascript(!Shields.blockScripts(prefs)); }
    catch (Exception ignored) { }
    tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
      @Override public void onPageStart(GeckoSession s, String url) {
        tab.url = url;
        if (tab == activeTab) {
          address.setText(url); progress.setVisibility(View.VISIBLE); progress.setProgress(5);
        }
        updateTabLabel(tab);
        // Incognito tabs never touch history.
        if (!tab.incognito && prefs.getBoolean("remember_history", true) && !historyItems.contains(url)) historyItems.add(0, url);
      }
      @Override public void onProgressChange(GeckoSession s, int p) {
        if (tab == activeTab && p > progress.getProgress()) progress.setProgress(Math.min(p, 100));
      }
      @Override public void onPageStop(GeckoSession s, boolean ok) {
        if (tab == activeTab) {
          progress.setProgress(100);
          progress.postDelayed(() -> { progress.setProgress(0); progress.setVisibility(View.GONE); }, 350);
        }
        // TV: auto-fullscreen videos
        if (VideoFullscreen.isTv(MainActivity.this)) {
          s.loadUri("javascript:" + VideoFullscreen.autoFullscreenJs());
        }
      }
    });
    tab.session.setContentDelegate(new GeckoSession.ContentDelegate() {
      @Override public void onFullScreen(GeckoSession s, boolean fullScreen) {
        runOnUiThread(() -> VideoFullscreen.set(MainActivity.this, fullScreen));
      }
      @Override public void onExternalResponse(GeckoSession s, WebResponse response) {
        String url = response.uri;
        String name = null;
        try {
          if (response.headers != null) {
            String disp = response.headers.get("Content-Disposition");
            if (disp == null) disp = response.headers.get("content-disposition");
            if (disp != null) {
              java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename=\"?([^\";]+)\"?").matcher(disp);
              if (m.find()) name = m.group(1).trim();
            }
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

  @Override public void onBackPressed() {
    if (VideoFullscreen.isFullscreen()) { session.exitFullScreen(); return; }
    super.onBackPressed();
  }

  private void selectTab(BrowserTab tab) {
    activeTab = tab;
    session = tab.session;
    geckoView.setSession(session);
    startPage.setVisibility(tab.url.isEmpty() ? View.VISIBLE : View.GONE);
    address.setText(tab.url.isEmpty() ? "" : tab.url);
    // Incognito indicator: show 🕶 in the tab count badge when in a private tab.
    TextView tc = findViewById(R.id.tabCount);
    if (tc != null) tc.setText(tab.incognito ? "🕶" : String.valueOf(tabs.size()));
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
    // Remember for "Recent tabs" (skip incognito and blank tabs).
    if (!tab.incognito && tab.url != null && !tab.url.isEmpty() && !tab.url.startsWith("about:")) {
      recentlyClosed.add(0, tab.url);
      while (recentlyClosed.size() > 25) recentlyClosed.remove(recentlyClosed.size() - 1);
    }
    tabs.remove(index);
    if (tab.chip != null && tabStrip != null) tabStrip.removeView(tab.chip);
    try { tab.session.close(); } catch (Exception ignored) {}
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

  void showTabs() {
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
      bg.setColor(tab == activeTab ? getColor(R.color.panel2) : Color.TRANSPARENT);
      bg.setCornerRadius(dp(12));
      if (tab == activeTab) bg.setStroke(dp(1), getColor(R.color.purple));
      row.setBackground(bg);

      TextView label = new TextView(this);
      String title = tab.url.isEmpty() ? "New Tab" : Uri.parse(tab.url).getHost();
      if (title == null || title.isEmpty()) title = "Page";
      if (tab.incognito) title = "🕶 " + title;
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

  void showChromeMenu(View anchor) {
    // Chrome-style popup menu with icons, anchored top-right like Chrome.
    // Uses PopupWindow (like real Chrome) instead of Dialog for stability.
    try {
      View root = getLayoutInflater().inflate(R.layout.panel_menu, null);
      final android.widget.PopupWindow popup = new android.widget.PopupWindow(
          root, dp(260), android.view.WindowManager.LayoutParams.WRAP_CONTENT, true);
      popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
      popup.setOutsideTouchable(true);

      // Dismiss helper that safely runs the action after popup closes.
      final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
      java.util.function.Consumer<Runnable> runAfterDismiss = action -> {
        try { popup.dismiss(); } catch (Exception ignored) {}
        handler.post(() -> {
          try { action.run(); } catch (Exception e) {
            Ui.toast(this, "Action failed: " + e.getMessage());
          }
        });
      };

      // Safe click setter: ignores missing views instead of crashing.
      java.util.function.BiConsumer<Integer, Runnable> onMenuClick = (id, action) -> {
        View v = root.findViewById(id);
        if (v != null) v.setOnClickListener(x -> runAfterDismiss.accept(action));
      };

      // "Customize menu" (Appearance): hide items the user turned off.
      String[] menuKeys = {"newtab", "incognito", "history", "cleardata", "site",
          "downloads", "bookmarks", "recent", "share", "find", "translate",
          "desktop", "extensions", "vpn", "help"};
      int[] menuIds = {R.id.m_newtab, R.id.m_incognito, R.id.m_history, R.id.m_cleardata,
          R.id.m_site, R.id.m_downloads, R.id.m_bookmarks, R.id.m_recent, R.id.m_share,
          R.id.m_find, R.id.m_translate, R.id.m_desktop, R.id.m_extensions, R.id.m_vpn, R.id.m_help};
      for (int i = 0; i < menuIds.length; i++) {
        View mv = root.findViewById(menuIds[i]);
        if (mv != null && !Appearance.menuVisible(prefs, menuKeys[i])) mv.setVisibility(View.GONE);
      }

      // Top circular action row
      onMenuClick.accept(R.id.m_back, () -> { if (session != null) session.goBack(); });
      onMenuClick.accept(R.id.m_forward, () -> { if (session != null) session.goForward(); });
      onMenuClick.accept(R.id.m_bookmark, this::bookmarkCurrent);
      onMenuClick.accept(R.id.m_download, this::showDownloads);
      onMenuClick.accept(R.id.m_refresh, () -> { if (session != null) session.reload(); });

      // Menu items
      onMenuClick.accept(R.id.m_newtab, this::newTab);
      onMenuClick.accept(R.id.m_incognito, this::newIncognitoTab);
      onMenuClick.accept(R.id.m_history, () -> showList("History", historyItems));
      onMenuClick.accept(R.id.m_cleardata, this::confirmClearData);
      onMenuClick.accept(R.id.m_site, this::showSiteControls);
      onMenuClick.accept(R.id.m_downloads, this::showDownloads);
      onMenuClick.accept(R.id.m_bookmarks, this::showBookmarks);
      onMenuClick.accept(R.id.m_recent, this::showRecentTabs);
      onMenuClick.accept(R.id.m_share, this::sharePage);
      onMenuClick.accept(R.id.m_find, this::showFindInPage);
      onMenuClick.accept(R.id.m_translate, this::translatePage);
      android.widget.CheckBox desktopCheck = root.findViewById(R.id.m_desktop_check);
      if (desktopCheck != null) desktopCheck.setChecked(prefs.getBoolean("desktop_site", false));
      onMenuClick.accept(R.id.m_desktop, () -> {
        boolean on = !prefs.getBoolean("desktop_site", false);
        prefs.edit().putBoolean("desktop_site", on).apply();
        applyDesktopSite(on);
      });
      onMenuClick.accept(R.id.m_extensions, this::showExtensions);
      onMenuClick.accept(R.id.m_ai, () -> startActivity(new Intent(this, AiChatActivity.class)));
      onMenuClick.accept(R.id.m_vpn, this::showVpn);
      onMenuClick.accept(R.id.m_settings, this::showSettings);
      onMenuClick.accept(R.id.m_help, this::showHelp);

      // Show anchored to the top-right, below the toolbar.
      popup.showAsDropDown(anchor, -dp(260) + anchor.getWidth(), dp(8));
    } catch (Exception e) {
      Ui.toast(this, "Menu failed: " + e.getMessage());
    }
  }

  private void bookmarkCurrent() {
    String current = address.getText().toString().trim();
    if (current.startsWith("http") && !bookmarks.contains(current)) {
      bookmarks.add(0, current);
      Ui.toast(this, "Bookmarked");
    } else if (current.startsWith("http")) {
      Ui.toast(this, "Already bookmarked");
    } else {
      Ui.toast(this, "Open a page first to bookmark it");
    }
  }

  private void confirmClearData() {
    Ui.dialog(this).setTitle("Delete browsing data")
        .setMessage("Clear browsing history?")
        .setPositiveButton("Delete", (d, which) -> {
          historyItems.clear();
          Ui.toast(this, "Browsing data deleted");
        })
        .setNegativeButton("Cancel", null).show();
  }

  // ---- Recently closed tabs ----
  private final ArrayList<String> recentlyClosed = new ArrayList<>();

  private void showRecentTabs() {
    if (recentlyClosed.isEmpty()) {
      showInfo("Recent tabs", "No recently closed tabs.");
      return;
    }
    showList("Recent tabs", recentlyClosed);
  }

  // ---- Share ----
  void sharePage() {
    String url = activeTab != null ? activeTab.url : "";
    if (url == null || url.isEmpty() || !url.startsWith("http")) {
      Ui.toast(this, "Open a page first to share it");
      return;
    }
    android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
    share.setType("text/plain");
    share.putExtra(android.content.Intent.EXTRA_TEXT, url);
    startActivity(android.content.Intent.createChooser(share, "Share via"));
  }

  // ---- Find in page ----
  private void showFindInPage() {
    if (activeTab == null || activeTab.url.isEmpty()) {
      Ui.toast(this, "Open a page first");
      return;
    }
    AlertDialog.Builder b = Ui.dialog(this);
    b.setTitle("Find in page");
    final android.widget.EditText input = new android.widget.EditText(this);
    input.setHint("Search text");
    input.setSingleLine(true);
    int pad = dp(16);
    input.setPadding(pad, pad, pad, pad);
    b.setView(input);
    b.setPositiveButton("Find", (d, which) -> {
      String q = input.getText().toString();
      if (!q.isEmpty()) {
        try {
          session.getFinder().find(q, 0).then(
            result -> { return null; },
            e -> { Ui.toast(this, "Find failed"); return null; });
        } catch (Exception e) {
          Ui.toast(this, "Find not available");
        }
      }
    });
    b.setNegativeButton("Cancel", null);
    b.show();
  }

  // ---- Translate ----
  private void translatePage() {
    String url = activeTab != null ? activeTab.url : "";
    if (url == null || url.isEmpty() || !url.startsWith("http")) {
      Ui.toast(this, "Open a page first to translate it");
      return;
    }
    try {
      String t = "https://translate.google.com/translate?sl=auto&tl=tl&u=" +
          java.net.URLEncoder.encode(url, "UTF-8");
      newTab(t);
    } catch (Exception e) {
      Ui.toast(this, "Could not open Translate");
    }
  }

  // ---- Desktop site ----
  private void applyDesktopSite(boolean on) {
    try {
      String ua = on
        ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        : null;
      // Apply to current session via settings; GeckoView picks it up on reload.
      if (session != null) {
        session.getSettings().setUserAgentOverride(ua);
        session.reload();
      }
      Ui.toast(this, on ? "Desktop site ON" : "Desktop site OFF");
    } catch (Exception e) {
      Ui.toast(this, "Desktop site not available");
    }
  }

  // ---- Site controls ----
  private void showSiteControls() {
    String url = activeTab != null ? activeTab.url : "";
    if (url == null || url.isEmpty()) url = "(new tab)";
    boolean secure = url.startsWith("https://");
    String host;
    try { host = Uri.parse(url).getHost(); } catch (Exception e) { host = null; }
    if (host == null) host = url;
    showInfo("Site controls",
        "Site: " + host + "\nConnection: " + (secure ? "Secure (HTTPS)" : "Not secure") +
        "\n\nTip: use Settings → Privacy for tracking protection.");
  }

  // ---- Help & feedback ----
  private void showHelp() {
    showInfo("Help & feedback",
        "DevxyzBrowser • GeckoView 156\nFast. Private. Flexible.\n\n" +
        "Tips:\n• Tap ⋮ for all browser actions\n• Tap the tab count to switch tabs\n• Long-press the address bar to paste\n\n" +
        "Made by JepongDevxyz.");
  }

  // ---- Download manager ----
  static final class DownloadItem {
    String name, url, time; long id;
    DownloadItem(String name, String url, String time, long id) {
      this.name = name; this.url = url; this.time = time; this.id = id;
    }
  }
  final ArrayList<DownloadItem> downloads = new ArrayList<>();
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

  void saveDownloads() {
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
        settingsScreens.enqueueDownload(url, filename);
      } catch (Exception e) {
        Ui.toastLong(this, "Download failed: " + e.getMessage());
      }
    });
  }

  String pendingDownloadUrl, pendingDownloadName;

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    super.onRequestPermissionsResult(code, perms, results);
    if (code == 9001 && results.length > 0 &&
        results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        pendingDownloadUrl != null) {
      settingsScreens.enqueueDownload(pendingDownloadUrl, pendingDownloadName);
      pendingDownloadUrl = null;
    }
  }

  /** Opens a downloaded file with the best matching app. */

  void showDownloads() {
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

  void refreshDownloadsSheet() {
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

  BottomSheetDialog settingsDialog;

  private void showSettings() {
    if (isDesktop) {
      showInfo("Settings", "DevxyzBrowser • GeckoView 156\nFirefox-compatible settings are available on the mobile layout.");
      return;
    }
    if (settingsDialog == null) {
      View root = getLayoutInflater().inflate(R.layout.panel_settings, null);
      settingsDialog = makeSheet(root);
      settingsScreens.initSettingsPanel((LinearLayout) root);
    } else {
      refreshSettingsPanel();
    }
    settingsDialog.show();
  }

  /** "Customize menu": toggle each ⋮ menu item on/off. */

  void refreshSettingsPanel() {
    if (settingsDialog == null) return;
    TextView se = settingsDialog.findViewById(R.id.set_search_engine_val);
    TextView hp = settingsDialog.findViewById(R.id.set_homepage_val);
    TextView shields = settingsDialog.findViewById(R.id.set_shields_val);
    TextView db = settingsDialog.findViewById(R.id.set_default_browser_val);
    TextView appearance = settingsDialog.findViewById(R.id.set_appearance_val);
    if (se != null) se.setText(prefs.getString("search_engine", "Google"));
    if (hp != null) hp.setText(prefs.getString("homepage", "Devxyz Home"));
    if (appearance != null) appearance.setText("Theme: " + Appearance.themeLabel(prefs));
    if (shields != null) {
      String[] labels = {"Allow all", "Standard", "Aggressive"};
      int m = Shields.mode(prefs);
      String extra = "";
      if (Shields.blockScripts(prefs)) extra += " • No scripts";
      shields.setText(labels[m] + extra);
    }
    if (db != null) db.setText(settingsScreens.isDefaultBrowser() ? "DevxyzBrowser is default" : "Not set");
  }

  /** True when DevxyzBrowser is the system's default browser (RoleManager, API 29+). */

  static final int REQ_DEFAULT_BROWSER = 1401;

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_DEFAULT_BROWSER) {
      refreshSettingsPanel();
      Ui.toastLong(this, settingsScreens.isDefaultBrowser()
          ? "DevxyzBrowser is now your default browser"
          : "DevxyzBrowser is not the default browser");
    }
  }

  void pickOption(String title, String[] options, String current, java.util.function.Consumer<String> onPick) {
    int checked = 0;
    for (int i = 0; i < options.length; i++) if (options[i].equals(current)) checked = i;
    Ui.dialog(this).setTitle(title)
        .setSingleChoiceItems(options, checked, (d, which) -> {
          onPick.accept(options[which]);
          d.dismiss();
        })
        .setNegativeButton("Cancel", null).show();
  }

  void browse(String raw) {
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

  void showBookmarks() {
    String current = address.getText().toString().trim();
    AlertDialog.Builder b = Ui.dialog(this).setTitle("Bookmarks");
    if (bookmarks.isEmpty()) b.setMessage("No saved pages yet.");
    else b.setItems(bookmarks.toArray(new String[0]), (d, which) -> browse(bookmarks.get(which)));
    if (current.startsWith("http")) b.setPositiveButton("Save current", (d, w) -> { if (!bookmarks.contains(current)) bookmarks.add(0, current); });
    b.setNegativeButton("Close", null).show();
  }

  void showList(String title, ArrayList<String> items) {
    if (items.isEmpty()) { showInfo(title, "Nothing here yet."); return; }
    Ui.dialog(this).setTitle(title).setItems(items.toArray(new String[0]), (d, w) -> browse(items.get(w))).setNegativeButton("Close", null).show();
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
    Ui.dialog(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
  }

  private void requestVpnCredentials() {
    try {
      if (!VpnProfileStore.requiresCredentials(this)) {
        startOpenVpn("", "", "");
        return;
      }
    } catch (Exception e) {
      Ui.toastLong(this, "Could not read VPN profile: " + e.getMessage());
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
    Ui.dialog(this).setTitle("VPN credentials")
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
      Ui.toastLong(this, "Could not start VPN: " + e.getMessage());
    }
    refreshVpnPanel();
  }

  private String safe(String value) { return value == null || value.trim().isEmpty() ? "OpenVPN" : value; }

  @Override protected void onPause() {
    super.onPause();
    // Firefox-style: remember open tabs so they can be restored on startup.
    // Incognito tabs are never saved.
    StringBuilder sb = new StringBuilder();
    for (BrowserTab tab : tabs) {
      if (!tab.incognito && tab.url != null && !tab.url.isEmpty() && !tab.url.startsWith("about:")) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(tab.url);
      }
    }
    prefs.edit().putString("saved_tabs", sb.toString()).apply();
  }

  @Override protected void onDestroy() {
    if (vpnReceiverRegistered) { unregisterReceiver(vpnStateReceiver); vpnReceiverRegistered = false; }
    try { unregisterReceiver(downloadCompleteReceiver); } catch (Exception ignored) { }
    if (prefs.getBoolean("clear_on_quit", false)) {
      historyItems.clear();
      prefs.edit().remove("saved_tabs").apply();
    }
    for (BrowserTab tab : tabs) tab.session.close();
    tabs.clear();
    super.onDestroy();
  }
}
