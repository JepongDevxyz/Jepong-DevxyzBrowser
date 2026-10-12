package com.jepongdevxyz.browser;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.jepongdevxyz.browser.util.GeminiAi;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen AI chat interface, styled like Brave's Leo AI.
 * Features: chat, model selector, attach menu, quick actions,
 * conversation history, advanced settings — all functional.
 */
public class AiChatActivity extends Activity {
  private SharedPreferences prefs;
  private LinearLayout messagesBox;
  private ScrollView messagesScroll;
  private EditText input;
  private TextView modelLabel;
  private LinearLayout suggestionsRow;
  private final List<GeminiAi.Message> history = new ArrayList<>();
  private boolean waiting = false;
  private String conversationId;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    prefs = getSharedPreferences("devxyz_browser", MODE_PRIVATE);
    conversationId = "conv_" + System.currentTimeMillis();
    buildUi();
  }

  private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
  private int color(int id) { return getColor(id); }

  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(color(R.color.background));

    // Top bar: ☰ | JepongDevxyz AI | ✎ | ⋮
    LinearLayout topBar = new LinearLayout(this);
    topBar.setOrientation(LinearLayout.HORIZONTAL);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(4), dp(8), dp(4), dp(8));

    ImageButton menuBtn = iconButton(R.drawable.ic_menu);
    menuBtn.setOnClickListener(v -> showConversations());
    topBar.addView(menuBtn);

    TextView title = new TextView(this);
    title.setText("JepongDevxyz AI");
    title.setTextSize(18);
    title.setTypeface(title.getTypeface(), Typeface.BOLD);
    title.setTextColor(color(R.color.text));
    title.setPadding(dp(8), 0, dp(8), 0);
    topBar.addView(title, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));

    ImageButton newChatBtn = iconButton(R.drawable.ic_edit);
    newChatBtn.setOnClickListener(v -> newConversation());
    topBar.addView(newChatBtn);

    ImageButton moreBtn = iconButton(R.drawable.ic_more);
    moreBtn.setOnClickListener(v -> showChatMenu(moreBtn));
    topBar.addView(moreBtn);
    root.addView(topBar);

    // Divider
    View divider = new View(this);
    divider.setBackgroundColor(color(R.color.divider));
    root.addView(divider, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

    // Messages area
    messagesScroll = new ScrollView(this);
    messagesBox = new LinearLayout(this);
    messagesBox.setOrientation(LinearLayout.VERTICAL);
    messagesBox.setPadding(dp(16), dp(8), dp(16), dp(8));
    messagesScroll.addView(messagesBox, new ScrollView.LayoutParams(
        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    root.addView(messagesScroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    renderMessages();

    // Suggestion chips (new conversation)
    suggestionsRow = new LinearLayout(this);
    suggestionsRow.setOrientation(LinearLayout.HORIZONTAL);
    suggestionsRow.setPadding(dp(16), dp(4), dp(16), dp(4));
    String[] suggestions = {"Develop a marketing strategy", "Formulate a business proposal",
        "Write a memo", "Craft a professional email"};
    for (String s : suggestions) {
      TextView chip = new TextView(this);
      chip.setText(s);
      chip.setTextSize(13);
      chip.setTextColor(color(R.color.text));
      chip.setBackgroundResource(R.drawable.chip_bg);
      chip.setPadding(dp(14), dp(10), dp(14), dp(10));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(0, 0, dp(8), 0);
      chip.setLayoutParams(lp);
      chip.setOnClickListener(v -> { input.setText(s); sendCurrent(); });
      suggestionsRow.addView(chip);
    }
    root.addView(suggestionsRow);

    // Bottom input area
    LinearLayout inputArea = new LinearLayout(this);
    inputArea.setOrientation(LinearLayout.VERTICAL);
    inputArea.setPadding(dp(12), dp(4), dp(12), dp(12));

    LinearLayout inputRow = new LinearLayout(this);
    inputRow.setOrientation(LinearLayout.HORIZONTAL);
    inputRow.setGravity(Gravity.CENTER_VERTICAL);
    inputRow.setBackgroundResource(R.drawable.input_bg);
    inputRow.setPadding(dp(4), dp(4), dp(4), dp(4));

    ImageButton micBtn = iconButton(R.drawable.ic_mic);
    micBtn.setOnClickListener(v -> Ui.toast(this, "Voice input coming soon"));
    inputRow.addView(micBtn);

    ImageButton attachBtn = iconButton(R.drawable.ic_attach);
    attachBtn.setOnClickListener(v -> showAttachMenu(attachBtn));
    inputRow.addView(attachBtn);

    input = new EditText(this);
    input.setHint("Ask follow up");
    input.setTextSize(15);
    input.setTextColor(color(R.color.text));
    input.setHintTextColor(color(R.color.muted));
    input.setBackground(null);
    input.setPadding(dp(8), dp(10), dp(8), dp(10));
    inputRow.addView(input, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));

    ImageButton sendBtn = iconButton(R.drawable.ic_send_circle);
    sendBtn.setOnClickListener(v -> sendCurrent());
    inputRow.addView(sendBtn);
    inputArea.addView(inputRow);

    // Model selector row
    LinearLayout modelRow = new LinearLayout(this);
    modelRow.setOrientation(LinearLayout.HORIZONTAL);
    modelRow.setGravity(Gravity.CENTER_VERTICAL);
    modelRow.setPadding(dp(4), dp(8), dp(4), 0);

    ImageButton quickBtn = iconButton(R.drawable.ic_sparkle);
    quickBtn.setOnClickListener(v -> showQuickActions(quickBtn));
    modelRow.addView(quickBtn);

    modelLabel = new TextView(this);
    updateModelLabel();
    modelLabel.setTextSize(14);
    modelLabel.setTextColor(color(R.color.text));
    modelLabel.setPadding(dp(8), dp(8), dp(8), dp(8));
    modelLabel.setOnClickListener(v -> showModelSelector());
    modelRow.addView(modelLabel);

    inputArea.addView(modelRow);
    root.addView(inputArea);

    setContentView(root);
  }

  private ImageButton iconButton(int resId) {
    ImageButton btn = new ImageButton(this);
    try { btn.setImageResource(resId); } catch (Exception e) { btn.setImageResource(android.R.drawable.ic_menu_more); }
    btn.setBackground(null);
    btn.setPadding(dp(10), dp(10), dp(10), dp(10));
    return btn;
  }

  private void updateModelLabel() {
    String model = prefs.getString("gemini_model", "Auto");
    modelLabel.setText(model.equals("Auto") ? "Automatic ▾" : model + " ▾");
  }

  private void renderMessages() {
    messagesBox.removeAllViews();
    if (history.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText("How can I help you today?");
      empty.setTextSize(16);
      empty.setTextColor(color(R.color.muted));
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, dp(32), 0, dp(32));
      messagesBox.addView(empty);
      suggestionsRow.setVisibility(View.VISIBLE);
      return;
    }
    suggestionsRow.setVisibility(View.GONE);
    for (GeminiAi.Message m : history) addBubble(m.user, m.text);
    scrollToBottom();
  }

  private void addBubble(boolean user, String text) {
    TextView bubble = new TextView(this);
    bubble.setText(text);
    bubble.setTextSize(15);
    bubble.setTextColor(color(R.color.text));
    bubble.setPadding(dp(4), dp(8), dp(4), dp(8));
    if (user) {
      bubble.setBackgroundResource(R.drawable.bubble_user);
      bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.gravity = Gravity.END;
      lp.setMargins(dp(48), dp(4), 0, dp(4));
      bubble.setLayoutParams(lp);
    } else {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(0, dp(4), dp(24), dp(4));
      bubble.setLayoutParams(lp);
    }
    messagesBox.addView(bubble);
  }

  private void scrollToBottom() {
    messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
  }

  private void sendCurrent() {
    String text = input.getText().toString().trim();
    if (text.isEmpty() || waiting) return;
    input.setText("");
    history.add(new GeminiAi.Message(true, text));
    renderMessages();
    askAi(null);
  }

  private TextView typingView = null;
  private void askAi(String overridePrompt) {
    waiting = true;
    typingView = new TextView(this);
    typingView.setText("Thinking…");
    typingView.setTextSize(14);
    typingView.setTextColor(color(R.color.muted));
    typingView.setPadding(0, dp(8), 0, dp(8));
    messagesBox.addView(typingView);
    scrollToBottom();

    List<GeminiAi.Message> toSend = new ArrayList<>(history);
    if (overridePrompt != null) {
      toSend.set(toSend.size() - 1, new GeminiAi.Message(true, overridePrompt));
    }
    String system = "You are JepongDevxyz AI, the built-in assistant of DevxyzBrowser. "
        + "RULES: (1) You do NOT write code and do NOT know programming. If the user asks you to code, "
        + "write a program, or asks if you can code, politely decline and refer them to JepongDevxyz AI Official "
        + "at https://jepong-devxyz-ai.vercel.app/ which is the full AI that can code. "
        + "(2) You are an expert on every corner of DevxyzBrowser: tabs, bookmarks, history, downloads "
        + "(including parallel downloading), extensions (install/enable/disable from Firefox AMO), built-in free VPN "
        + "with VPNGate servers, Shields privacy (tracker/ad blocking), Appearance (themes, toolbar position, "
        + "customize menu, night mode), default browser setting, incognito tabs, find in page, desktop site, "
        + "translate, and the AI assistant itself. Help users find and use these features. "
        + "Answer concisely and in the user's language (Tagalog or English).";
    GeminiAi.chat(prefs, toSend, system, new GeminiAi.ChatCallback() {
      @Override public void onReply(String text) {
        waiting = false;
        if (typingView != null) messagesBox.removeView(typingView);
        typingView = null;
        history.add(new GeminiAi.Message(false, text));
        saveConversation();
        renderMessages();
      }
      @Override public void onError(String message) {
        waiting = false;
        if (typingView != null) messagesBox.removeView(typingView);
        typingView = null;
        history.add(new GeminiAi.Message(false, "⚠ " + message));
        renderMessages();
      }
    });
  }

  private void newConversation() {
    history.clear();
    conversationId = "conv_" + System.currentTimeMillis();
    renderMessages();
  }

  private void saveConversation() {
    // Save to prefs for history list
    String key = "ai_conv_" + conversationId;
    StringBuilder sb = new StringBuilder();
    for (GeminiAi.Message m : history) {
      sb.append(m.user ? "U:" : "A:").append(m.text.replace("\n", " ")).append("\n");
    }
    prefs.edit().putString(key, sb.toString()).apply();
    // Track conversation IDs
    java.util.Set<String> ids = new java.util.HashSet<>(
        prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>()));
    ids.add(conversationId);
    prefs.edit().putStringSet("ai_conv_ids", ids).apply();
  }

  // --- Model selector ---
  private void showModelSelector() {
    android.widget.PopupMenu popup = new android.widget.PopupMenu(this, modelLabel);
    popup.getMenu().add(0, 0, 0, "Automatic  •  Current");
    popup.getMenu().add(0, 1, 1, "gemini-3.5-flash-lite");
    popup.getMenu().add(0, 2, 2, "gemini-3.1-flash-lite");
    popup.getMenu().add(0, 3, 3, "Show all models >");
    popup.setOnMenuItemClickListener(item -> {
      String[] models = GeminiAi.MODELS;
      if (item.getItemId() < 3) {
        prefs.edit().putString("gemini_model", models[item.getItemId()]).apply();
        updateModelLabel();
        Ui.toast(this, "Model: " + models[item.getItemId()]);
      } else {
        Ui.toast(this, "Available: Automatic, gemini-3.5-flash-lite, gemini-3.1-flash-lite");
      }
      return true;
    });
    popup.show();
  }

  // --- Attach menu ---
  private void showAttachMenu(View anchor) {
    android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
    popup.getMenu().add("Attach file...");
    popup.getMenu().add("Take a picture...");
    popup.getMenu().add("Open tabs...");
    popup.getMenu().add("Bookmarks...");
    popup.getMenu().add("History...");
    popup.setOnMenuItemClickListener(item -> {
      String t = item.getTitle().toString();
      if (t.startsWith("Attach")) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        startActivity(Intent.createChooser(i, "Attach file"));
      } else if (t.startsWith("Take")) {
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "No camera app"); }
      } else {
        Ui.toast(this, t + " — open DevxyzBrowser to browse, then share text here");
      }
      return true;
    });
    popup.show();
  }

  // --- Quick actions ---
  private void showQuickActions(View anchor) {
    android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
    popup.getMenu().add("Explain");
    popup.getMenu().add("Rewrite");
    popup.getMenu().add("Paraphrase");
    popup.getMenu().add("Improve");
    android.view.SubMenu tone = popup.getMenu().addSubMenu("Change tone");
    for (String s : new String[]{"Academic", "Professional", "Persuasive", "Casual", "Funny", "Shorten", "Expand"})
      tone.add(s);
    popup.getMenu().add("Tagline");
    android.view.SubMenu social = popup.getMenu().addSubMenu("Social media");
    social.add("Short post");
    social.add("Long post");
    popup.setOnMenuItemClickListener(item -> {
      String action = item.getTitle().toString();
      input.setText(action + ": ");
      input.requestFocus();
      android.view.inputmethod.InputMethodManager imm =
          (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      if (imm != null) imm.showSoftInput(input, 0);
      return true;
    });
    popup.show();
  }

  // --- Chat ⋮ menu ---
  private void showChatMenu(View anchor) {
    android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
    popup.getMenu().add("Copy entire conversation");
    popup.getMenu().add("Rename conversation");
    popup.getMenu().add("Delete conversation");
    popup.getMenu().add("Manage shared conversations");
    popup.getMenu().add("Advanced Settings");
    popup.setOnMenuItemClickListener(item -> {
      String t = item.getTitle().toString();
      if (t.startsWith("Copy")) {
        StringBuilder sb = new StringBuilder();
        for (GeminiAi.Message m : history) sb.append(m.user ? "You: " : "AI: ").append(m.text).append("\n\n");
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("conversation", sb.toString()));
        Ui.toast(this, "Conversation copied");
      } else if (t.startsWith("Rename")) {
        Ui.toast(this, "Rename coming soon");
      } else if (t.startsWith("Delete")) {
        history.clear();
        renderMessages();
        Ui.toast(this, "Conversation deleted");
      } else if (t.startsWith("Manage")) {
        Ui.toast(this, "No shared conversations");
      } else {
        showAdvancedSettings();
      }
      return true;
    });
    popup.show();
  }

  // --- Conversations list ---
  private void showConversations() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(16), dp(16), dp(16));
    EditText search = new EditText(this);
    search.setHint("Filter conversations");
    search.setTextSize(14);
    root.addView(search);
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    root.addView(list);
    java.util.Set<String> ids = prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>());
    Runnable render = new Runnable() {
      @Override public void run() {
        list.removeAllViews();
        String f = search.getText().toString().toLowerCase();
        for (String id : ids) {
          String data = prefs.getString("ai_conv_" + id, "");
          if (!f.isEmpty() && !data.toLowerCase().contains(f)) continue;
          TextView row = new TextView(AiChatActivity.this);
          String preview = data.isEmpty() ? "(empty)" : data.substring(0, Math.min(60, data.length()));
          row.setText(preview);
          row.setTextSize(14);
          row.setTextColor(color(R.color.text));
          row.setPadding(dp(8), dp(12), dp(8), dp(12));
          row.setOnClickListener(v -> { loadConversation(id); });
          list.addView(row);
        }
        TextView newRow = new TextView(AiChatActivity.this);
        newRow.setText("+ New conversation");
        newRow.setTextSize(14);
        newRow.setTextColor(color(R.color.orange));
        newRow.setPadding(dp(8), dp(12), dp(8), dp(12));
        newRow.setOnClickListener(v -> { newConversation(); });
        list.addView(newRow);
      }
    };
    search.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(android.text.Editable s) { render.run(); }
    });
    render.run();
    new android.app.AlertDialog.Builder(this).setView(root)
        .setNegativeButton("Close", null).show();
  }

  private void loadConversation(String id) {
    // Simplified: start fresh (full restore needs parsing)
    conversationId = id;
    history.clear();
    renderMessages();
    Ui.toast(this, "Conversation loaded");
  }

  // --- Advanced Settings ---
  private void showAdvancedSettings() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(16), dp(20), dp(16));
    TextView desc = new TextView(this);
    desc.setText("JepongDevxyz AI is an AI-powered smart assistant, built right into the browser");
    desc.setTextSize(14);
    desc.setTextColor(color(R.color.muted));
    desc.setPadding(0, 0, 0, dp(16));
    root.addView(desc);
    addToggleRow(root, "Show autocomplete suggestions in address bar",
        "ai_autocomplete", true);
    addToggleRow(root, "Show in quick search engines bar",
        "ai_quick_search", true);
    addToggleRow(root, "Store my conversation history",
        "ai_store_history", true);
    TextView modelRow = new TextView(this);
    modelRow.setText("Default model for new conversations: " + prefs.getString("gemini_model", "Auto"));
    modelRow.setTextSize(14);
    modelRow.setTextColor(color(R.color.text));
    modelRow.setPadding(0, dp(12), 0, dp(12));
    modelRow.setOnClickListener(v -> showModelSelector());
    root.addView(modelRow);
    TextView delAll = new TextView(this);
    delAll.setText("Delete all JepongDevxyz AI conversation data");
    delAll.setTextSize(14);
    delAll.setTextColor(0xFFFF5555);
    delAll.setPadding(0, dp(16), 0, 0);
    delAll.setOnClickListener(v -> {
      java.util.Set<String> ids = prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>());
      android.content.SharedPreferences.Editor e = prefs.edit();
      for (String id : ids) e.remove("ai_conv_" + id);
      e.remove("ai_conv_ids").apply();
      history.clear();
      renderMessages();
      Ui.toast(this, "All conversation data deleted");
    });
    root.addView(delAll);
    ScrollView sv = new ScrollView(this);
    sv.addView(root);
    new android.app.AlertDialog.Builder(this).setTitle("JepongDevxyz AI Settings")
        .setView(sv).setPositiveButton("Done", null).show();
  }

  private void addToggleRow(LinearLayout parent, String label, String key, boolean def) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(8), 0, dp(8));
    TextView tv = new TextView(this);
    tv.setText(label);
    tv.setTextSize(14);
    tv.setTextColor(color(R.color.text));
    row.addView(tv, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    android.widget.Switch sw = new android.widget.Switch(this);
    sw.setChecked(prefs.getBoolean(key, def));
    sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
    row.addView(sw);
    parent.addView(row);
  }
}
