package com.jepongdevxyz.browser;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.webkit.URLUtil;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
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
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import com.jepongdevxyz.browser.vpn.VpnProfileStore;
import com.jepongdevxyz.browser.vpn.DevxyzVpnService;
import android.content.Intent;
import android.net.VpnService;
import org.mozilla.geckoview.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.widget.PopupMenu;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;

public final class MainActivity extends AppCompatActivity {
  private static GeckoRuntime runtime;
  private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
  private GeckoSession session;
  private EditText address, heroSearch;
  private ProgressBar progress;
  private View startPage;
  private View menuButton;
  private TextView vpnStatus;
  private LinearLayout tabStrip;
  private LinearLayout extensionRows;
  private EditText extensionSearch;
  private List<WebExtension> installedExtensions = Collections.emptyList();
  private org.mozilla.geckoview.GeckoView geckoView;
  private ActivityResultLauncher<String[]> openVpnProfile;
  private ActivityResultLauncher<Intent> vpnPermission;
  private boolean vpnReceiverRegistered;
  private WindowInsetsControllerCompat desktopInsetsController;
  private boolean desktopFullscreen = true;
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
    }
  };
  private final ArrayList<String> historyItems = new ArrayList<>();
  private final ArrayList<String> bookmarks = new ArrayList<>();
  private final ArrayList<BrowserTab> tabs = new ArrayList<>();
  private BrowserTab activeTab;

  private static final class BrowserTab {
    GeckoSession session;
    String url = "";
    String displayTitle;
    LinearLayout chip;
    TextView label;
    BrowserTab(GeckoSession session) { this.session = session; }
  }

  private static final class AddonCard {
    final String name, description, slug, color;
    AddonCard(String name, String description, String slug, String color) {
      this.name = name; this.description = description; this.slug = slug; this.color = color;
    }
    boolean supportedOnAndroid() { return slug != null; }
    String downloadUrl() { return "https://addons.mozilla.org/firefox/downloads/latest/" + slug + "/latest.xpi"; }
  }
  private static final AddonCard[] ADDON_CATALOG = {
    new AddonCard("uBlock Origin", "Block ads and trackers", "ublock-origin", "#B31326"),
    new AddonCard("Dark Reader", "Dark mode for all websites", "darkreader", "#168EAA"),
    new AddonCard("Grammarly", "Write better everywhere", null, "#168F70"),
    new AddonCard("SponsorBlock", "Skip sponsored content", "sponsorblock", "#E73D32"),
    new AddonCard("React Developer Tools", "Debug React apps", null, "#1497B8")
  };

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
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
        if (getPreferences(MODE_PRIVATE).getBoolean("auto_connect_vpn", false)) connectSavedProfileWithPermission();
      } catch (Exception ex) {
        Toast.makeText(this, "Profile rejected: " + ex.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
    geckoView = findViewById(R.id.gecko);
    geckoView.setVisibility(View.GONE);
    activeTab = new BrowserTab(null);
    tabs.add(activeTab);
    address = findViewById(R.id.address);
    heroSearch = findViewById(R.id.heroSearch);
    progress = findViewById(R.id.pageProgress);
    startPage = findViewById(R.id.startPage);
    vpnStatus = findViewById(R.id.vpnStatus);
    tabStrip = findViewById(R.id.tabStrip);
    extensionRows = findViewById(R.id.desktopExtensionRows);
    extensionSearch = findViewById(R.id.extensionSearch);
    menuButton = findViewById(R.id.menu);
    boolean wideLayout = getResources().getConfiguration().screenWidthDp >= 1200;
    if (wideLayout) {
      desktopInsetsController = new WindowInsetsControllerCompat(
        getWindow(), findViewById(android.R.id.content));
      desktopInsetsController.hide(WindowInsetsCompat.Type.systemBars());
      desktopInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      ViewGroup browserRoot = findViewById(R.id.browserRoot);
      browserRoot.setBackgroundResource(R.drawable.browser_frame);
      browserRoot.setClipToOutline(true);
      browserRoot.setClipChildren(false);
      browserRoot.setPadding(dp(1), dp(1), dp(1), dp(1));
      LinearLayout mainBrowserArea = findViewById(R.id.mainBrowserArea);
      mainBrowserArea.setClipChildren(false);
      mainBrowserArea.setClipToPadding(false);
      FrameLayout.LayoutParams browserFrame = new FrameLayout.LayoutParams(-1, -1);
      browserFrame.leftMargin = dp(20);
      browserFrame.rightMargin = dp(20);
      browserRoot.setLayoutParams(browserFrame);
      HorizontalScrollView tabScroller = findViewById(R.id.tabScroller);
      LinearLayout.LayoutParams tabScrollerParams = (LinearLayout.LayoutParams) tabScroller.getLayoutParams();
      tabScrollerParams.width = dp(460);
      tabScrollerParams.weight = 0;
      tabScroller.setLayoutParams(tabScrollerParams);
      TextView newTabButton = findViewById(R.id.newTab);
      ((ViewGroup) newTabButton.getParent()).removeView(newTabButton);
      tabStrip.addView(newTabButton, new LinearLayout.LayoutParams(dp(38), -1));
      findViewById(R.id.tabSpacer).setVisibility(View.VISIBLE);
      findViewById(R.id.windowControls).setVisibility(View.VISIBLE);
      findViewById(R.id.windowMinimize).setOnClickListener(v -> moveTaskToBack(true));
      findViewById(R.id.windowMaximize).setOnClickListener(v -> {
        desktopFullscreen = !desktopFullscreen;
        if (desktopFullscreen) desktopInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        else desktopInsetsController.show(WindowInsetsCompat.Type.systemBars());
      });
      findViewById(R.id.windowClose).setOnClickListener(v -> finish());
      configureDesktopToolbar();
      BrowserTab brandTab = new BrowserTab(null);
      brandTab.displayTitle = "DevxyzBrowser";
      tabs.add(0, brandTab);
      addTabChip(brandTab);
    }
    LinearLayout tabletRail = findViewById(R.id.tabletRail);
    tabletRail.setVisibility(wideLayout ? View.VISIBLE : View.GONE);
    tabletRail.findViewById(R.id.tabletRailBrand).setVisibility(wideLayout ? View.GONE : View.VISIBLE);
    if (wideLayout) tabletRail.setPadding(dp(8), dp(21), dp(12), dp(10));
    findViewById(R.id.desktopPanels).setVisibility(wideLayout ? View.VISIBLE : View.GONE);
    findViewById(R.id.mobileNav).setVisibility(wideLayout ? View.GONE : View.VISIBLE);
    findViewById(R.id.homeBrandLockup).setVisibility(wideLayout ? View.GONE : View.VISIBLE);
    findViewById(R.id.heroTitle).setVisibility(wideLayout ? View.GONE : View.VISIBLE);
    findViewById(R.id.heroSubtitle).setVisibility(wideLayout ? View.GONE : View.VISIBLE);
    findViewById(R.id.desktopHomeTitle).setVisibility(wideLayout ? View.VISIBLE : View.GONE);
    findViewById(R.id.desktopHomeSubtitle).setVisibility(wideLayout ? View.VISIBLE : View.GONE);
    if (!wideLayout) {
      LinearLayout navigationToolbar = findViewById(R.id.navigationToolbar);
      navigationToolbar.setPadding(dp(2), 0, dp(2), 0);
      int[] actionIds = {R.id.back, R.id.forward, R.id.refresh, R.id.vpn,
        R.id.extensions, R.id.toolbarDownloads, R.id.profile, R.id.menu};
      int[] actionWidths = {34, 34, 34, 42, 34, 34, 34, 34};
      for (int index = 0; index < actionIds.length; index++) {
        View action = findViewById(actionIds[index]);
        ViewGroup.LayoutParams params = action.getLayoutParams();
        params.width = dp(actionWidths[index]);
        params.height = dp(40);
        action.setLayoutParams(params);
        if (action instanceof ImageButton) {
          ImageButton button = (ImageButton) action;
          button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
          button.setPadding(dp(7), dp(7), dp(7), dp(7));
        }
      }
      if (getResources().getConfiguration().screenWidthDp < 400) {
        for (int id : new int[]{R.id.vpn, R.id.extensions, R.id.toolbarDownloads, R.id.profile}) {
          findViewById(id).setVisibility(View.GONE);
        }
      }
      EditText addressBar = findViewById(R.id.address);
      addressBar.setPadding(dp(9), 0, dp(9), 0);
      addressBar.setTextSize(12);
    }
    View homeContent = ((android.widget.ScrollView) startPage).getChildAt(0);
    homeContent.setPadding(homeContent.getPaddingLeft(), dp(wideLayout ? 63 : 42), homeContent.getPaddingRight(), homeContent.getPaddingBottom());
    homeContent.setTranslationX(wideLayout ? dp(24) : 0);
    EditText homeSearch = findViewById(R.id.heroSearch);
    ViewGroup.LayoutParams searchParams = homeSearch.getLayoutParams();
    searchParams.width = wideLayout ? dp(544) : ViewGroup.LayoutParams.MATCH_PARENT;
    if (wideLayout) searchParams.height = dp(52);
    homeSearch.setLayoutParams(searchParams);
    int shortcutSize = wideLayout ? 72 : 58;
    for (int id : new int[]{R.id.siteYoutube, R.id.siteFacebook, R.id.siteGithub, R.id.siteReddit, R.id.siteX, R.id.addSite}) {
      android.view.ViewGroup.LayoutParams shortcut = findViewById(id).getLayoutParams();
      shortcut.width = dp(shortcutSize);
      shortcut.height = dp(wideLayout ? 76 : 58);
      findViewById(id).setLayoutParams(shortcut);
    }
    LinearLayout quickSiteStrip = findViewById(R.id.quickSiteStrip);
    int quickSiteCellWidth = dp(wideLayout ? 99 : 60);
    ViewGroup.LayoutParams quickStripParams = quickSiteStrip.getLayoutParams();
    quickStripParams.width = wideLayout ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
    quickSiteStrip.setLayoutParams(quickStripParams);
    quickSiteStrip.setGravity(wideLayout ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
    quickSiteStrip.setTranslationX(wideLayout ? -dp(5) : 0);
    ((android.widget.HorizontalScrollView) findViewById(R.id.quickSites)).setFillViewport(wideLayout);
    for (int index = 0; index < quickSiteStrip.getChildCount(); index++) {
      View cell = quickSiteStrip.getChildAt(index);
      ViewGroup.LayoutParams cellParams = cell.getLayoutParams();
      cellParams.width = quickSiteCellWidth;
      cell.setLayoutParams(cellParams);
    }
    quickSiteStrip.getChildAt(4).setVisibility(wideLayout ? View.VISIBLE : View.GONE);
    addTabChip(activeTab);
    selectTab(activeTab);
    setVpnStatus(DevxyzVpnService.isConnected() ? "Connected • OpenVPN" :
      (VpnProfileStore.hasProfile(this) ? "Ready • profile imported" : "Disconnected • No profile imported"));
    IntentFilter vpnFilter = new IntentFilter(DevxyzVpnService.ACTION_STATE);
    ContextCompat.registerReceiver(this, vpnStateReceiver, vpnFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    vpnReceiverRegistered = true;

    findViewById(R.id.back).setOnClickListener(v -> { if (session != null) session.goBack(); });
    findViewById(R.id.forward).setOnClickListener(v -> { if (session != null) session.goForward(); });
    findViewById(R.id.refresh).setOnClickListener(v -> { if (session != null) session.reload(); });
    findViewById(R.id.newTab).setOnClickListener(v -> newTab());
    menuButton.setOnClickListener(this::showBrowserMenu);
    findViewById(R.id.home).setOnClickListener(v -> showHome());
    findViewById(R.id.bookmarks).setOnClickListener(v -> showBookmarks());
    findViewById(R.id.history).setOnClickListener(v -> showList("History", historyItems));
    findViewById(R.id.downloads).setOnClickListener(v -> showDownloads());
    findViewById(R.id.extensions).setOnClickListener(v -> {
      if (wideLayout) showExtensionsPane(); else showExtensions();
    });
    findViewById(R.id.toolbarDownloads).setOnClickListener(v -> showDownloads());
    findViewById(R.id.profile).setOnClickListener(v -> showSettings());
    findViewById(R.id.extensionsPanel).setOnClickListener(v -> showExtensions());
    findViewById(R.id.vpn).setOnClickListener(v -> showVpn());
    findViewById(R.id.vpnPanel).setOnClickListener(v -> showVpn());
    findViewById(R.id.settings).setOnClickListener(v -> showSettings());
    findViewById(R.id.railHome).setOnClickListener(v -> showHome());
    findViewById(R.id.railBookmarks).setOnClickListener(v -> showBookmarks());
    findViewById(R.id.railHistory).setOnClickListener(v -> showList("History", historyItems));
    findViewById(R.id.railDownloads).setOnClickListener(v -> showDownloads());
    findViewById(R.id.railExtensions).setOnClickListener(v -> showExtensionsPane());
    findViewById(R.id.railVpn).setOnClickListener(v -> showVpn());
    findViewById(R.id.railSettings).setOnClickListener(v -> showSettings());
    findViewById(R.id.desktopVpnStatus).setOnClickListener(v -> showVpn());
    findViewById(R.id.desktopVpnMap).setOnClickListener(v -> handleVpnPower());
    findViewById(R.id.desktopVpnAction).setOnClickListener(v -> showVpn());
    findViewById(R.id.vpnSettings).setOnClickListener(v -> showVpn());
    findViewById(R.id.desktopExtensionsAction).setOnClickListener(v -> browseMoreExtensions());
    findViewById(R.id.extensionsClose).setOnClickListener(v -> findViewById(R.id.desktopExtensionsPane).setVisibility(View.GONE));
    findViewById(R.id.vpnProfileSelector).setOnClickListener(v -> showVpn());
    findViewById(R.id.killSwitchSettings).setOnClickListener(v -> openAndroidVpnSettings());
    setupVpnSwitches();
    extensionSearch.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderExtensionRows(installedExtensions, s.toString()); }
      @Override public void afterTextChanged(Editable s) {}
    });
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
      else if(id==R.id.siteX) browse("https://x.com");
    };
    findViewById(R.id.siteYoutube).setOnClickListener(quick);
    findViewById(R.id.siteFacebook).setOnClickListener(quick);
    findViewById(R.id.siteGithub).setOnClickListener(quick);
    findViewById(R.id.siteReddit).setOnClickListener(quick);
    findViewById(R.id.siteX).setOnClickListener(quick);
    setBrandWordmark(findViewById(R.id.brandTitle));
    setBrandWordmark(findViewById(R.id.desktopHomeTitle));
    setBrandWordmark(findViewById(R.id.railBrandTitle));
    refreshExtensionsSummary();
    maybeAutoConnectSavedProfile();
    if (wideLayout) {
      findViewById(R.id.phoneVpnCard).setVisibility(View.GONE);
      findViewById(R.id.phoneExtensionsCard).setVisibility(View.GONE);
    }

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
        if(startPage.getVisibility()!=View.VISIBLE && activeTab != null && activeTab.session != null && !activeTab.url.isEmpty()) session.goBack();
        else if(startPage.getVisibility()!=View.VISIBLE) showHome();
        else finish();
      }
    });
    showHome();
    if (!wideLayout) showReferenceMobileSplash();
  }

  private void showReferenceMobileSplash() {
    FrameLayout content = findViewById(android.R.id.content);
    FrameLayout splash = new FrameLayout(this);
    splash.setBackground(new GradientDrawable(
      GradientDrawable.Orientation.TOP_BOTTOM,
      new int[] {0xFF030514, 0xFF070923, 0xFF030615}
    ));
    splash.addView(new SplashBackdrop(this), new FrameLayout.LayoutParams(-1, -1));

    ImageView earthHorizon = new ImageView(this);
    earthHorizon.setImageResource(R.drawable.reference_earth_horizon);
    earthHorizon.setScaleType(ImageView.ScaleType.FIT_XY);
    splash.addView(earthHorizon, new FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM));

    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.devxyz_splash_logo);
    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    logo.setContentDescription("DevxyzBrowser fox and globe logo");
    splash.addView(logo, new FrameLayout.LayoutParams(dp(200), dp(210), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

    TextView wordmark = new TextView(this);
    SpannableString brand = new SpannableString("DevxyzBrowser");
    brand.setSpan(new ForegroundColorSpan(0xFF7435F4), 6, brand.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    wordmark.setText(brand);
    wordmark.setTextColor(0xFFF6F5FF);
    wordmark.setTextSize(32);
    wordmark.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
    wordmark.setGravity(Gravity.CENTER);
    splash.addView(wordmark, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    TextView subtitle = new TextView(this);
    subtitle.setText("Browse Freely. Securely. Your Way.");
    subtitle.setTextColor(0xFFB9B9D1);
    subtitle.setTextSize(13);
    subtitle.setGravity(Gravity.CENTER);
    splash.addView(subtitle, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    ProgressBar loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    loading.setMax(100);
    loading.setProgress(56);
    loading.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF7A32F4));
    loading.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF211A62));
    splash.addView(loading, new FrameLayout.LayoutParams(dp(190), dp(6), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

    TextView footer = new TextView(this);
    footer.setText("Built for a more open internet.");
    footer.setTextColor(0xFF9A9AB6);
    footer.setTextSize(10);
    footer.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(-1, dp(22), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    footerParams.bottomMargin = dp(9);
    splash.addView(footer, footerParams);

    splash.post(() -> {
      if (splash.getParent() != content) return;
      int height = splash.getHeight();
      int width = splash.getWidth();
      FrameLayout.LayoutParams logoParams = (FrameLayout.LayoutParams) logo.getLayoutParams();
      logoParams.width = Math.min(dp(164), (int) (width * 0.455f));
      logoParams.height = Math.min(dp(176), (int) (height * 0.22f));
      logoParams.topMargin = (int) (height * 0.225f);
      logo.setLayoutParams(logoParams);

      FrameLayout.LayoutParams wordmarkParams = (FrameLayout.LayoutParams) wordmark.getLayoutParams();
      wordmarkParams.topMargin = (int) (height * 0.535f);
      wordmark.setLayoutParams(wordmarkParams);

      FrameLayout.LayoutParams subtitleParams = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
      subtitleParams.topMargin = (int) (height * 0.592f);
      subtitle.setLayoutParams(subtitleParams);

      FrameLayout.LayoutParams loadingParams = (FrameLayout.LayoutParams) loading.getLayoutParams();
      loadingParams.topMargin = (int) (height * 0.69f);
      loading.setLayoutParams(loadingParams);
    });
    content.addView(splash, new FrameLayout.LayoutParams(-1, -1));

    WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), content);
    controller.hide(WindowInsetsCompat.Type.systemBars());
    controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    splash.setClickable(true);
    final boolean[] dismissed = {false};
    Runnable dismiss = () -> {
      if (dismissed[0]) return;
      dismissed[0] = true;
      if (splash.getParent() == content) content.removeView(splash);
      controller.show(WindowInsetsCompat.Type.systemBars());
    };
    splash.setOnClickListener(v -> dismiss.run());
    long splashDuration = getIntent().getBooleanExtra("visual_test_splash", false) ? 120000L : 2200L;
    splash.postDelayed(dismiss, splashDuration);
  }

  private static final class SplashBackdrop extends View {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    SplashBackdrop(Context context) { super(context); }

    @Override protected void onDraw(android.graphics.Canvas canvas) {
      super.onDraw(canvas);
      float width = getWidth();
      float height = getHeight();

      for (int i = 0; i < 34; i++) {
        float x = ((i * 97) % 997) / 997f * width;
        float y = ((i * 67 + 13) % 691) / 691f * height * 0.68f;
        paint.setColor((i % 4 == 0) ? 0x558DA9FF : 0x337D8CDB);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawCircle(x, y, i % 5 == 0 ? 1.4f : 0.8f, paint);
      }

      paint.setStyle(android.graphics.Paint.Style.FILL);
    }
  }

  private void showHome() {
    if (activeTab != null) {
      activeTab.url = "";
      updateTabLabel(activeTab);
    }
    startPage.setVisibility(View.VISIBLE);
    geckoView.setVisibility(View.GONE);
    address.setText("");
    progress.setVisibility(View.GONE);
    progress.setProgress(0);
  }

  private void newTab() {
    BrowserTab tab = new BrowserTab(null);
    tabs.add(tab);
    addTabChip(tab);
    selectTab(tab);
  }

  private void ensureBrowserRuntime() {
    if (runtime != null) return;
    runtime = GeckoRuntime.create(getApplicationContext());
    runtime.getWebExtensionController().setPromptDelegate(new WebExtensionController.PromptDelegate() {
      @Override public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
          WebExtension extension, String[] permissions, String[] origins, String[] dataPermissions) {
        GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
        runOnUiThread(() -> {
          String detail = "Permissions:\n" + permissionLines(permissions, origins, dataPermissions);
          new AlertDialog.Builder(MainActivity.this).setTitle("Install " + extension.metaData.name + "?")
            .setMessage(detail).setPositiveButton("Allow and install", (d,w) -> result.complete(
              new WebExtension.PermissionPromptResponse(true, false, false)))
            .setNegativeButton("Cancel", (d,w) -> result.complete(
              new WebExtension.PermissionPromptResponse(false, false, false)))
            .setOnCancelListener(d -> result.complete(new WebExtension.PermissionPromptResponse(false, false, false)))
            .show();
        });
        return result;
      }
    });
  }

  private GeckoSession ensureBrowserSession(BrowserTab tab) {
    ensureBrowserRuntime();
    if (tab.session == null) {
      tab.session = newBrowserSession();
      tab.session.open(runtime);
      attachSession(tab);
    }
    if (tab == activeTab) {
      GeckoSession attached = geckoView.getSession();
      if (attached != tab.session) {
        if (attached != null) geckoView.releaseSession();
        geckoView.setSession(tab.session);
      }
      geckoView.setVisibility(View.VISIBLE);
    }
    return tab.session;
  }

  private GeckoSession newBrowserSession() {
    boolean protection = getPreferences(MODE_PRIVATE).getBoolean("block_trackers", true);
    GeckoSessionSettings settings = new GeckoSessionSettings.Builder().useTrackingProtection(protection).build();
    return new GeckoSession(settings);
  }

  private void attachSession(BrowserTab tab) {
    tab.session.setContentDelegate(new GeckoSession.ContentDelegate() {
      @Override public void onExternalResponse(GeckoSession source, WebResponse response) {
        requestDownload(response);
      }
    });
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
    GeckoSession attached = geckoView.getSession();
    if (attached != session) {
      if (attached != null) geckoView.releaseSession();
      if (session != null) geckoView.setSession(session);
    }
    geckoView.setVisibility(session == null ? View.GONE : View.VISIBLE);
    startPage.setVisibility(tab.url.isEmpty() ? View.VISIBLE : View.GONE);
    address.setText(tab.url.isEmpty() ? "" : tab.url);
    progress.setVisibility(View.GONE);
    for (BrowserTab item : tabs) updateTabLabel(item);
  }

  private void configureDesktopToolbar() {
    int[] navigationIds = {R.id.back, R.id.forward, R.id.refresh};
    for (int id : navigationIds) {
      View action = findViewById(id);
      ViewGroup.LayoutParams navParams = action.getLayoutParams();
      navParams.width = dp(42);
      action.setLayoutParams(navParams);
    }
    View vpnAction = findViewById(R.id.vpn);
    ViewGroup.LayoutParams vpnParams = vpnAction.getLayoutParams();
    vpnParams.width = dp(68);
    vpnParams.height = dp(32);
    vpnAction.setLayoutParams(vpnParams);
    for (int id : new int[]{R.id.extensions, R.id.toolbarDownloads, R.id.profile, R.id.menu}) {
      View action = findViewById(id);
      ViewGroup.LayoutParams params = action.getLayoutParams();
      params.width = dp(48);
      action.setLayoutParams(params);
    }
    View extensionsAction = findViewById(R.id.extensions);
    LinearLayout.LayoutParams extensionsParams =
      (LinearLayout.LayoutParams) extensionsAction.getLayoutParams();
    extensionsParams.setMarginStart(dp(8));
    extensionsAction.setLayoutParams(extensionsParams);
    LinearLayout navigationToolbar = findViewById(R.id.navigationToolbar);
    navigationToolbar.setPadding(navigationToolbar.getPaddingLeft(),
      navigationToolbar.getPaddingTop(), 0, navigationToolbar.getPaddingBottom());
    LinearLayout.LayoutParams addressParams = (LinearLayout.LayoutParams) address.getLayoutParams();
    addressParams.height = dp(32);
    addressParams.setMarginStart(dp(10));
    address.setLayoutParams(addressParams);
    address.setTextSize(14);
    address.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
    address.setCompoundDrawablePadding(dp(7));
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
    int tabWidth = getResources().getConfiguration().screenWidthDp >= 1200
      ? ("DevxyzBrowser".equals(tab.displayTitle) ? dp(202) : dp(216))
      : LinearLayout.LayoutParams.WRAP_CONTENT;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tabWidth, dp(38));
    int rightMargin = "DevxyzBrowser".equals(tab.displayTitle) ? 0 : 5;
    lp.setMargins(3, 0, rightMargin, 0);
    int index = tabStrip.getChildCount();
    if (findViewById(R.id.newTab).getParent() == tabStrip) index--;
    tabStrip.addView(chip, Math.max(0, index), lp);
    chip.setOnClickListener(v -> selectTab(tab));
  }

  private void updateTabLabel(BrowserTab tab) {
    if (tab.chip == null) return;
    String title = tab.displayTitle == null ? (tab.url.isEmpty() ? "New Tab" : Uri.parse(tab.url).getHost()) : tab.displayTitle;
    if (title == null || title.isEmpty()) title = "Page";
    tab.label.setText((tab.displayTitle != null ? "" : (tab == activeTab ? "●  " : "◉  ")) + title);
    if ("DevxyzBrowser".equals(tab.displayTitle) && tab.chip.getChildCount() > 0
        && !(tab.chip.getChildAt(0) instanceof ImageView)) {
      ImageView brandIcon = new ImageView(this);
      brandIcon.setImageResource(R.drawable.devxyz_brandmark);
      brandIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
      int iconSize = dp(22);
      LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
      iconParams.setMargins(dp(8), 0, dp(4), 0);
      tab.chip.addView(brandIcon, 0, iconParams);
      tab.label.setPadding(dp(7), 0, 4, 0);
    }
    tab.chip.setContentDescription(title + ". Long press to close tab.");
  }

  private void closeTab(BrowserTab tab) {
    int index = tabs.indexOf(tab);
    if (index < 0) return;
    tabs.remove(index);
    tabStrip.removeView(tab.chip);
    if (tab.session != null) tab.session.close();
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

  private void requestDownload(WebResponse response) {
    if (response == null || response.uri == null) return;
    String contentType = response.headers == null ? null : response.headers.get("Content-Type");
    String disposition = response.headers == null ? null : response.headers.get("Content-Disposition");
    String fileName = URLUtil.guessFileName(response.uri, disposition, contentType);
    fileName = new File(fileName == null ? "download" : fileName).getName();
    final String downloadName = fileName;
    new AlertDialog.Builder(this).setTitle("Download file?")
      .setMessage(downloadName + (contentType == null ? "" : "\n" + contentType))
      .setPositiveButton("Download", (dialog, which) -> saveDownload(response, downloadName, contentType))
      .setNegativeButton("Cancel", null).show();
  }

  private void saveDownload(WebResponse response, String fileName, String contentType) {
    if (response.body == null) {
      try {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(response.uri))
          .setTitle(fileName).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if (contentType != null) request.setMimeType(contentType);
        manager.enqueue(request);
        Toast.makeText(this, "Download started: " + fileName, Toast.LENGTH_LONG).show();
      } catch (Exception error) {
        Toast.makeText(this, "Could not start download: " + error.getMessage(), Toast.LENGTH_LONG).show();
      }
      return;
    }
    DOWNLOAD_EXECUTOR.execute(() -> {
      Uri saved = null;
      try (InputStream input = response.body) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
          ContentValues values = new ContentValues();
          values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
          values.put(MediaStore.Downloads.MIME_TYPE, contentType == null ? "application/octet-stream" : contentType);
          values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DevxyzBrowser");
          values.put(MediaStore.Downloads.IS_PENDING, 1);
          saved = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
          if (saved == null) throw new IOException("Could not create the Downloads file");
          try (OutputStream output = getContentResolver().openOutputStream(saved)) {
            if (output == null) throw new IOException("Could not open the Downloads file");
            copyStream(input, output);
          }
          ContentValues complete = new ContentValues();
          complete.put(MediaStore.Downloads.IS_PENDING, 0);
          getContentResolver().update(saved, complete, null, null);
        } else {
          File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
          if (directory == null || (!directory.exists() && !directory.mkdirs())) throw new IOException("Downloads folder unavailable");
          File destination = uniqueDownloadFile(directory, fileName);
          try (OutputStream output = new FileOutputStream(destination)) { copyStream(input, output); }
          saved = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", destination);
        }
        rememberDownload(saved);
        runOnUiThread(() -> Toast.makeText(this, "Downloaded: " + fileName, Toast.LENGTH_LONG).show());
      } catch (Exception error) {
        if (saved != null && android.os.Build.VERSION.SDK_INT >= 29) getContentResolver().delete(saved, null, null);
        runOnUiThread(() -> Toast.makeText(this, "Download failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
      }
    });
  }

  private void copyStream(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[32768];
    int count;
    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
  }

  private File uniqueDownloadFile(File directory, String name) {
    File target = new File(directory, name);
    if (!target.exists()) return target;
    String base = name, extension = "";
    int dot = name.lastIndexOf('.');
    if (dot > 0) { base = name.substring(0, dot); extension = name.substring(dot); }
    for (int suffix = 1; suffix < 10000; suffix++) {
      target = new File(directory, base + " (" + suffix + ")" + extension);
      if (!target.exists()) return target;
    }
    return new File(directory, System.currentTimeMillis() + "-" + name);
  }

  private void rememberDownload(Uri uri) {
    Set<String> current = getPreferences(MODE_PRIVATE).getStringSet("download_uris", Collections.emptySet());
    Set<String> updated = new LinkedHashSet<>(current);
    updated.add(uri.toString());
    getPreferences(MODE_PRIVATE).edit().putStringSet("download_uris", updated).apply();
  }

  private void showDownloads() {
    Set<String> stored = getPreferences(MODE_PRIVATE).getStringSet("download_uris", Collections.emptySet());
    ArrayList<Uri> files = new ArrayList<>();
    for (String value : stored) try { files.add(Uri.parse(value)); } catch (Exception ignored) { }
    Collections.reverse(files);
    if (files.isEmpty()) {
      new AlertDialog.Builder(this).setTitle("Downloads").setMessage("No downloaded files yet.")
        .setPositiveButton("OK", null).show();
      return;
    }
    String[] labels = new String[files.size()];
    for (int index = 0; index < files.size(); index++) labels[index] = downloadDisplayName(files.get(index));
    new AlertDialog.Builder(this).setTitle("Downloads").setItems(labels, (dialog, which) -> openDownloadedFile(files.get(which)))
      .setNegativeButton("Close", null).show();
  }

  private String downloadDisplayName(Uri uri) {
    if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
    try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
    } catch (Exception ignored) { }
    return uri.getLastPathSegment() == null ? "Downloaded file" : uri.getLastPathSegment();
  }

  private void openDownloadedFile(Uri uri) {
    String name = downloadDisplayName(uri);
    String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
      android.webkit.MimeTypeMap.getFileExtensionFromUrl(name));
    try {
      Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime == null ? "*/*" : mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(view, "Open downloaded file"));
    } catch (Exception error) {
      Toast.makeText(this, "No app can open this file", Toast.LENGTH_LONG).show();
    }
  }

  private void showSettings() {
    String vpn = DevxyzVpnService.isConnected() ? "Connected" : "Disconnected";
    showInfo("Settings", "DevxyzBrowser • GeckoView 156\nVPN: " + vpn +
      "\nExtensions are managed from the browser menu. Site data and app preferences are stored on this device.");
  }

  private void browse(String raw) {
    String q = raw.trim();
    if(q.isEmpty()) return;
    session = ensureBrowserSession(activeTab);
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
    ensureBrowserRuntime();
    runtime.getWebExtensionController().list().accept(list -> runOnUiThread(() -> {
      if (isFinishing()) return;
      AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Extensions • " + list.size() + " installed")
        .setPositiveButton("Browse more", (d,w) -> browseMoreExtensions())
        .setNeutralButton("Install signed .xpi", (d,w) -> showXpiInstallDialog())
        .setNegativeButton("Close", null);
      if (list.isEmpty()) dialog.setMessage("No add-ons installed yet. Choose Browse more to install a Mozilla Add-on.");
      else {
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).metaData.name;
        dialog.setItems(names, (d, which) -> showExtensionActions(list.get(which)));
      }
      dialog.show();
    }), error -> runOnUiThread(() -> Toast.makeText(this, "Unable to load extensions: " + error.getMessage(), Toast.LENGTH_LONG).show()));
  }

  private void showExtensionActions(WebExtension extension) {
    String name = extension.metaData.name == null ? "Extension" : extension.metaData.name;
    String stateAction = extension.metaData.enabled ? "Disable" : "Enable";
    new AlertDialog.Builder(this).setTitle(name)
      .setItems(new String[]{stateAction, "Remove"}, (dialog, which) -> {
        if (which == 0) {
          GeckoResult<WebExtension> result = extension.metaData.enabled
            ? runtime.getWebExtensionController().disable(extension, WebExtensionController.EnableSource.USER)
            : runtime.getWebExtensionController().enable(extension, WebExtensionController.EnableSource.USER);
          result.accept(updated -> refreshExtensionsSummary(), error -> Toast.makeText(this, "Could not update extension: " + error.getMessage(), Toast.LENGTH_LONG).show());
        } else {
          new AlertDialog.Builder(this).setTitle("Remove " + name + "?")
            .setMessage("This removes the add-on and its stored data from this browser.")
            .setPositiveButton("Remove", (d,w) -> runtime.getWebExtensionController().uninstall(extension)
              .accept(removed -> refreshExtensionsSummary(), error -> Toast.makeText(this, "Could not remove extension: " + error.getMessage(), Toast.LENGTH_LONG).show()))
            .setNegativeButton("Cancel", null).show();
        }
      }).setNegativeButton("Close", null).show();
  }

  private void showXpiInstallDialog() {
    final EditText xpi = new EditText(this);
    xpi.setSingleLine(true);
    xpi.setHint("Mozilla-signed .xpi URL");
    new AlertDialog.Builder(this).setTitle("Install extension")
      .setMessage("Only Mozilla-signed extensions are accepted. Review and approve requested permissions before installation.")
      .setView(xpi).setPositiveButton("Continue", (d,w) -> {
        String uri = xpi.getText().toString().trim();
        if (!uri.startsWith("https://")) {
          Toast.makeText(this, "Use a secure HTTPS .xpi URL", Toast.LENGTH_LONG).show();
          return;
        }
        installExtension(uri, "Extension");
      }).setNegativeButton("Cancel", null).show();
  }

  private void browseMoreExtensions() {
    load("https://addons.mozilla.org/en-US/android/extensions/");
  }

  private void showExtensionsPane() {
    ensureBrowserRuntime();
    findViewById(R.id.desktopExtensionsPane).setVisibility(View.VISIBLE);
    refreshExtensionsSummary();
  }

  private void refreshExtensionsSummary() {
    TextView phoneSummary = findViewById(R.id.mobileExtensionSummary);
    if (runtime == null) {
      if (phoneSummary != null) phoneSummary.setText("Open Extensions to load installed add-ons.");
      installedExtensions = Collections.emptyList();
      renderExtensionRows(installedExtensions, extensionSearch == null ? "" : extensionSearch.getText().toString());
      return;
    }
    if (phoneSummary != null) phoneSummary.setText("Checking installed add-ons…");
    runtime.getWebExtensionController().list().accept(list -> {
      String summary;
      if (list.isEmpty()) {
        summary = "No add-ons installed. Install a Mozilla-signed .xpi extension to use it here.";
      } else {
        StringBuilder names = new StringBuilder("Installed add-ons • ").append(list.size()).append('\n');
        for (WebExtension extension : list) names.append("•  ").append(extension.metaData.name).append('\n');
        summary = names.toString().trim();
      }
      runOnUiThread(() -> {
        if (isFinishing()) return;
        installedExtensions = new ArrayList<>(list);
        if (phoneSummary != null) phoneSummary.setText(summary);
        renderExtensionRows(installedExtensions, extensionSearch == null ? "" : extensionSearch.getText().toString());
      });
    }, error -> {
      String message = "Could not load installed add-ons. Tap Manage to retry.";
      runOnUiThread(() -> {
        if (isFinishing()) return;
        if (phoneSummary != null) phoneSummary.setText(message);
        renderExtensionRows(Collections.emptyList(), extensionSearch == null ? "" : extensionSearch.getText().toString());
      });
    });
  }

  private void renderExtensionRows(List<WebExtension> installed, String rawQuery) {
    if (extensionRows == null) return;
    extensionRows.removeAllViews();
    String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
    Set<WebExtension> shown = new HashSet<>();
    for (AddonCard addon : ADDON_CATALOG) {
      WebExtension match = findInstalledAddon(installed, addon);
      if (match != null) shown.add(match);
      if (!query.isEmpty() && !addon.name.toLowerCase(Locale.ROOT).contains(query)
          && !addon.description.toLowerCase(Locale.ROOT).contains(query)) continue;
      addExtensionRow(addon, match);
    }
    for (WebExtension extension : installed) {
      if (shown.contains(extension)) continue;
      String name = extension.metaData.name == null ? "Installed extension" : extension.metaData.name;
      if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)) continue;
      addExtensionRow(new AddonCard(name, "Installed WebExtension", null, "#6540B8"), extension);
    }
    if (extensionRows.getChildCount() == 0) {
      TextView empty = new TextView(this);
      empty.setText(query.isEmpty() ? "No extensions found" : "No matches for “" + rawQuery + "”");
      empty.setTextColor(getColor(R.color.muted));
      empty.setTextSize(12);
      empty.setGravity(Gravity.CENTER);
      extensionRows.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
    }
  }

  private WebExtension findInstalledAddon(List<WebExtension> installed, AddonCard addon) {
    for (WebExtension extension : installed) {
      String name = extension.metaData.name == null ? "" : extension.metaData.name.toLowerCase(Locale.ROOT);
      if (name.equals(addon.name.toLowerCase(Locale.ROOT))) return extension;
      if (addon.slug != null && name.contains(addon.slug.replace('-', ' '))) return extension;
      if (addon.name.equals("Dark Reader") && name.contains("dark reader")) return extension;
      if (addon.name.equals("uBlock Origin") && name.contains("ublock")) return extension;
      if (addon.name.equals("SponsorBlock") && name.contains("sponsorblock")) return extension;
    }
    return null;
  }

  private void addExtensionRow(AddonCard addon, WebExtension installed) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(50));
    extensionRows.addView(row, rowLp);

    ImageView icon = new ImageView(this);
    icon.setImageResource(extensionIcon(addon.name));
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    icon.setContentDescription(addon.name + " icon");
    row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setPadding(8, 0, 4, 0);
    TextView title = new TextView(this);
    title.setText(addon.name);
    title.setTextColor(getColor(R.color.text));
    title.setTextSize(11);
    title.setSingleLine(true);
    TextView description = new TextView(this);
    description.setText(addon.description);
    description.setTextColor(getColor(R.color.muted));
    description.setTextSize(9);
    description.setSingleLine(true);
    labels.addView(title);
    labels.addView(description);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

    Switch toggle = new Switch(this);
    toggle.setThumbTintList(getColorStateList(R.color.switch_thumb));
    toggle.setTrackTintList(getColorStateList(R.color.switch_track));
    if (installed != null) {
      toggle.setChecked(installed.metaData.enabled);
      toggle.setContentDescription("Enable " + addon.name);
      row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
      toggle.setOnCheckedChangeListener((button, enabled) -> {
        GeckoResult<WebExtension> result = enabled
          ? runtime.getWebExtensionController().enable(installed, WebExtensionController.EnableSource.USER)
          : runtime.getWebExtensionController().disable(installed, WebExtensionController.EnableSource.USER);
        result.accept(updated -> refreshExtensionsSummary(), error -> {
          Toast.makeText(this, "Could not change " + addon.name + ": " + error.getMessage(), Toast.LENGTH_LONG).show();
          refreshExtensionsSummary();
        });
      });
    } else if (addon.supportedOnAndroid()) {
      toggle.setChecked(false);
      toggle.setContentDescription("Install " + addon.name + " from Mozilla Add-ons");
      toggle.setOnClickListener(v -> {
        if (!toggle.isChecked()) return;
        toggle.setChecked(false);
        new AlertDialog.Builder(this).setTitle("Install " + addon.name + "?")
          .setMessage("This will download the Mozilla-signed Android extension. GeckoView will show its requested permissions before installation.")
          .setPositiveButton("Continue", (d,w) -> installExtension(addon.downloadUrl(), addon.name))
          .setNegativeButton("Cancel", null).show();
      });
      row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
    } else {
      toggle.setChecked(false);
      toggle.setEnabled(false);
      toggle.setContentDescription(addon.name + " is not compatible with this Android browser");
      row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
    }
  }

  private int extensionIcon(String name) {
    if (name.equals("uBlock Origin")) return R.drawable.extension_ublock;
    if (name.equals("Dark Reader")) return R.drawable.extension_darkreader;
    if (name.equals("Grammarly")) return R.drawable.extension_grammarly;
    if (name.equals("SponsorBlock")) return R.drawable.extension_sponsorblock;
    if (name.startsWith("React")) return R.drawable.extension_reactdevtools;
    return R.drawable.ic_extension;
  }

  private void installExtension(String uri, String displayName) {
    runtime.getWebExtensionController().install(uri, WebExtensionController.INSTALLATION_METHOD_MANAGER)
      .accept(ext -> {
        Toast.makeText(this, "Installed: " + ext.metaData.name, Toast.LENGTH_LONG).show();
        refreshExtensionsSummary();
      }, error -> Toast.makeText(this, displayName + " install failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
  }

  private String permissionLines(String[] permissions, String[] origins, String[] dataPermissions) {
    ArrayList<String> lines = new ArrayList<>();
    for (String value : permissions) lines.add("• " + value);
    for (String value : origins) lines.add("• Site access: " + value);
    for (String value : dataPermissions) lines.add("• Data: " + value);
    if (lines.isEmpty()) lines.add("No additional permissions requested.");
    return TextUtils.join("\n", lines);
  }

  private void setBrandWordmark(TextView title) {
    if (title == null) return;
    SpannableString wordmark = new SpannableString("DevxyzBrowser");
    wordmark.setSpan(new ForegroundColorSpan(getColor(R.color.purple2)), 6, wordmark.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    title.setText(wordmark);
  }

  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
         startService(stop); setVpnStatus("Disconnecting • stopping OpenVPN");
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

  private void handleVpnPower() {
    if (!VpnProfileStore.hasProfile(this)) {
      showVpn();
      return;
    }
    if (DevxyzVpnService.isConnected()) {
      Intent stop = new Intent(this, DevxyzVpnService.class).setAction(DevxyzVpnService.ACTION_DISCONNECT);
      startService(stop);
      setVpnStatus("Disconnecting • stopping OpenVPN");
      return;
    }
    Intent permission = VpnService.prepare(this);
    if (permission != null) vpnPermission.launch(permission);
    else requestVpnCredentials();
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
      setVpnStatus("Connecting • starting OpenVPN");
    } catch (Exception e) {
      Toast.makeText(this, "Could not start VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private String safe(String value) { return value == null || value.trim().isEmpty() ? "OpenVPN" : value; }

  private void setVpnStatus(String text) {
    if (vpnStatus != null) vpnStatus.setText(text);
    TextView desktopStatus = findViewById(R.id.desktopVpnStatus);
    boolean connected = DevxyzVpnService.isConnected();
    if (desktopStatus != null) {
      desktopStatus.setText(connected ? "Connected" : text.startsWith("Connecting") ? "Connecting" : "Disconnected");
      desktopStatus.setTextColor(getColor(connected ? R.color.green : R.color.muted));
    }
    TextView secure = findViewById(R.id.vpnSecureCard);
    if (secure != null) secure.setText(connected ? "✓  Your connection is secure" : "◇  VPN disconnected • import a profile to connect");
    if (secure != null) secure.setTextColor(getColor(connected ? R.color.green : R.color.muted));
    TextView profileName = findViewById(R.id.vpnProfileName);
    TextView profileSubtitle = findViewById(R.id.vpnProfileSubtitle);
    if (profileName != null && profileSubtitle != null) {
      try {
        profileName.setText(VpnProfileStore.hasProfile(this) ? VpnProfileStore.getEndpoint(this) : "No profile imported");
        profileSubtitle.setText(VpnProfileStore.hasProfile(this) ? "Imported OpenVPN server" : "Choose your OpenVPN server");
      } catch (Exception ignored) {
        profileName.setText("Profile unavailable");
      }
    }
    String action = DevxyzVpnService.isConnected() ? "Disconnect VPN" :
      (VpnProfileStore.hasProfile(this) ? "Connect VPN" : "Set up VPN");
    Button phoneAction = findViewById(R.id.vpnPanel);
    Button desktopAction = findViewById(R.id.desktopVpnAction);
    if (phoneAction != null) phoneAction.setText(action);
    if (desktopAction != null) desktopAction.setText(action);
  }

  private void setupVpnSwitches() {
    android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
    Switch autoConnect = findViewById(R.id.autoConnect);
    Switch blockTrackers = findViewById(R.id.blockTrackers);
    autoConnect.setChecked(preferences.getBoolean("auto_connect_vpn", false));
    blockTrackers.setChecked(preferences.getBoolean("block_trackers", true));
    autoConnect.setOnCheckedChangeListener((button, checked) -> {
      preferences.edit().putBoolean("auto_connect_vpn", checked).apply();
      if (checked && !VpnProfileStore.hasProfile(this)) {
        button.setChecked(false);
        Toast.makeText(this, "Import an OpenVPN profile before enabling Auto Connect", Toast.LENGTH_LONG).show();
        showVpn();
      } else if (checked) {
        connectSavedProfileWithPermission();
      }
    });
    blockTrackers.setOnCheckedChangeListener((button, checked) -> {
      preferences.edit().putBoolean("block_trackers", checked).apply();
      for (BrowserTab tab : tabs) if (tab.session != null) tab.session.getSettings().setUseTrackingProtection(checked);
      Toast.makeText(this, checked ? "Gecko tracking protection enabled" : "Gecko tracking protection disabled", Toast.LENGTH_SHORT).show();
    });
  }

  private void maybeAutoConnectSavedProfile() {
    if (!getPreferences(MODE_PRIVATE).getBoolean("auto_connect_vpn", false)
        || !VpnProfileStore.hasProfile(this) || DevxyzVpnService.isConnected()) return;
    try {
      if (VpnProfileStore.requiresCredentials(this)) {
        setVpnStatus("Disconnected • credentials required for Auto Connect");
        return;
      }
      if (VpnService.prepare(this) == null) startOpenVpn("", "", "");
      else setVpnStatus("Disconnected • approve VPN permission to connect");
    } catch (Exception error) {
      setVpnStatus("Disconnected • VPN profile unavailable");
    }
  }

  private void connectSavedProfileWithPermission() {
    try {
      if (VpnProfileStore.requiresCredentials(this)) {
        requestVpnCredentials();
        return;
      }
      Intent permission = VpnService.prepare(this);
      if (permission != null) vpnPermission.launch(permission);
      else startOpenVpn("", "", "");
    } catch (Exception error) {
      Toast.makeText(this, "Could not read VPN profile: " + error.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void openAndroidVpnSettings() {
    new AlertDialog.Builder(this).setTitle("Android Kill Switch")
      .setMessage("Android controls Always-on VPN and Block connections without VPN. Choose DevxyzBrowser in system VPN settings and enable those options if you want the system kill switch.")
      .setPositiveButton("Open VPN settings", (dialog, which) -> {
        try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
        catch (Exception error) { Toast.makeText(this, "VPN settings are unavailable on this device", Toast.LENGTH_LONG).show(); }
      }).setNegativeButton("Cancel", null).show();
  }

  @Override protected void onDestroy() {
    if (vpnReceiverRegistered) { unregisterReceiver(vpnStateReceiver); vpnReceiverRegistered = false; }
    for (BrowserTab tab : tabs) if (tab.session != null) tab.session.close();
    tabs.clear();
    super.onDestroy();
  }
}
