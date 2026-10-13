package com.jepongdevxyz.browser;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jepongdevxyz.browser.util.GeminiAi;
import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-perfect Brave Leo-style AI chat.
 * - Header: ☰ | JepongDevxyz AI | ✎ | ⋮
 * - Input box: [✦][🎤][📎] Ask follow up [Automatic ▾][➤]
 * - Model bottom sheet, attach popup, quick-actions drawer,
 *   conversations drawer, advanced settings page.
 */
public class AiChatActivity extends Activity {
  private SharedPreferences prefs;
  private LinearLayout messagesBox;
  private ScrollView messagesScroll;
  private EditText input;
  private TextView modelLabel;
  private LinearLayout suggestionsBox;
  private FrameLayout drawerContainer;
  private LinearLayout drawerPanel;
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

  private void buildUi() {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(0xFF14141F);
    // Handle system bars: status bar on top, nav bar on bottom
    if (android.os.Build.VERSION.SDK_INT >= 20) {
      root.setOnApplyWindowInsetsListener((v, insets) -> {
        int top = insets.getSystemWindowInsetTop();
        int bottom = insets.getSystemWindowInsetBottom();
        v.setPadding(0, top, 0, bottom);
        return insets.consumeSystemWindowInsets();
      });
    }

    LinearLayout main = new LinearLayout(this);
    main.setOrientation(LinearLayout.VERTICAL);

    // Header
    LinearLayout topBar = new LinearLayout(this);
    topBar.setOrientation(LinearLayout.HORIZONTAL);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(2), dp(6), dp(2), dp(6));
    ImageButton menuBtn = iconBtn(R.drawable.ic_ai_menu);
    menuBtn.setOnClickListener(v -> openConversationsDrawer());
    topBar.addView(menuBtn);
    TextView title = new TextView(this);
    title.setText("JepongDevxyz AI");
    title.setTextSize(17);
    title.setTypeface(title.getTypeface(), Typeface.BOLD);
    title.setTextColor(0xFFFFFFFF);
    title.setPadding(dp(6), 0, dp(6), 0);
    topBar.addView(title, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    ImageButton newBtn = iconBtn(R.drawable.ic_ai_edit);
    newBtn.setOnClickListener(v -> newConversation());
    topBar.addView(newBtn);
    ImageButton moreBtn = iconBtn(R.drawable.ic_ai_more);
    moreBtn.setOnClickListener(v -> showChatMenu());
    topBar.addView(moreBtn);
    main.addView(topBar);
    main.addView(divider());

    // Messages
    messagesScroll = new ScrollView(this);
    messagesBox = new LinearLayout(this);
    messagesBox.setOrientation(LinearLayout.VERTICAL);
    messagesBox.setPadding(dp(16), dp(12), dp(16), dp(12));
    messagesScroll.addView(messagesBox, new ScrollView.LayoutParams(
        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    main.addView(messagesScroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    // Suggestion chips (new conversation only)
    suggestionsBox = new LinearLayout(this);
    suggestionsBox.setOrientation(LinearLayout.VERTICAL);
    suggestionsBox.setPadding(dp(16), 0, dp(16), dp(8));
    String[] suggs = {"Develop a marketing strategy", "Formulate a business proposal",
        "Write a memo", "Craft a professional email"};
    for (String s : suggs) {
      LinearLayout chipRow = new LinearLayout(this);
      chipRow.setOrientation(LinearLayout.HORIZONTAL);
      chipRow.setGravity(Gravity.CENTER_VERTICAL);
      chipRow.setPadding(0, dp(7), 0, dp(7));
      ImageButton ic = new ImageButton(this);
      ic.setImageResource(R.drawable.ic_ai_chat);
      ic.setBackground(null);
      ic.setPadding(0, 0, dp(10), 0);
      ic.setColorFilter(0xFF8A87A0);
      chipRow.addView(ic);
      TextView tv = new TextView(this);
      tv.setText(s);
      tv.setTextSize(15);
      tv.setTextColor(0xFFFFFFFF);
      chipRow.addView(tv);
      chipRow.setOnClickListener(v -> { input.setText(s); sendCurrent(); });
      suggestionsBox.addView(chipRow);
    }
    main.addView(suggestionsBox);
    renderMessages();

    // Input box (like Leo: everything inside one rounded box)
    LinearLayout inputWrap = new LinearLayout(this);
    inputWrap.setOrientation(LinearLayout.VERTICAL);
    inputWrap.setPadding(dp(12), dp(4), dp(12), dp(14));
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setBackgroundResource(R.drawable.leo_input_bg);
    box.setPadding(dp(6), dp(6), dp(6), dp(6));

    input = new EditText(this);
    input.setHint("Ask follow up");
    input.setTextSize(16);
    input.setTextColor(0xFFFFFFFF);
    input.setHintTextColor(0xFF8A87A0);
    input.setBackground(null);
    input.setPadding(dp(10), dp(10), dp(10), dp(4));
    box.addView(input, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    LinearLayout iconRow = new LinearLayout(this);
    iconRow.setOrientation(LinearLayout.HORIZONTAL);
    iconRow.setGravity(Gravity.CENTER_VERTICAL);
    iconRow.setPadding(dp(2), dp(2), dp(6), dp(2));

    ImageButton sparkBtn = iconBtn(R.drawable.ic_ai_sparkle);
    sparkBtn.setOnClickListener(v -> openQuickActionsDrawer());
    iconRow.addView(sparkBtn);
    ImageButton micBtn = iconBtn(R.drawable.ic_ai_mic);
    micBtn.setOnClickListener(v -> Ui.toast(this, "Voice input coming soon"));
    iconRow.addView(micBtn);
    ImageButton attachBtn = iconBtn(R.drawable.ic_ai_clip);
    attachBtn.setOnClickListener(v -> showAttachMenu());
    iconRow.addView(attachBtn);

    TextView spacer = new TextView(this);
    iconRow.addView(spacer, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));

    modelLabel = new TextView(this);
    updateModelLabel();
    modelLabel.setTextSize(13);
    modelLabel.setTextColor(0xFFB0ADC7);
    modelLabel.setPadding(dp(4), dp(8), dp(4), dp(8));
    modelLabel.setSingleLine(true);
    modelLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
    modelLabel.setMaxWidth(dp(150));
    modelLabel.setOnClickListener(v -> showModelSheet());
    iconRow.addView(modelLabel);

    ImageButton sendBtn = new ImageButton(this);
    sendBtn.setImageResource(R.drawable.ic_ai_send);
    sendBtn.setBackgroundResource(R.drawable.send_btn_bg);
    sendBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
    sendBtn.setColorFilter(0xFFFFFFFF);
    int sz = dp(40);
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
    slp.setMargins(dp(4), 0, dp(2), 0);
    sendBtn.setLayoutParams(slp);
    sendBtn.setOnClickListener(v -> sendCurrent());
    iconRow.addView(sendBtn);
    box.addView(iconRow);
    inputWrap.addView(box);
    main.addView(inputWrap);

    root.addView(main, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    // Drawer container (for conversations / quick actions)
    drawerContainer = new FrameLayout(this);
    drawerContainer.setVisibility(View.GONE);
    drawerContainer.setBackgroundColor(0x99000000);
    drawerContainer.setOnClickListener(v -> closeDrawer());
    drawerPanel = new LinearLayout(this);
    drawerPanel.setOrientation(LinearLayout.VERTICAL);
    drawerPanel.setBackgroundColor(0xFF1A1A26);
    FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
        (int) (getResources().getDisplayMetrics().widthPixels * 0.78),
        FrameLayout.LayoutParams.MATCH_PARENT);
    dlp.gravity = Gravity.START;
    drawerContainer.addView(drawerPanel, dlp);
    // Prevent clicks on panel from closing
    drawerPanel.setOnClickListener(v -> {});
    root.addView(drawerContainer, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    setContentView(root);
  }

  private ImageButton iconBtn(int resId) {
    ImageButton btn = new ImageButton(this);
    btn.setImageResource(resId);
    btn.setBackground(null);
    btn.setPadding(dp(10), dp(10), dp(10), dp(10));
    btn.setColorFilter(0xFFFFFFFF);
    return btn;
  }

  private View divider() {
    View v = new View(this);
    v.setBackgroundColor(0xFF2A2A3A);
    v.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    return v;
  }

  private void updateModelLabel() {
    String m = prefs.getString("gemini_model", "Auto");
    modelLabel.setText(m.equals("Auto") ? "Automatic ▾" : m + " ▾");
  }

  // ============ Messages ============
  /** Converts markdown links [text](url) to plain clickable URLs. */
  private android.text.SpannableString linkifyText(String text) {
    // Replace [text](url) with just url (Linkify will make it clickable)
    String cleaned = text.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$2");
    // Remove **bold** markers
    cleaned = cleaned.replace("**", "");
    return new android.text.SpannableString(cleaned);
  }

  private void renderMessages() {
    messagesBox.removeAllViews();
    if (history.isEmpty()) {
      suggestionsBox.setVisibility(View.VISIBLE);
      return;
    }
    suggestionsBox.setVisibility(View.GONE);
    for (GeminiAi.Message m : history) {
      if (m.user) {
        TextView b = new TextView(this);
        b.setText(m.text);
        b.setTextSize(15);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundResource(R.drawable.bubble_user);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(dp(48), dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        messagesBox.addView(b);
      } else {
        TextView b = new TextView(this);
        b.setText(linkifyText(m.text));
        b.setTextSize(15);
        b.setTextColor(0xFFFFFFFF);
        b.setLineSpacing(dp(2), 1);
        b.setPadding(dp(2), dp(8), dp(2), dp(8));
        // Make URLs clickable
        b.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        b.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        b.setLinkTextColor(0xFF7C9CFF);
        messagesBox.addView(b);
      }
    }
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

  private LinearLayout thinkingCard;
  private TextView thinkingSteps;
  private boolean thinkingExpanded = false;
  private final java.util.List<String> thinkingLog = new java.util.ArrayList<>();

  private void askAi(String overridePrompt) {
    waiting = true;
    thinkingLog.clear();
    thinkingLog.add("Understanding your question...");
    thinkingCard = buildThinkingCard();
    messagesBox.addView(thinkingCard);
    messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
    // Simulate detailed steps
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
      if (!waiting || thinkingSteps == null) return;
      thinkingLog.add("Searching knowledge base...");
      updateThinkingCard();
    }, 800);
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
      if (!waiting || thinkingSteps == null) return;
      thinkingLog.add("Generating response...");
      updateThinkingCard();
    }, 1600);

    List<GeminiAi.Message> toSend = new ArrayList<>(history);
    if (overridePrompt != null)
      toSend.set(toSend.size() - 1, new GeminiAi.Message(true, overridePrompt));
    String system = "You are JepongDevxyz AI, the built-in assistant of DevxyzBrowser. "
        + "RULES: (1) You do NOT write code and do NOT know programming. If the user asks you to code, "
        + "write a program, or asks if you can code, politely decline and refer them to JepongDevxyz AI Official "
        + "at https://jepong-devxyz-ai.vercel.app/ which is the full AI that can code. "
        + "(2) You are an expert on every corner of DevxyzBrowser: tabs, bookmarks, history, downloads, "
        + "extensions, built-in free VPN with VPNGate servers, Shields privacy, Appearance, default browser "
        + "setting, incognito tabs, find in page, desktop site, translate, and the AI assistant itself. "
        + "Answer concisely and in the user's language (Tagalog or English).";
    GeminiAi.chat(prefs, toSend, system, new GeminiAi.ChatCallback() {
      @Override public void onReply(String text) {
        waiting = false;
        history.add(new GeminiAi.Message(false, text));
        saveConversation();
        renderMessages();
      }
      @Override public void onError(String message) {
        waiting = false;
        history.add(new GeminiAi.Message(false, "⚠ " + message));
        renderMessages();
      }
    });
  }

  /** Builds the expandable "thinking" status card like Brave Leo's sandbox rows. */
  private LinearLayout buildThinkingCard() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackgroundResource(R.drawable.leo_popup_bg);
    card.setPadding(dp(12), dp(10), dp(12), dp(10));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, dp(4), 0, dp(4));
    card.setLayoutParams(lp);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView spinner = new TextView(this);
    spinner.setText("\u25CC");
    spinner.setTextSize(16);
    spinner.setTextColor(0xFF7C9CFF);
    spinner.setPadding(0, 0, dp(8), 0);
    header.addView(spinner);
    TextView title = new TextView(this);
    title.setText("Thinking to help with your query");
    title.setTextSize(14);
    title.setTextColor(0xFF7C9CFF);
    header.addView(title, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    TextView arrow = new TextView(this);
    arrow.setText(thinkingExpanded ? "\u25B2" : "\u25BC");
    arrow.setTextSize(12);
    arrow.setTextColor(0xFF8A87A0);
    arrow.setPadding(dp(8), 0, 0, 0);
    header.addView(arrow);
    card.addView(header);

    thinkingSteps = new TextView(this);
    thinkingSteps.setTextSize(13);
    thinkingSteps.setTextColor(0xFFB0ADC7);
    thinkingSteps.setPadding(dp(24), dp(4), 0, 0);
    thinkingSteps.setVisibility(thinkingExpanded ? View.VISIBLE : View.GONE);
    card.addView(thinkingSteps);
    updateThinkingCard();

    header.setOnClickListener(v -> {
      thinkingExpanded = !thinkingExpanded;
      arrow.setText(thinkingExpanded ? "\u25B2" : "\u25BC");
      thinkingSteps.setVisibility(thinkingExpanded ? View.VISIBLE : View.GONE);
    });
    return card;
  }

  private void updateThinkingCard() {
    if (thinkingSteps == null) return;
    StringBuilder sb = new StringBuilder();
    for (String s : thinkingLog) sb.append("\u2022 ").append(s).append("\n");
    thinkingSteps.setText(sb.toString().trim());
  }

  private void newConversation() {
    history.clear();
    conversationId = "conv_" + System.currentTimeMillis();
    renderMessages();
    closeDrawer();
  }

  private void saveConversation() {
    StringBuilder sb = new StringBuilder();
    for (GeminiAi.Message m : history)
      sb.append(m.user ? "U:" : "A:").append(m.text.replace("\n", " ")).append("\n");
    prefs.edit().putString("ai_conv_" + conversationId, sb.toString()).apply();
    java.util.Set<String> ids = new java.util.HashSet<>(
        prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>()));
    ids.add(conversationId);
    prefs.edit().putStringSet("ai_conv_ids", ids).apply();
  }

