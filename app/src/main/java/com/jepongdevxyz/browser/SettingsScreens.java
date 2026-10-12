package com.jepongdevxyz.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.jepongdevxyz.browser.util.Appearance;
import com.jepongdevxyz.browser.util.ParallelDownloader;
import com.jepongdevxyz.browser.util.Shields;
import com.jepongdevxyz.browser.Ui;

/** Brave-style settings screens (Shields, Downloads, Appearance, Default browser).
 * Extracted from MainActivity to keep the activity pushable; all handlers are real. */
public final class SettingsScreens {
  private final MainActivity a;

  public SettingsScreens(MainActivity activity) { a = activity; }

  public void showShieldsDialog() {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, a.dp(8));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);

    TextView title = new TextView(a);
    title.setText("Block trackers & ads");
    title.setTextSize(20);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    root.addView(title);

    TextView desc = new TextView(a);
    desc.setText("Prevents trackers & ads from loading and showing in webpages");
    desc.setTextSize(14);
    desc.setTextColor(a.getColor(R.color.muted));
    desc.setPadding(0, a.dp(8), 0, a.dp(12));
    root.addView(desc);

    String[] modes = {"Block trackers & ads (Aggressive)", "Block trackers & ads (Standard)", "Allow all trackers & ads"};
    int cur = Shields.mode(a.prefs);
    RadioGroup group = new RadioGroup(a);
    for (int i = 0; i < modes.length; i++) {
      int modeVal = (i == 0) ? 2 : (i == 1) ? 1 : 0;
      RadioButton rb = new RadioButton(a);
      rb.setText(modes[i]);
      rb.setTextSize(16);
      rb.setTextColor(a.getColor(R.color.text));
      rb.setChecked(modeVal == cur);
      rb.setPadding(0, a.dp(8), 0, a.dp(8));
      rb.setOnCheckedChangeListener((b, checked) -> {
        if (!checked) return;
        a.prefs.edit().putInt("shields_mode", modeVal).apply();
        a.refreshSettingsPanel();
        Ui.toast(a, "Applies after you restart the app");
      });
      group.addView(rb);
    }
    root.addView(group);

    root.addView(shieldToggle("Block scripts",
        "Blocks JavaScript (may break sites)",
        "shields_block_scripts", false, true));
    root.addView(shieldToggle("Block cookies",
        "Block third-party cookies",
        "shields_block_cookies", true, false));
    root.addView(shieldToggle("Block fingerprinting",
        "Prevents websites from fingerprinting me",
        "shields_fingerprinting", true, false));

    TextView note = new TextView(a);
    note.setText("Tracker, cookie and fingerprinting changes apply after restarting the app. Script blocking applies immediately.");
    note.setTextSize(12);
    note.setTextColor(a.getColor(R.color.muted));
    note.setPadding(0, a.dp(12), 0, a.dp(8));
    root.addView(note);

    Ui.dialog(a).setView(scroll)
        .setPositiveButton("Done", null).show();
  }

  public LinearLayout shieldToggle(String title, String subtitle, String prefKey,
      boolean defaultValue, boolean liveApply) {
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, a.dp(10), 0, a.dp(10));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(16);
    t.setTextColor(a.getColor(R.color.text));
    TextView s = new TextView(a);
    s.setText(subtitle);
    s.setTextSize(13);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(t);
    texts.addView(s);
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    com.google.android.material.switchmaterial.SwitchMaterial sw =
        new com.google.android.material.switchmaterial.SwitchMaterial(a);
    sw.setChecked(a.prefs.getBoolean(prefKey, defaultValue));
    sw.setOnCheckedChangeListener((b, checked) -> {
      a.prefs.edit().putBoolean(prefKey, checked).apply();
      if (liveApply) {
        a.applyShieldsToSessions();
        Ui.toast(a, checked ? "Scripts blocked" : "Scripts allowed");
      } else {
        Ui.toast(a, "Applies after you restart the app");
      }
    });
    row.addView(sw);
    return row;
  }

  public void showDownloadSettings() {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, a.dp(8));

    TextView title = new TextView(a);
    title.setText("Downloads");
    title.setTextSize(20);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    title.setPadding(0, 0, 0, a.dp(12));
    root.addView(title);

    // Download location (display).
    LinearLayout locRow = new LinearLayout(a);
    locRow.setOrientation(LinearLayout.VERTICAL);
    locRow.setPadding(0, a.dp(4), 0, a.dp(12));
    TextView locTitle = new TextView(a);
    locTitle.setText("Download location");
    locTitle.setTextSize(16);
    locTitle.setTextColor(a.getColor(R.color.text));
    TextView locPath = new TextView(a);
    java.io.File dlDir = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS);
    locPath.setText(dlDir.getAbsolutePath());
    locPath.setTextSize(13);
    locPath.setTextColor(a.getColor(R.color.muted));
    locRow.addView(locTitle);
    locRow.addView(locPath);
    root.addView(locRow);

    root.addView(downloadToggle("Ask where to save files",
        "Choose a location every time you download",
        "dl_ask_where", false, null));
    root.addView(downloadToggle("Automatically open when possible",
        "Open downloaded files right away",
        "dl_auto_open", true, null));
    root.addView(downloadToggle("Show download progress notifications",
        "Notify while files are downloading",
        "dl_notifications", true, null));
    root.addView(downloadToggle("Parallel Downloading",
        "Enable parallel downloading to accelerate download speed",
        "dl_parallel", true, null));

    Ui.dialog(a).setView(root).setPositiveButton("Done", null).show();
  }

  public LinearLayout downloadToggle(String title, String subtitle, String prefKey,
      boolean defaultValue, Runnable onChange) {
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, a.dp(10), 0, a.dp(10));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(16);
    t.setTextColor(a.getColor(R.color.text));
    TextView s = new TextView(a);
    s.setText(subtitle);
    s.setTextSize(13);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(t);
    texts.addView(s);
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    com.google.android.material.switchmaterial.SwitchMaterial sw =
        new com.google.android.material.switchmaterial.SwitchMaterial(a);
    sw.setChecked(a.prefs.getBoolean(prefKey, defaultValue));
    sw.setOnCheckedChangeListener((b, checked) -> {
      a.prefs.edit().putBoolean(prefKey, checked).apply();
      if (onChange != null) onChange.run();
    });
    row.addView(sw);
    return row;
  }

  public void openFile(Uri uri) {
    try {
      String name = uri.getLastPathSegment();
      String mime = null;
      if (name != null) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.US));
      }
      if (mime == null) mime = "*/*";
      Uri shareUri = uri;
      if ("file".equals(uri.getScheme())) {
        shareUri = androidx.core.content.FileProvider.getUriForFile(a,
            a.getPackageName() + ".fileprovider", new java.io.File(uri.getPath()));
      }
      Intent open = new Intent(Intent.ACTION_VIEW);
      open.setDataAndType(shareUri, mime);
      open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      a.startActivity(Intent.createChooser(open, "Open with"));
    } catch (Exception e) {
      Ui.toastLong(a, "Can't open file: " + e.getMessage());
    }
  }

  public boolean isDefaultBrowser() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= 29) {
        android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
          return rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER);
        }
      }
    } catch (Exception ignored) { }
    return false;
  }

  public void openDefaultBrowserSettings() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= 29) {
        android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)
            && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
          a.startActivityForResult(
              rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER),
              MainActivity.REQ_DEFAULT_BROWSER);
          return;
        }
      }
      // Fallback: open the system's Default apps screen.
      Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
      a.startActivity(i);
    } catch (Exception e) {
      Ui.toastLong(a, "Could not open default apps: " + e.getMessage());
    }
  }

  public void showAppearanceSettings() {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, a.dp(8));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);

    TextView title = new TextView(a);
    title.setText("Appearance");
    title.setTextSize(20);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    title.setPadding(0, 0, 0, a.dp(8));
    root.addView(title);

    // Theme: System default / Light / Dark.
    LinearLayout themeRow = appearanceRow("Theme", Appearance.themeLabel(a.prefs));
    themeRow.setOnClickListener(v -> a.pickOption("Theme",
        new String[]{"System default", "Light", "Dark"},
        Appearance.themeLabel(a.prefs),
        val -> {
          int t = val.equals("Light") ? 1 : val.equals("Dark") ? 2 : 0;
          a.prefs.edit().putInt("app_theme", t).apply();
          Appearance.applyTheme(a.prefs);
          a.recreate();
        }));
    root.addView(themeRow);

    // Dynamic colors.
    root.addView(appearanceToggle("Dynamic colors",
        "App theme matches wallpaper colors",
        "dynamic_colors", false,
        () -> Ui.toast(a, "Restart the app to apply")));

    // Customize menu.
    LinearLayout menuRow = appearanceRow("Customize menu", "Choose menu items");
    menuRow.setOnClickListener(v -> showCustomizeMenu());
    root.addView(menuRow);

    // Toolbar shortcut.
    String[] shortcutOpts = {"Off", "New tab", "Bookmarks", "History", "Downloads", "Share", "Voice search"};
    String curShortcut = Appearance.toolbarShortcut(a.prefs);
    LinearLayout scRow = appearanceRow("Toolbar shortcut",
        curShortcut.isEmpty() ? "Off" : curShortcut);
    scRow.setOnClickListener(v -> a.pickOption("Toolbar shortcut", shortcutOpts,
        curShortcut.isEmpty() ? "Off" : curShortcut,
        val -> {
          a.prefs.edit().putString("toolbar_shortcut", val.equals("Off") ? "" : val).apply();
          applyAppearance();
          showAppearanceSettings();
        }));
    root.addView(scRow);

    // Address bar position.
    LinearLayout addrRow = appearanceRow("Address bar",
        Appearance.addressBarBottom(a.prefs) ? "Bottom" : "Top");
    addrRow.setOnClickListener(v -> a.pickOption("Address bar",
        new String[]{"Top", "Bottom"},
        Appearance.addressBarBottom(a.prefs) ? "Bottom" : "Top",
        val -> {
          a.prefs.edit().putBoolean("address_bar_bottom", val.equals("Bottom")).apply();
          applyAppearance();
          showAppearanceSettings();
        }));
    root.addView(addrRow);

    // Bottom navigation toolbar.
    root.addView(appearanceToggle("Bottom navigation toolbar",
        "Show a bottom bar for quick access to home, bookmarks, and search",
        "bottom_nav_bar", false,
        () -> applyAppearance()));

    // Night Mode for web content (via Dark Reader extension).
    root.addView(appearanceToggle("Night Mode",
        "Force dark mode for web content",
        "night_mode_web", false,
        () -> applyNightModeWeb()));

    Ui.dialog(a).setView(scroll).setPositiveButton("Done", null).show();
  }

  public LinearLayout appearanceRow(String title, String value) {
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, a.dp(12), 0, a.dp(12));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(16);
    t.setTextColor(a.getColor(R.color.text));
    TextView s = new TextView(a);
    s.setText(value);
    s.setTextSize(13);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(t);
    texts.addView(s);
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    return row;
  }

  public LinearLayout appearanceToggle(String title, String subtitle, String prefKey,
      boolean defaultValue, Runnable onChange) {
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, a.dp(10), 0, a.dp(10));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = new TextView(a);
    t.setText(title);
    t.setTextSize(16);
    t.setTextColor(a.getColor(R.color.text));
    TextView s = new TextView(a);
    s.setText(subtitle);
    s.setTextSize(13);
    s.setTextColor(a.getColor(R.color.muted));
    texts.addView(t);
    texts.addView(s);
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    com.google.android.material.switchmaterial.SwitchMaterial sw =
        new com.google.android.material.switchmaterial.SwitchMaterial(a);
    sw.setChecked(a.prefs.getBoolean(prefKey, defaultValue));
    sw.setOnCheckedChangeListener((b, checked) -> {
      a.prefs.edit().putBoolean(prefKey, checked).apply();
      if (onChange != null) onChange.run();
    });
    row.addView(sw);
    return row;
  }

  public void showCustomizeMenu() {
    String[][] items = {
        {"newtab", "New tab"}, {"incognito", "New incognito tab"},
        {"history", "History"}, {"cleardata", "Delete browsing data"},
        {"site", "Site controls"}, {"downloads", "Downloads"},
        {"bookmarks", "Bookmarks"}, {"recent", "Recent tabs"},
        {"share", "Share"}, {"find", "Find in page"},
        {"translate", "Translate"}, {"desktop", "Desktop site"},
        {"extensions", "Extensions"}, {"vpn", "VPN"}, {"help", "Help"},
    };
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, a.dp(8));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);
    TextView title = new TextView(a);
    title.setText("Customize menu");
    title.setTextSize(20);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    title.setPadding(0, 0, 0, a.dp(8));
    root.addView(title);
    for (String[] item : items) {
      LinearLayout row = new LinearLayout(a);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, a.dp(8), 0, a.dp(8));
      TextView t = new TextView(a);
      t.setText(item[1]);
      t.setTextSize(16);
      t.setTextColor(a.getColor(R.color.text));
      row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
      com.google.android.material.switchmaterial.SwitchMaterial sw =
          new com.google.android.material.switchmaterial.SwitchMaterial(a);
      sw.setChecked(Appearance.menuVisible(a.prefs, item[0]));
      sw.setOnCheckedChangeListener((b, checked) ->
          a.prefs.edit().putBoolean("menu_" + item[0], checked).apply());
      row.addView(sw);
      root.addView(row);
    }
    Ui.dialog(a).setView(scroll).setPositiveButton("Done", null).show();
  }

  public void applyNightModeWeb() {
    boolean on = a.prefs.getBoolean("night_mode_web", false);
    a.runtime.getWebExtensionController().list().accept(list -> {
      org.mozilla.geckoview.WebExtension darkReader = null;
      if (list != null) {
        for (org.mozilla.geckoview.WebExtension ext : list) {
          String nm = (ext.metaData != null && ext.metaData.name != null) ? ext.metaData.name : "";
          if (nm.toLowerCase(java.util.Locale.US).contains("dark reader")) { darkReader = ext; break; }
        }
      }
      if (darkReader == null) {
        a.runOnUiThread(() -> {
          if (on) {
            Ui.dialog(a)
                .setTitle("Install Dark Reader?")
                .setMessage("Night Mode uses the Dark Reader extension to force dark mode on websites.")
                .setPositiveButton("Install", (d, w) -> {
                  for (MainActivity.RecExt rec : MainActivity.RECOMMENDED) {
                    if ("darkreader".equals(rec.slug)) { a.installRecommended(rec); break; }
                  }
                })
                .setNegativeButton("Cancel", (d, w) ->
                    a.prefs.edit().putBoolean("night_mode_web", false).apply())
                .show();
          }
        });
        return;
      }
      final org.mozilla.geckoview.WebExtension dr = darkReader;
      a.runOnUiThread(() -> {
        (on ? a.runtime.getWebExtensionController().enable(dr,
                org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
            : a.runtime.getWebExtensionController().disable(dr,
                org.mozilla.geckoview.WebExtensionController.EnableSource.USER))
            .accept(v -> a.runOnUiThread(() ->
                Ui.toast(a, on ? "Night Mode on" : "Night Mode off")),
                e -> a.runOnUiThread(() ->
                    Ui.toastLong(a, "Night Mode failed: " + e.getMessage())));
      });
    }, e -> a.runOnUiThread(() ->
        Ui.toastLong(a, "Night Mode failed: " + e.getMessage())));
  }

  public void applyAppearance() {
    LinearLayout toolbar = a.findViewById(R.id.toolbar);
    LinearLayout root = (LinearLayout) toolbar.getParent();
    boolean bottom = Appearance.addressBarBottom(a.prefs);
    // Move toolbar to top or bottom.
    root.removeView(toolbar);
    View progress = a.findViewById(R.id.pageProgress);
    if (bottom) {
      // Insert toolbar just above the bottom nav bar (last child).
      int idx = root.indexOfChild(a.findViewById(R.id.bottomNavBar));
      if (idx < 0) idx = root.getChildCount();
      root.addView(toolbar, idx);
      if (progress != null) {
        root.removeView(progress);
        root.addView(progress, idx);
      }
    } else {
      root.addView(toolbar, 0);
      if (progress != null) {
        root.removeView(progress);
        root.addView(progress, 1);
      }
    }

    // Toolbar shortcut button.
    ImageButton shortcut = a.findViewById(R.id.toolbarShortcut);
    String action = Appearance.toolbarShortcut(a.prefs);
    if (action == null || action.isEmpty()) {
      shortcut.setVisibility(View.GONE);
    } else {
      shortcut.setVisibility(View.VISIBLE);
      shortcut.setImageResource(shortcutIcon(action));
      shortcut.setContentDescription(action);
      shortcut.setOnClickListener(v -> runShortcut(action));
    }

    // Bottom navigation toolbar.
    View bottomBar = a.findViewById(R.id.bottomNavBar);
    boolean showBottom = a.prefs.getBoolean("bottom_nav_bar", false);
    bottomBar.setVisibility(showBottom ? View.VISIBLE : View.GONE);
    if (showBottom) {
      a.onClick(R.id.bnHome, v -> a.showHome());
      a.onClick(R.id.bnBookmarks, v -> a.showBookmarks());
      a.onClick(R.id.bnSearch, v -> { a.address.requestFocus(); showKeyboard(a.address); });
      a.onClick(R.id.bnTabs, v -> a.showTabs());
      a.onClick(R.id.bnMenu, v -> a.showChromeMenu(v));
    }
  }

  public void showKeyboard(View v) {
    try {
      android.view.inputmethod.InputMethodManager imm =
          (android.view.inputmethod.InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
      imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    } catch (Exception ignored) { }
  }

  public void startVoiceSearch() {
    try {
      Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
      i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
          android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
      i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search");
      a.voiceSearchLauncher.launch(i);
    } catch (Exception e) {
      Ui.toastLong(a, "Voice search not available: " + e.getMessage());
    }
  }

  public int shortcutIcon(String action) {
    switch (action) {
      case "New tab": return R.drawable.ic_new_tab;
      case "Bookmarks": return R.drawable.ic_nav_bookmark;
      case "History": return R.drawable.ic_nav_history;
      case "Downloads": return R.drawable.ic_nav_download;
      case "Share": return R.drawable.ic_share;
      case "Voice search": return R.drawable.ic_mic;
      default: return R.drawable.ic_tb_search;
    }
  }

  public void runShortcut(String action) {
    switch (action) {
      case "New tab": a.newTab(); break;
      case "Bookmarks": a.showBookmarks(); break;
      case "History": a.showList("History", a.historyItems); break;
      case "Downloads": a.showDownloads(); break;
      case "Share": a.sharePage(); break;
      case "Voice search": startVoiceSearch(); break;
      default: break;
    }
  }

  public void enqueueDownload(String url, String filename) {
    try {
      boolean askWhere = a.prefs.getBoolean("dl_ask_where", false);
      boolean parallel = a.prefs.getBoolean("dl_parallel", true);
      boolean notifications = a.prefs.getBoolean("dl_notifications", true);
      if (askWhere) {
        // Let the user pick the save location (SAF), then download there.
        a.pendingDownloadUrl = url;
        a.pendingDownloadName = filename;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_TITLE, filename);
        a.safDownloadPicker.launch(i);
        return;
      }
      if (parallel) {
        // Brave-style parallel downloading: multi-connection Range download.
        Ui.toast(a, "Downloading " + filename);
        ParallelDownloader.download(a, url, filename, null, notifications,
            new ParallelDownloader.Callback() {
              @Override public void onProgress(int percent) { }
              @Override public void onDone(Uri fileUri) {
                a.runOnUiThread(() -> {
                  String time = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a",
                      java.util.Locale.getDefault()).format(new java.util.Date());
                  a.downloads.add(0, new MainActivity.DownloadItem(filename, url, time, -1));
                  a.saveDownloads();
                  a.refreshDownloadsSheet();
                  Ui.toast(a, "Downloaded " + filename);
                  if (a.prefs.getBoolean("dl_auto_open", true)) openFile(fileUri);
                });
              }
              @Override public void onError(String message) {
                a.runOnUiThread(() -> Ui.toastLong(a, "Download failed: " + message));
              }
            });
        return;
      }
      android.app.DownloadManager dm = (android.app.DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
      android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
      req.setTitle(filename);
      req.setNotificationVisibility(notifications
          ? android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
          : android.app.DownloadManager.Request.VISIBILITY_HIDDEN);
      req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename);
      req.allowScanningByMediaScanner();
      long id = dm.enqueue(req);
      String time = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
          .format(new java.util.Date());
      a.downloads.add(0, new MainActivity.DownloadItem(filename, url, time, id));
      a.saveDownloads();
      Ui.toast(a, "Downloading " + filename);
      a.refreshDownloadsSheet();
    } catch (Exception e) {
      Ui.toastLong(a, "Download failed: " + e.getMessage());
    }
  }


  public void initDownloadLaunchers() {
    // Voice search for the toolbar shortcut.
    a.voiceSearchLauncher = a.registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
      if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
      java.util.ArrayList<String> matches = result.getData().getStringArrayListExtra(
          android.speech.RecognizerIntent.EXTRA_RESULTS);
      if (matches != null && !matches.isEmpty()) {
        a.address.setText(matches.get(0));
        a.browse(matches.get(0));
      }
    });
    // "Ask where to save files": user picks the location, we download straight to it.
    a.safDownloadPicker = a.registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
      if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) {
        Ui.toast(a, "Download cancelled");
        return;
      }
      Uri target = result.getData().getData();
      if (target == null || a.pendingDownloadUrl == null) return;
      String url = a.pendingDownloadUrl;
      String name = a.pendingDownloadName;
      boolean notifications = a.prefs.getBoolean("dl_notifications", true);
      Ui.toast(a, "Downloading " + name);
      ParallelDownloader.download(a, url, name, target, notifications,
          new ParallelDownloader.Callback() {
            @Override public void onProgress(int percent) { }
            @Override public void onDone(Uri fileUri) {
              a.runOnUiThread(() -> {
                String time = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a",
                    java.util.Locale.getDefault()).format(new java.util.Date());
                a.downloads.add(0, new MainActivity.DownloadItem(name, url, time, -1));
                a.saveDownloads();
                a.refreshDownloadsSheet();
                Ui.toast(a, "Downloaded " + name);
                if (a.prefs.getBoolean("dl_auto_open", true)) openFile(fileUri);
              });
            }
            @Override public void onError(String message) {
              a.runOnUiThread(() -> Ui.toastLong(a, "Download failed: " + message));
            }
          });
    });
    // "Automatically open when possible" for DownloadManager downloads.
    a.downloadCompleteReceiver = new android.content.BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        if (!a.prefs.getBoolean("dl_auto_open", true)) return;
        long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id < 0) return;
        for (MainActivity.DownloadItem d : a.downloads) {
          if (d.id == id) {
            android.app.DownloadManager dm =
                (android.app.DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri != null) openFile(uri);
            break;
          }
        }
      }
    };
  }


  public void initSettingsPanel(View root) {
    root.findViewById(R.id.settings_close).setOnClickListener(v -> a.settingsDialog.dismiss());
    com.google.android.material.switchmaterial.SwitchMaterial swRestore = root.findViewById(R.id.sw_restore_tabs);
    com.google.android.material.switchmaterial.SwitchMaterial swHist = root.findViewById(R.id.sw_remember_history);
    com.google.android.material.switchmaterial.SwitchMaterial swClear = root.findViewById(R.id.sw_clear_on_quit);
    swRestore.setChecked(a.prefs.getBoolean("restore_tabs", true));
    swHist.setChecked(a.prefs.getBoolean("remember_history", true));
    swClear.setChecked(a.prefs.getBoolean("clear_on_quit", false));
    swRestore.setOnCheckedChangeListener((b, c) -> a.prefs.edit().putBoolean("restore_tabs", c).apply());
    swHist.setOnCheckedChangeListener((b, c) -> a.prefs.edit().putBoolean("remember_history", c).apply());
    swClear.setOnCheckedChangeListener((b, c) -> a.prefs.edit().putBoolean("clear_on_quit", c).apply());
    root.findViewById(R.id.set_search_engine_row).setOnClickListener(v -> a.pickOption(
        "Default search engine",
        new String[]{"Google", "Bing", "DuckDuckGo"},
        a.prefs.getString("search_engine", "Google"),
        val -> { a.prefs.edit().putString("search_engine", val).apply(); a.refreshSettingsPanel(); }));
    root.findViewById(R.id.set_homepage_row).setOnClickListener(v -> a.pickOption(
        "Homepage",
        new String[]{"Devxyz Home", "Blank page"},
        a.prefs.getString("homepage", "Devxyz Home"),
        val -> { a.prefs.edit().putString("homepage", val).apply(); a.refreshSettingsPanel(); }));
    root.findViewById(R.id.set_shields_row).setOnClickListener(v -> showShieldsDialog());
    root.findViewById(R.id.set_downloads_row).setOnClickListener(v -> showDownloadSettings());
    root.findViewById(R.id.set_default_browser_row).setOnClickListener(v -> openDefaultBrowserSettings());
    root.findViewById(R.id.set_appearance_row).setOnClickListener(v -> showAppearanceSettings());
    // AI Assistant row (added programmatically since panel_settings.xml is fixed)
    LinearLayout aiRow = appearanceRow("AI Assistant",
        com.jepongdevxyz.browser.util.GeminiAi.getKeys(a.prefs).isEmpty() ? "Not set up" : "Gemini ready");
    aiRow.setOnClickListener(v -> showAiSettings());
    // Insert before the last child (version footer if any)
    root.addView(aiRow, Math.max(0, root.getChildCount() - 1));
    a.refreshSettingsPanel();
  }


  public void showAiSettings() {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = a.dp(20);
    root.setPadding(pad, pad, pad, a.dp(8));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(root);

    TextView title = new TextView(a);
    title.setText("AI Assistant");
    title.setTextSize(20);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    title.setPadding(0, 0, 0, a.dp(8));
    root.addView(title);

    TextView desc = new TextView(a);
    desc.setText("Powered by Google Gemini. Add API keys below (one per line) — keys rotate automatically on quota limits. Get a free key at aistudio.google.com.");
    desc.setTextSize(13);
    desc.setTextColor(a.getColor(R.color.muted));
    desc.setPadding(0, 0, 0, a.dp(12));
    root.addView(desc);

    TextView keyLabel = new TextView(a);
    keyLabel.setText("Gemini API keys");
    keyLabel.setTextSize(14);
    keyLabel.setTextColor(a.getColor(R.color.text));
    keyLabel.setPadding(0, 0, 0, a.dp(4));
    root.addView(keyLabel);

    android.widget.EditText keyInput = new android.widget.EditText(a);
    keyInput.setHint("Paste API key(s), one per line");
    keyInput.setTextSize(14);
    keyInput.setTextColor(a.getColor(R.color.text));
    keyInput.setHintTextColor(a.getColor(R.color.muted));
    keyInput.setMinLines(3);
    keyInput.setText(a.prefs.getString("gemini_api_keys", ""));
    root.addView(keyInput);

    LinearLayout btnRow = new LinearLayout(a);
    btnRow.setOrientation(LinearLayout.HORIZONTAL);
    btnRow.setPadding(0, a.dp(12), 0, 0);
    android.widget.Button testBtn = new android.widget.Button(a);
    testBtn.setText("Test");
    testBtn.setOnClickListener(v -> {
      String key = keyInput.getText().toString().trim().split("\\n")[0].trim();
      if (key.isEmpty()) { Ui.toast(a, "Enter a key first"); return; }
      Ui.toast(a, "Testing key…");
      com.jepongdevxyz.browser.util.GeminiAi.testKey(key, new com.jepongdevxyz.browser.util.GeminiAi.ChatCallback() {
        @Override public void onReply(String t) { Ui.toast(a, "Key works!"); }
        @Override public void onError(String m) { Ui.toastLong(a, "Key failed: " + m); }
      });
    });
    btnRow.addView(testBtn);
    android.widget.Button saveBtn = new android.widget.Button(a);
    saveBtn.setText("Save");
    saveBtn.setOnClickListener(v -> {
      com.jepongdevxyz.browser.util.GeminiAi.saveKeys(a.prefs, keyInput.getText().toString());
      Ui.toast(a, "API keys saved");
    });
    btnRow.addView(saveBtn);
    root.addView(btnRow);

    Ui.dialog(a).setView(scroll).setPositiveButton("Done", null).show();
  }

}
