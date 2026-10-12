package com.jepongdevxyz.browser;

import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jepongdevxyz.browser.util.GeminiAi;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in AI assistant panel (like Brave's Leo AI). Chat with Gemini,
 * summarize the current page, and run quick actions — all inside the browser.
 */
public final class AiChatPanel {
  private final MainActivity a;
  private BottomSheetDialog dialog;
  private LinearLayout messagesBox;
  private ScrollView messagesScroll;
  private EditText input;
  private final List<GeminiAi.Message> history = new ArrayList<>();
  private boolean waiting = false;

  public AiChatPanel(MainActivity activity) { a = activity; }

  public void show() {
    if (dialog == null) build();
    dialog.show();
  }

  public void dismiss() { if (dialog != null) dialog.dismiss(); }

  private void build() {
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = a.dp(16);
    root.setPadding(pad, pad, pad, pad);

    // Header
    LinearLayout header = new LinearLayout(a);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = new TextView(a);
    title.setText("\u2728 JepongDevxyz AI");
    title.setTextSize(18);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(a.getColor(R.color.text));
    header.addView(title, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    TextView newChat = new TextView(a);
    newChat.setText("New chat");
    newChat.setTextSize(13);
    newChat.setTextColor(a.getColor(R.color.orange));
    newChat.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
    newChat.setOnClickListener(v -> { history.clear(); renderMessages(); });
    header.addView(newChat);
    root.addView(header);

    // Quick actions row
    LinearLayout quick = new LinearLayout(a);
    quick.setOrientation(LinearLayout.HORIZONTAL);
    quick.setPadding(0, a.dp(8), 0, a.dp(8));
    String[] actions = {"Summarize page", "Explain", "Key points"};
    for (String act : actions) {
      TextView chip = new TextView(a);
      chip.setText(act);
      chip.setTextSize(13);
      chip.setTextColor(a.getColor(R.color.text));
      chip.setBackgroundResource(R.drawable.toolbar_chip_bg);
      chip.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(0, 0, a.dp(8), 0);
      chip.setLayoutParams(lp);
      chip.setOnClickListener(v -> runQuickAction(act));
      quick.addView(chip);
    }
    root.addView(quick);

    // Messages
    messagesScroll = new ScrollView(a);
    messagesBox = new LinearLayout(a);
    messagesBox.setOrientation(LinearLayout.VERTICAL);
    messagesScroll.addView(messagesBox, new ScrollView.LayoutParams(
        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
    mlp.setMargins(0, a.dp(4), 0, a.dp(8));
    root.addView(messagesScroll, mlp);
    renderMessages();

    // Input row
    LinearLayout inputRow = new LinearLayout(a);
    inputRow.setOrientation(LinearLayout.HORIZONTAL);
    inputRow.setGravity(Gravity.CENTER_VERTICAL);
    input = new EditText(a);
    input.setHint("Ask JepongDevxyz AI…");
    input.setTextSize(15);
    input.setTextColor(a.getColor(R.color.text));
    input.setHintTextColor(a.getColor(R.color.muted));
    input.setBackgroundResource(R.drawable.search_bg);
    input.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
    inputRow.addView(input, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    ImageButton send = new ImageButton(a);
    send.setImageResource(R.drawable.ic_send);
    send.setBackground(null);
    send.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
    send.setOnClickListener(v -> sendCurrent());
    inputRow.addView(send);
    input.setOnEditorActionListener((v, actionId, e) -> {
      if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
        sendCurrent();
        return true;
      }
      return false;
    });
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
    root.addView(inputRow);

    // API key hint
    if (GeminiAi.getKeys(a.prefs).isEmpty()) {
      TextView hint = new TextView(a);
      hint.setText("Add your Gemini API key in Settings → AI Assistant to start chatting.");
      hint.setTextSize(12);
      hint.setTextColor(a.getColor(R.color.muted));
      hint.setPadding(0, a.dp(8), 0, 0);
      hint.setOnClickListener(v -> { dismiss(); a.settingsScreens.showAiSettings(); });
      root.addView(hint);
    }

    dialog = new BottomSheetDialog(a);
    dialog.setContentView(root);
  }

  private void renderMessages() {
    messagesBox.removeAllViews();
    if (history.isEmpty()) {
      TextView empty = new TextView(a);
      empty.setText("Ask anything, or tap a quick action above to analyze the current page.");
      empty.setTextSize(14);
      empty.setTextColor(a.getColor(R.color.muted));
      empty.setPadding(0, a.dp(24), 0, a.dp(24));
      empty.setGravity(Gravity.CENTER);
      messagesBox.addView(empty);
      return;
    }
    for (GeminiAi.Message m : history) addBubble(m.user, m.text);
    scrollToBottom();
  }

  private void addBubble(boolean user, String text) {
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, a.dp(4), 0, a.dp(4));
    TextView bubble = new TextView(a);
    bubble.setText(text);
    bubble.setTextSize(14);
    bubble.setTextColor(a.getColor(R.color.text));
    bubble.setBackgroundResource(user ? R.drawable.bubble_user : R.drawable.bubble_ai);
    bubble.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.weight = 0;
    if (user) {
      row.setGravity(Gravity.END);
      lp.setMargins(a.dp(48), 0, 0, 0);
    } else {
      row.setGravity(Gravity.START);
      lp.setMargins(0, 0, a.dp(48), 0);
    }
    // Cap bubble width
    lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
    bubble.setLayoutParams(lp);
    bubble.setMaxWidth((int) (a.getResources().getDisplayMetrics().widthPixels * 0.78));
    row.addView(bubble);
    messagesBox.addView(row);
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

  private void runQuickAction(String action) {
    if (waiting) return;
    if (action.equals("Summarize page")) {
      history.add(new GeminiAi.Message(true, "Summarize this page"));
      renderMessages();
      // Grab page text via JS, then ask AI
      getPageText(pageText -> {
        String prompt = "Summarize the following web page in 3-5 bullet points:\n\n"
            + (pageText.length() > 12000 ? pageText.substring(0, 12000) : pageText);
        askAi(prompt);
      });
    } else if (action.equals("Explain")) {
      history.add(new GeminiAi.Message(true, "Explain this page simply"));
      renderMessages();
      getPageText(pageText -> {
        String prompt = "Explain the following web page content in simple terms:\n\n"
            + (pageText.length() > 12000 ? pageText.substring(0, 12000) : pageText);
        askAi(prompt);
      });
    } else {
      history.add(new GeminiAi.Message(true, "List the key points of this page"));
      renderMessages();
      getPageText(pageText -> {
        String prompt = "List the key points of the following web page:\n\n"
            + (pageText.length() > 12000 ? pageText.substring(0, 12000) : pageText);
        askAi(prompt);
      });
    }
  }

  private void askAi(String overridePrompt) {
    waiting = true;
    addTyping();
    List<GeminiAi.Message> toSend = new ArrayList<>(history);
    if (overridePrompt != null) {
      // Replace last user message with the full prompt (page text included)
      toSend.set(toSend.size() - 1, new GeminiAi.Message(true, overridePrompt));
    }
    String system = "You are JepongDevxyz AI, a helpful assistant built into DevxyzBrowser. "
        + "Answer concisely and in the user's language (Tagalog or English).";
    GeminiAi.chat(a.prefs, toSend, system, new GeminiAi.ChatCallback() {
      @Override public void onReply(String text) {
        waiting = false;
        removeTyping();
        history.add(new GeminiAi.Message(false, text));
        renderMessages();
      }
      @Override public void onError(String message) {
        waiting = false;
        removeTyping();
        history.add(new GeminiAi.Message(false, "⚠ " + message));
        renderMessages();
      }
    });
  }

  private TextView typingView = null;
  private void addTyping() {
    typingView = new TextView(a);
    typingView.setText("Thinking…");
    typingView.setTextSize(14);
    typingView.setTextColor(a.getColor(R.color.muted));
    typingView.setPadding(0, a.dp(8), 0, a.dp(8));
    messagesBox.addView(typingView);
    scrollToBottom();
  }
  private void removeTyping() {
    if (typingView != null) messagesBox.removeView(typingView);
    typingView = null;
  }

  private void getPageText(java.util.function.Consumer<String> cb) {
    MainActivity.BrowserTab t = a.activeTab;
    if (t == null || t.session == null) { cb.accept(""); return; }
    t.session.evaluateJS("document.body?document.body.innerText.slice(0,20000):''",
        r -> a.runOnUiThread(() -> cb.accept(r != null ? r.toString() : "")),
        e -> a.runOnUiThread(() -> cb.accept("")));
  }

}
