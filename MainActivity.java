package app.foco;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Foco: biblioteca de vídeos do YouTube sem recomendações.
 *
 * A página (assets/index.html) é servida por uma origem https interna.
 * O YouTube exige uma origem http(s) para tocar vídeos embutidos, e é isso que
 * evita o erro 153 que acontece ao abrir o HTML direto do arquivo.
 *
 * O app fica sempre em retrato. Só a tela cheia do vídeo gira para paisagem.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int BG = Color.parseColor("#0E1014");
    private static final int FILE_REQ = 11;

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chrome;
    private ValueCallback<Uri[]> fileCb;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " FocoApp");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // Ponte para a busca de vídeos (a página não consegue buscar sozinha por causa do CORS).
        web.addJavascriptInterface(new FocoBridge(), "FocoNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    String p = u.getPath();
                    if (p == null || p.equals("/") || p.equals("/index.html")) {
                        try {
                            InputStream in = getAssets().open("index.html");
                            return new WebResourceResponse("text/html", "utf-8", in);
                        } catch (Exception ignored) { }
                    }
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                            null, new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            /** Nenhuma navegação da janela principal sai da página do app. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                immersive(true);
                // Tela cheia do vídeo: única situação em que o app deita.
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
                immersive(false);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }
        };
        web.setWebChromeClient(chrome);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        web.evaluateJavascript("(window.__focoBack && window.__focoBack()) === true", value -> {
            if (!"true".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onPause() {
        web.evaluateJavascript("window.__focoSave && window.__focoSave()", null);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    /** Chamado pela página: window.FocoNative.search(texto, idDaRequisição). */
    public class FocoBridge {
        @JavascriptInterface
        public void search(final String query, final String reqId) {
            if (reqId == null || !reqId.matches("[A-Za-z0-9_-]{1,24}")) return;
            final String q = query == null ? "" : query;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String json = YtSearch.search(q);
                    final String call = ("window.__focoSearch&&window.__focoSearch("
                            + JSONObject.quote(reqId) + "," + JSONObject.quote(json) + ")")
                            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (web != null) web.evaluateJavascript(call, null);
                        }
                    });
                }
            }).start();
        }
    }

    // <YTSEARCH-BEGIN>
    /**
     * Busca de vídeos no YouTube sem chave de API: lê a página pública de resultados
     * e extrai os vídeos do JSON embutido (ytInitialData).
     * Depende da estrutura interna do YouTube e pode quebrar se ela mudar; qualquer
     * falha de leitura volta como {"error":"parse"} e a página mostra um aviso.
     */
    static final class YtSearch {
        private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
        private static final int MAX_RESULTS = 30;
        private static final int MAX_BYTES = 6 * 1024 * 1024;

        static String search(String query) {
            try {
                String q = query == null ? "" : query.trim();
                if (q.isEmpty() || q.length() > 200) return error("empty");
                String url = "https://www.youtube.com/results?hl=pt-BR&gl=BR&search_query="
                        + URLEncoder.encode(q, "UTF-8");
                JSONObject data = initialData(download(url));
                if (data == null) return error("parse");
                JSONArray out = new JSONArray();
                collect(data, out, new HashSet<String>());
                return new JSONObject().put("results", out).toString();
            } catch (IOException e) {
                return error("network");
            } catch (Exception e) {
                return error("parse");
            }
        }

        private static String error(String code) {
            try {
                return new JSONObject().put("error", code).toString();
            } catch (JSONException e) {
                return "{\"error\":\"parse\"}";
            }
        }

        private static String download(String address) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8");
                c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[16384];
                int n, total = 0;
                while ((n = in.read(tmp)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) break;
                    buf.write(tmp, 0, n);
                }
                return buf.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        }

        /** Acha "ytInitialData = {...}" na página e devolve o objeto. */
        static JSONObject initialData(String html) throws JSONException {
            final String key = "ytInitialData";
            int from = 0;
            while (true) {
                int k = html.indexOf(key, from);
                if (k < 0) return null;
                int eq = html.indexOf('=', k);
                if (eq < 0) return null;
                int p = eq + 1;
                while (p < html.length() && Character.isWhitespace(html.charAt(p))) p++;
                if (p < html.length() && html.charAt(p) == '{' && eq - (k + key.length()) <= 4) {
                    int end = matchEnd(html, p);
                    if (end > p) return new JSONObject(html.substring(p, end));
                }
                from = k + key.length();
            }
        }

        /** Posição logo após a chave que fecha o objeto que começa em start (respeita strings). */
        private static int matchEnd(String s, int start) {
            int depth = 0;
            boolean inStr = false;
            for (int i = start; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (ch == '\\') i++;
                    else if (ch == '"') inStr = false;
                } else if (ch == '"') {
                    inStr = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            return -1;
        }

        /** Percorre o JSON na ordem e junta os "videoRenderer". */
        static void collect(Object node, JSONArray out, Set<String> seen) throws JSONException {
            if (out.length() >= MAX_RESULTS) return;
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                JSONObject vr = o.optJSONObject("videoRenderer");
                if (vr != null) {
                    addVideo(vr, out, seen);
                    return;
                }
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) collect(o.opt(keys.next()), out, seen);
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, seen);
            }
        }

        private static void addVideo(JSONObject vr, JSONArray out, Set<String> seen) throws JSONException {
            String id = vr.optString("videoId", "");
            if (!id.matches("[A-Za-z0-9_-]{11}")) return;
            String title = text(vr.optJSONObject("title"));
            if (title.isEmpty() || !seen.add(id)) return;
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("title", title);
            r.put("duration", text(vr.optJSONObject("lengthText")));
            r.put("channel", first(text(vr.optJSONObject("ownerText")),
                    text(vr.optJSONObject("longBylineText")), text(vr.optJSONObject("shortBylineText"))));
            r.put("views", first(text(vr.optJSONObject("viewCountText")),
                    text(vr.optJSONObject("shortViewCountText")), ""));
            r.put("published", text(vr.optJSONObject("publishedTimeText")));
            out.put(r);
        }

        private static String first(String a, String b, String c) {
            if (!a.isEmpty()) return a;
            return !b.isEmpty() ? b : c;
        }

        /** Texto do YouTube: {"simpleText": "..."} ou {"runs":[{"text":"..."}, ...]}. */
        private static String text(JSONObject t) {
            if (t == null) return "";
            String simple = t.optString("simpleText", "");
            if (!simple.isEmpty()) return simple;
            JSONArray runs = t.optJSONArray("runs");
            if (runs == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null) sb.append(run.optString("text", ""));
            }
            return sb.toString();
        }
    }
    // <YTSEARCH-END>
}