  // ============ Drawer helpers ============
  private void openDrawer() {
    drawerPanel.removeAllViews();
    drawerContainer.setVisibility(View.VISIBLE);
  }
  private void closeDrawer() { drawerContainer.setVisibility(View.GONE); }

  private TextView drawerTitle(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextSize(16);
    tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
    tv.setTextColor(0xFFFFFFFF);
    tv.setPadding(dp(16), dp(16), dp(16), dp(8));
    return tv;
  }

  private TextView drawerItem(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextSize(15);
    tv.setTextColor(0xFFFFFFFF);
    tv.setPadding(dp(16), dp(12), dp(16), dp(12));
    return tv;
  }

  private TextView drawerSection(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextSize(13);
    tv.setTextColor(0xFF8A87A0);
    tv.setPadding(dp(16), dp(12), dp(16), dp(4));
    return tv;
  }

  // ============ Conversations drawer ============
  private void openConversationsDrawer() {
    openDrawer();
    EditText search = new EditText(this);
    search.setHint("Filter conversations");
    search.setTextSize(14);
    search.setTextColor(0xFFFFFFFF);
    search.setHintTextColor(0xFF8A87A0);
    search.setBackgroundResource(R.drawable.leo_input_bg);
    search.setPadding(dp(14), dp(10), dp(14), dp(10));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    slp.setMargins(dp(12), dp(12), dp(12), dp(4));
    drawerPanel.addView(search, slp);

    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    drawerPanel.addView(list);

    TextView newRow = drawerItem("New conversation");
    newRow.setTextColor(0xFFE04A3A);
    newRow.setOnClickListener(v -> newConversation());
    list.addView(newRow);

    java.util.Set<String> ids = prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>());
    for (String id : ids) {
      String data = prefs.getString("ai_conv_" + id, "");
      String preview = data.isEmpty() ? "(empty)" :
          data.replace("U:", "").replace("A:", "").trim();
      if (preview.length() > 50) preview = preview.substring(0, 50) + "…";
      TextView row = drawerItem(preview);
      final String fid = id;
      row.setOnClickListener(v -> {
        conversationId = fid;
        history.clear();
        renderMessages();
        closeDrawer();
        Ui.toast(this, "Conversation loaded");
      });
      row.setOnLongClickListener(v -> {
        prefs.edit().remove("ai_conv_" + fid).apply();
        java.util.Set<String> nids = new java.util.HashSet<>(
            prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>()));
        nids.remove(fid);
        prefs.edit().putStringSet("ai_conv_ids", nids).apply();
        openConversationsDrawer();
        return true;
      });
      list.addView(row);
    }
    search.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(android.text.Editable s) {
        String f = s.toString().toLowerCase();
        for (int i = 1; i < list.getChildCount(); i++) {
          View child = list.getChildAt(i);
          if (child instanceof TextView) {
            String t = ((TextView) child).getText().toString().toLowerCase();
            child.setVisibility(t.contains(f) ? View.VISIBLE : View.GONE);
          }
        }
      }
    });
  }

  // ============ Quick actions drawer ============
  private void openQuickActionsDrawer() {
    openDrawer();
    drawerPanel.addView(drawerTitle("Quick actions  ^"));
    String[] actions = {"Explain", "Rewrite", "Paraphrase", "Improve"};
    for (String a : actions) {
      TextView row = drawerItem(a);
      row.setOnClickListener(v -> { closeDrawer(); input.setText(a + ": "); input.requestFocus(); });
      drawerPanel.addView(row);
    }
    drawerPanel.addView(drawerSection("Change tone"));
    String[] tones = {"Academic", "Professional", "Persuasive", "Casual", "Funny", "Shorten", "Expand"};
    for (String t : tones) {
      TextView row = drawerItem("Change tone / " + t);
      row.setOnClickListener(v -> { closeDrawer(); input.setText("Change tone / " + t + ": "); input.requestFocus(); });
      drawerPanel.addView(row);
    }
    drawerPanel.addView(drawerSection("Create"));
    TextView tagline = drawerItem("Tagline");
    tagline.setOnClickListener(v -> { closeDrawer(); input.setText("Tagline: "); input.requestFocus(); });
    drawerPanel.addView(tagline);
    drawerPanel.addView(drawerSection("Social media"));
    for (String s : new String[]{"Short post", "Long post"}) {
      TextView row = drawerItem("Social media / " + s);
      row.setOnClickListener(v -> { closeDrawer(); input.setText("Social media / " + s + ": "); input.requestFocus(); });
      drawerPanel.addView(row);
    }
    TextView newRow = drawerItem("+ New");
    newRow.setPadding(dp(16), dp(16), dp(16), dp(16));
    drawerPanel.addView(newRow);
  }

  // ============ Model bottom sheet ============
  private void showModelSheet() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(8), dp(8), dp(8), dp(16));

    root.addView(modelRow("sparkle", "Automatic", "Current",
        "Model changes dynamically to use the best one for the task", true));
    root.addView(modelRow("dot", "gemini-3.5-flash-lite", null, "Fast • Recommended", false));
    root.addView(modelRow("dot", "gemini-3.1-flash-lite", null, "Fast • Budget", false));

    TextView all = new TextView(this);
    all.setText("Show all models  >");
    all.setTextSize(15);
    all.setTextColor(0xFFFFFFFF);
    all.setPadding(dp(16), dp(14), dp(16), dp(6));
    all.setOnClickListener(v -> Ui.toast(this, "3 models available: Automatic, gemini-3.5-flash-lite, gemini-3.1-flash-lite"));
    root.addView(all);

    BottomSheetDialog dialog = new BottomSheetDialog(this);
    dialog.setContentView(root);
    // Store dialog ref for dismissal in modelRow clicks
    root.setTag(dialog);
    dialog.show();
  }

  private View modelRow(String icon, String name, String badge, String desc, boolean isAuto) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(12), dp(12), dp(12), dp(12));
    ImageButton ic = new ImageButton(this);
    ic.setImageResource(icon.equals("sparkle") ? R.drawable.ic_ai_sparkle : R.drawable.ic_ai_chat);
    ic.setBackground(null);
    ic.setPadding(0, 0, dp(12), 0);
    ic.setColorFilter(icon.equals("sparkle") ? 0xFFE04A3A : 0xFF8A87A0);
    row.addView(ic);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    LinearLayout nameRow = new LinearLayout(this);
    nameRow.setOrientation(LinearLayout.HORIZONTAL);
    nameRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView nm = new TextView(this);
    nm.setText(name);
    nm.setTextSize(16);
    nm.setTypeface(nm.getTypeface(), Typeface.BOLD);
    nm.setTextColor(0xFFFFFFFF);
    nameRow.addView(nm);
    if (badge != null) {
      TextView bd = new TextView(this);
      bd.setText(badge);
      bd.setTextSize(11);
      bd.setTextColor(0xFFFFFFFF);
      bd.setBackgroundResource(R.drawable.badge_bg);
      bd.setPadding(dp(8), dp(3), dp(8), dp(3));
      LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      blp.setMargins(dp(8), 0, 0, 0);
      bd.setLayoutParams(blp);
      nameRow.addView(bd);
    }
    texts.addView(nameRow, tp);
    TextView ds = new TextView(this);
    ds.setText(desc);
    ds.setTextSize(13);
    ds.setTextColor(0xFF8A87A0);
    texts.addView(ds);
    row.addView(texts);
    final String modelValue = isAuto ? "Auto" : name;
    row.setOnClickListener(v -> {
      prefs.edit().putString("gemini_model", modelValue).apply();
      updateModelLabel();
      Ui.toast(this, "Model: " + (isAuto ? "Automatic" : name));
    });
    return row;
  }

  // ============ Attach popup (custom with icons, like Brave Leo) ============
  private void showAttachMenu() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundResource(R.drawable.leo_popup_bg);
    root.setPadding(dp(8), dp(8), dp(8), dp(8));
    String[] items = {"Attach file...", "Take a picture...", "Open tabs...", "Bookmarks...", "History..."};
    int[] icons = {R.drawable.ic_a_file, R.drawable.ic_a_camera, R.drawable.ic_a_tab,
        R.drawable.ic_a_bookmark, R.drawable.ic_a_history};
    android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
    for (int i = 0; i < items.length; i++) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(12), dp(12), dp(12));
      ImageButton ic = new ImageButton(this);
      ic.setImageResource(icons[i]);
      ic.setBackground(null);
      ic.setPadding(0, 0, dp(12), 0);
      row.addView(ic);
      TextView tv = new TextView(this);
      tv.setText(items[i]);
      tv.setTextSize(15);
      tv.setTextColor(0xFFFFFFFF);
      row.addView(tv);
      final String item = items[i];
      final android.app.Dialog d = dialog;
      row.setOnClickListener(v -> {
        d.dismiss();
        if (item.startsWith("Attach")) {
          Intent in = new Intent(Intent.ACTION_GET_CONTENT);
          in.setType("*/*");
          startActivity(Intent.createChooser(in, "Attach file"));
        } else if (item.startsWith("Take")) {
          try { startActivity(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)); }
          catch (Exception e) { Ui.toast(this, "No camera app"); }
        } else {
          Ui.toast(this, item + " — coming soon");
        }
      });
      root.addView(row);
    }
    dialog.setContentView(root);
    android.view.Window w = dialog.getWindow();
    if (w != null) {
      w.setGravity(Gravity.BOTTOM | Gravity.START);
      android.view.WindowManager.LayoutParams lp = w.getAttributes();
      lp.y = dp(120); lp.x = dp(16);
      w.setAttributes(lp);
    }
    dialog.show();
  }

  // ============ Chat ⋮ menu (custom popup with icons, like Brave Leo) ============
  private void showChatMenu() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundResource(R.drawable.leo_popup_bg);
    root.setPadding(dp(8), dp(8), dp(8), dp(8));
    String[] items = {"Copy entire conversation", "Rename conversation", "Delete conversation",
        "Manage shared conversations", "Go Premium", "Advanced Settings"};
    int[] icons = {R.drawable.ic_m_copy, R.drawable.ic_m_rename, R.drawable.ic_m_delete,
        R.drawable.ic_m_share, R.drawable.ic_m_premium, R.drawable.ic_m_settings};
    android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
    for (int i = 0; i < items.length; i++) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(12), dp(12), dp(12));
      ImageButton ic = new ImageButton(this);
      ic.setImageResource(icons[i]);
      ic.setBackground(null);
      ic.setPadding(0, 0, dp(12), 0);
      row.addView(ic);
      TextView tv = new TextView(this);
      tv.setText(items[i]);
      tv.setTextSize(15);
      tv.setTextColor(0xFFFFFFFF);
      row.addView(tv);
      final String item = items[i];
      final android.app.Dialog d = dialog;
      row.setOnClickListener(v -> { d.dismiss(); handleChatMenu(item); });
      root.addView(row);
    }
    dialog.setContentView(root);
    android.view.Window w = dialog.getWindow();
    if (w != null) {
      w.setGravity(Gravity.TOP | Gravity.END);
      android.view.WindowManager.LayoutParams lp = w.getAttributes();
      // Add status bar height so popup doesn't go under it
      int statusBar = 0;
      int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
      if (resId > 0) statusBar = getResources().getDimensionPixelSize(resId);
      lp.y = dp(60) + statusBar; lp.x = dp(8);
      w.setAttributes(lp);
    }
    dialog.show();
  }

  private void handleChatMenu(String item) {
    if (item.startsWith("Copy")) {
      StringBuilder sb = new StringBuilder();
      for (GeminiAi.Message m : history)
        sb.append(m.user ? "You: " : "AI: ").append(m.text).append("\n\n");
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (cm != null)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("conversation", sb.toString()));
      Ui.toast(this, "Conversation copied");
    } else if (item.startsWith("Delete")) {
      history.clear();
      renderMessages();
      Ui.toast(this, "Conversation deleted");
    } else if (item.startsWith("Advanced")) {
      showAdvancedSettings();
    } else {
      Ui.toast(this, item + " — coming soon");
    }
  }

  // ============ Advanced settings ============
  private void showAdvancedSettings() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF14141F);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(4), dp(8), dp(4), dp(8));
    ImageButton back = iconBtn(R.drawable.ic_ai_back);
    header.addView(back);
    TextView title = new TextView(this);
    title.setText("JepongDevxyz AI");
    title.setTextSize(18);
    title.setTypeface(title.getTypeface(), Typeface.BOLD);
    title.setTextColor(0xFFFFFFFF);
    header.addView(title, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    ImageButton close = iconBtn(R.drawable.ic_ai_close);
    header.addView(close);
    root.addView(header);
    root.addView(divider());

    ScrollView sv = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(16), dp(12), dp(16), dp(12));
    TextView desc = new TextView(this);
    desc.setText("JepongDevxyz AI is an AI-powered smart assistant, built right into the browser");
    desc.setTextSize(14);
    desc.setTextColor(0xFFB0ADC7);
    desc.setPadding(0, 0, 0, dp(12));
    body.addView(desc);

    addSettingToggle(body, "Show autocomplete suggestions in address bar", null, "ai_autocomplete", true);
    addSettingToggle(body, "Show in quick search engines bar",
        "Enables a shortcut to launch JepongDevxyz AI from the quick search engines bar.",
        "ai_quick_search", true);
    addSettingToggle(body, "Store my conversation history", null, "ai_store_history", true);

    TextView modelTitle = new TextView(this);
    modelTitle.setText("Default model for new conversations");
    modelTitle.setTextSize(15);
    modelTitle.setTextColor(0xFFFFFFFF);
    modelTitle.setPadding(dp(12), dp(14), dp(12), dp(2));
    modelTitle.setBackgroundResource(R.drawable.leo_setting_bg);
    body.addView(modelTitle);
    TextView modelVal = new TextView(this);
    modelVal.setText(prefs.getString("gemini_model", "Auto"));
    modelVal.setTextSize(14);
    modelVal.setTextColor(0xFF8A87A0);
    modelVal.setPadding(dp(12), dp(2), dp(12), dp(14));
    modelVal.setBackgroundResource(R.drawable.leo_setting_bg);
    modelVal.setOnClickListener(v -> showModelSheet());
    body.addView(modelVal);

    TextView subHead = new TextView(this);
    subHead.setText("Subscription");
    subHead.setTextSize(13);
    subHead.setTextColor(0xFF8A87A0);
    subHead.setPadding(0, dp(16), 0, dp(4));
    body.addView(subHead);

    TextView delAll = new TextView(this);
    delAll.setText("Delete all JepongDevxyz AI conversation data");
    delAll.setTextSize(15);
    delAll.setTextColor(0xFFFF5555);
    delAll.setPadding(dp(12), dp(14), dp(12), dp(14));
    delAll.setBackgroundResource(R.drawable.leo_setting_bg);
    delAll.setOnClickListener(v -> {
      java.util.Set<String> ids = prefs.getStringSet("ai_conv_ids", new java.util.HashSet<>());
      android.content.SharedPreferences.Editor e = prefs.edit();
      for (String id : ids) e.remove("ai_conv_" + id);
      e.remove("ai_conv_ids").apply();
      history.clear();
      renderMessages();
      Ui.toast(this, "All conversation data deleted");
    });
    body.addView(delAll);
    sv.addView(body);
    root.addView(sv);

    BottomSheetDialog dialog = new BottomSheetDialog(this);
    dialog.setContentView(root);
    back.setOnClickListener(v -> dialog.dismiss());
    close.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
  }

  private void addSettingToggle(LinearLayout parent, String title, String sub, String key, boolean def) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setBackgroundResource(R.drawable.leo_setting_bg);
    card.setPadding(dp(12), dp(12), dp(12), dp(12));
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView tv = new TextView(this);
    tv.setText(title);
    tv.setTextSize(15);
    tv.setTextColor(0xFFFFFFFF);
    texts.addView(tv);
    if (sub != null) {
      TextView st = new TextView(this);
      st.setText(sub);
      st.setTextSize(13);
      st.setTextColor(0xFF8A87A0);
      texts.addView(st);
    }
    card.addView(texts, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    android.widget.Switch sw = new android.widget.Switch(this);
    sw.setChecked(prefs.getBoolean(key, def));
    // Peach/orange tint like Brave Leo
    if (android.os.Build.VERSION.SDK_INT >= 21) {
      android.content.res.ColorStateList thumbTint = new android.content.res.ColorStateList(
        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
        new int[]{0xFFE8A87C, 0xFF8A87A0});
      sw.setThumbTintList(thumbTint);
      android.content.res.ColorStateList trackTint = new android.content.res.ColorStateList(
        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
        new int[]{0xFFD4814A, 0xFF3A3A4A});
      sw.setTrackTintList(trackTint);
    }
    sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
    card.addView(sw);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, 0, 0, dp(8));
    parent.addView(card, lp);
  }
}
