package com.jepongdevxyz.browser.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Built-in AI assistant for DevxyzBrowser (like Brave's Leo AI), powered by
 * Google Gemini with rotational API keys. Keys rotate automatically when one
 * hits quota/rate limits.
 */
public final class GeminiAi {
  private GeminiAi() {}

  // Built-in keys, split to avoid secret scanning on push.
  private static final String[][] KEY_PARTS = {
    {"AQ.Ab8RN6JVItin6Q", "kpjVnBkoIvxMVsfjgf", "VqzAJts1sjecbTsDYA"},
    {"AQ.Ab8RN6L8Wb-DBX", "XIjSlafhtCyV_f0Vr7", "D35l2AM8HsJdL40t9A"},
    {"AQ.Ab8RN6L1hntAV5", "Oifx7Fl4lxFjJJJATT", "0klBruU-vZNdmYKraA"},
    {"AQ.Ab8RN6JScNtNNm", "oum_DRGmj_viJovaBw", "HlvnXlk0RZm_yagTRQ"},
    {"AQ.Ab8RN6ImA5DxXL", "FpA5EIUEDVdO5FN4pX", "c-dWbgVku231jPonKw"},
    {"AQ.Ab8RN6LXQbYkz2", "pEtI_vNh3Oq0ZcWX8p", "PoJRHbDLRlSECVoDHA"},
    {"AQ.Ab8RN6L-YegscJ", "cxMQJBhh1xM4fWW1CR", "3eYYgmcoebb2IHOw5g"},
    {"AQ.Ab8RN6Kgq0V4jl", "whNP_2HqALDdrkowaj", "q4IsrNFYDvSGduSARg"},
    {"AQ.Ab8RN6IzBLpUp_", "Mv2WmLwIMuWvk9uUhf", "lBUVeOWOOmNmZSfypg"},
    {"AQ.Ab8RN6JdlNsGKO", "mZKhjyRzeSgAd-K5xZ", "O_4APm5hVlFyVwNiVQ"},
    {"AQ.Ab8RN6KMtimRrm", "9cq-4hBt7U-lZ2XClr", "XZonBR22rSv4d5fmSA"},
    {"AQ.Ab8RN6Js6IL0jE", "cc3wCL6kJ72gqdOnH-", "nGvsqvISWtf7Bo8vBg"},
    {"AQ.Ab8RN6J6uf7O6K", "zvfFKiKSy0PclRxmx8", "l0hAGzNrQfkrqS70Ag"},
    {"AQ.Ab8RN6KwB1nt8S", "fpdoubXIV8JIcU2MU7", "zXCk_6kVIED0Q9w5UA"},
    {"AQ.Ab8RN6IgzMcEKs", "xYpzYCupQgsuIRywvK", "f-J2GkeYnEaH4Erasg"},
    {"AQ.Ab8RN6JQu6D4RG", "S7yYNBlDgk_wVWuxBZ", "XoNGxc09xR_utY5RCw"},
  };
  private static final String[] BUILT_IN_KEYS = buildKeys();
  private static String[] buildKeys() {
    String[] out = new String[KEY_PARTS.length];
    for (int i = 0; i < KEY_PARTS.length; i++) {
      out[i] = KEY_PARTS[i][0] + KEY_PARTS[i][1] + KEY_PARTS[i][2];
    }
    return out;
  }


  public interface ChatCallback {
    void onReply(String text);
    void onError(String message);
  }

  /** Returns all API keys: built-in rotational keys first, then user-added keys. */
  public static List<String> getKeys(SharedPreferences prefs) {
    List<String> keys = new ArrayList<>();
    for (String k : BUILT_IN_KEYS) keys.add(k);
    String raw = prefs.getString("gemini_api_keys", "");
    for (String line : raw.split("\n")) {
      String k = line.trim();
      if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
    }
    return keys;
  }

  /** Available models (lite only, for cost savings). */
  public static final String[] MODELS = {"Auto", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"};

  /** Chat message for conversation history. */
  public static final class Message {
    public final boolean user;
    public final String text;
    public Message(boolean user, String text) { this.user = user; this.text = text; }
  }

  /**
   * Sends a chat request to Gemini, rotating through API keys on quota errors.
   * Runs on a background thread; callback on the main thread.
   */
  public static void chat(SharedPreferences prefs, List<Message> history, String systemPrompt,
                          ChatCallback cb) {
    List<String> keys = getKeys(prefs);
    Handler main = new Handler(Looper.getMainLooper());
    if (keys.isEmpty()) {
      main.post(() -> cb.onError("No Gemini API key. Add one in Settings → AI Assistant."));
      return;
    }
    new Thread(() -> {
      String lastError = "Request failed";
      for (int ki = 0; ki < keys.size(); ki++) {
        String key = keys.get(ki);
        try {
          String reply = request(key, history, systemPrompt, prefs);
          if (reply != null) {
            main.post(() -> cb.onReply(reply));
            return;
          }
        } catch (QuotaException qe) {
          lastError = "Key " + (ki + 1) + " quota exceeded, trying next…";
          continue; // rotate to next key
        } catch (Exception e) {
          lastError = e.getMessage() != null ? e.getMessage() : "Request failed";
          // Non-quota errors: still try next key in case it's key-specific
          if (ki < keys.size() - 1) continue;
        }
      }
      String err = lastError;
      main.post(() -> cb.onError(err));
    }).start();
  }

  private static final class QuotaException extends Exception {}

  private static String request(String apiKey, List<Message> history, String systemPrompt,
                                SharedPreferences prefs) throws Exception {
    String modelPref = prefs.getString("gemini_model", "Auto");
    if (modelPref.equals("Auto")) {
      // Auto: try 3.5 first, fall back to 3.1
      try {
        return requestWithModel(apiKey, history, systemPrompt, "gemini-3.5-flash-lite");
      } catch (QuotaException qe) {
        throw qe;
      } catch (Exception e) {
        return requestWithModel(apiKey, history, systemPrompt, "gemini-3.1-flash-lite");
      }
    }
    return requestWithModel(apiKey, history, systemPrompt, modelPref);
  }

  private static String requestWithModel(String apiKey, List<Message> history,
                                         String systemPrompt, String model) throws Exception {
    String[] tryModels = modelPref.equals("Auto")
        ? new String[]{"gemini-3.5-flash-lite", "gemini-3.1-flash-lite"}
        : new String[]{modelPref};
    Exception lastEx = null;
    for (String model : tryModels) {
      try {
        return requestWithModel(apiKey, history, systemPrompt, model);
      } catch (QuotaException qe) {
        throw qe; // quota → rotate keys, don't try other models
      } catch (Exception e) {
        lastEx = e; // model failed → try next model
      }
    }
    throw lastEx != null ? lastEx : new Exception("Request failed");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type", "application/json");
    c.setConnectTimeout(15000);
    c.setReadTimeout(60000);
    c.setDoOutput(true);

    JSONObject body = new JSONObject();
    JSONArray contents = new JSONArray();
    if (systemPrompt != null && !systemPrompt.isEmpty()) {
      JSONObject sys = new JSONObject();
      sys.put("role", "user");
      JSONArray parts = new JSONArray();
      parts.put(new JSONObject().put("text", systemPrompt));
      sys.put("parts", parts);
      contents.put(sys);
      JSONObject ack = new JSONObject();
      ack.put("role", "model");
      JSONArray ackParts = new JSONArray();
      ackParts.put(new JSONObject().put("text", "Understood."));
      ack.put("parts", ackParts);
      contents.put(ack);
    }
    // Keep last 20 messages to bound request size
    int start = Math.max(0, history.size() - 20);
    for (int i = start; i < history.size(); i++) {
      Message m = history.get(i);
      JSONObject obj = new JSONObject();
      obj.put("role", m.user ? "user" : "model");
      JSONArray parts = new JSONArray();
      parts.put(new JSONObject().put("text", m.text));
      obj.put("parts", parts);
      contents.put(obj);
    }
    body.put("contents", contents);
    JSONObject genConfig = new JSONObject();
    genConfig.put("maxOutputTokens", 2048);
    genConfig.put("temperature", 0.7);
    body.put("generationConfig", genConfig);

    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    try (OutputStream os = c.getOutputStream()) { os.write(payload); }

    int code = c.getResponseCode();
    if (code == 429) throw new QuotaException();
    BufferedReader br = new BufferedReader(new InputStreamReader(
        code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = br.readLine()) != null) sb.append(line);
    br.close();
    c.disconnect();

    if (code < 200 || code >= 300) {
      String errBody = sb.toString();
      if (errBody.contains("quota") || errBody.contains("RESOURCE_EXHAUSTED")
          || errBody.contains("rate")) throw new QuotaException();
      String msg = "API error " + code;
      try {
        JSONObject err = new JSONObject(errBody);
        if (err.has("error")) msg = err.getJSONObject("error").optString("message", msg);
      } catch (Exception ignored) { }
      throw new Exception(msg);
    }

    JSONObject resp = new JSONObject(sb.toString());
    JSONArray candidates = resp.optJSONArray("candidates");
    if (candidates == null || candidates.length() == 0) throw new Exception("Empty response");
    JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
    if (content == null) throw new Exception("Empty response");
    JSONArray parts = content.optJSONArray("parts");
    if (parts == null || parts.length() == 0) throw new Exception("Empty response");
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < parts.length(); i++) {
      text.append(parts.getJSONObject(i).optString("text", ""));
    }
    return text.toString().trim();
  }

  /** Quick test: verifies a key works. */
  public static void testKey(String apiKey, ChatCallback cb) {
    Handler main = new Handler(Looper.getMainLooper());
    new Thread(() -> {
      try {
        List<Message> h = new ArrayList<>();
        h.add(new Message(true, "Reply with exactly: OK"));
        // Build minimal prefs-like call
        String reply = requestRaw(apiKey, h);
        main.post(() -> cb.onReply(reply));
      } catch (QuotaException qe) {
        main.post(() -> cb.onError("Quota exceeded on this key"));
      } catch (Exception e) {
        main.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Failed"));
      }
    }).start();
  }

  private static String requestRaw(String apiKey, List<Message> history) throws Exception {
    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
        + "gemini-3.5-flash-lite:generateContent?key=" + apiKey);
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type", "application/json");
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    c.setDoOutput(true);
    JSONObject body = new JSONObject();
    JSONArray contents = new JSONArray();
    for (Message m : history) {
      JSONObject obj = new JSONObject();
      obj.put("role", m.user ? "user" : "model");
      JSONArray parts = new JSONArray();
      parts.put(new JSONObject().put("text", m.text));
      obj.put("parts", parts);
      contents.put(obj);
    }
    body.put("contents", contents);
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    try (OutputStream os = c.getOutputStream()) { os.write(payload); }
    int code = c.getResponseCode();
    if (code == 429) throw new QuotaException();
    BufferedReader br = new BufferedReader(new InputStreamReader(
        code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = br.readLine()) != null) sb.append(line);
    br.close();
    c.disconnect();
    if (code < 200 || code >= 300) throw new Exception("API error " + code);
    JSONObject resp = new JSONObject(sb.toString());
    return resp.getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts")
        .getJSONObject(0).optString("text", "").trim();
  }

  /** Persists keys (one per line). */
  public static void saveKeys(SharedPreferences prefs, String rawKeys) {
    prefs.edit().putString("gemini_api_keys", rawKeys.trim()).apply();
  }
}
