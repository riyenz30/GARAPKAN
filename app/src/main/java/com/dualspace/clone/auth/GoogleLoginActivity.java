package com.dualspace.clone.auth;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.IAccountManagerResponse;
import android.os.IBinder;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.accounts.IBAccountManagerService;
import top.niunaijun.blackbox.fake.frameworks.BAccountManager;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Embedded Google Sign-In Activity that runs entirely within the GARAPKAN app.
 * Uses microG's proven EmbeddedSetup flow to authenticate directly with Google
 * servers via WebView, extracts the oauth_token cookie, exchanges it for a master
 * refresh token via https://android.googleapis.com/auth, and saves the resulting
 * account directly into the sandbox virtual BUserAccounts.
 *
 * This completely isolates the Google account from the host Android OS — the host
 * device's AccountManager is NEVER touched.
 */
public class GoogleLoginActivity extends Activity {
    private static final String TAG = "GoogleLoginActivity";

    private static final String EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup";
    private static final String AUTH_ENDPOINT = "https://android.googleapis.com/auth";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_SIG_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788";
    private static final String MAGIC_USER_AGENT = " MinuteMaid";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private AccountAuthenticatorResponse mResponse;
    private IAccountManagerResponse mAmResponse;
    private int mUserId = 0;
    private boolean mResultHandled = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null) {
            mUserId = intent.getIntExtra("_B_|_UserId", 0);
            if (intent.hasExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)) {
                Object obj = intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
                if (obj instanceof AccountAuthenticatorResponse) {
                    mResponse = (AccountAuthenticatorResponse) obj;
                }
            }
            Bundle extras = intent.getExtras();
            if (extras != null && extras.containsKey("_B_|_response")) {
                IBinder binder = extras.getBinder("_B_|_response");
                if (binder != null) {
                    mAmResponse = IAccountManagerResponse.Stub.asInterface(binder);
                }
            }
        }

        FrameLayout root = new FrameLayout(this);
        mWebView = new WebView(this);
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setIndeterminate(true);

        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 8);
        root.addView(mWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(mProgressBar, pbParams);
        setContentView(root);

        initWebView();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.removeAllCookies(null);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setSaveFormData(false);
        settings.setAllowFileAccess(false);
        settings.setDatabaseEnabled(false);
        settings.setNeedInitialFocus(false);
        settings.setUseWideViewPort(false);
        settings.setSupportZoom(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + MAGIC_USER_AGENT);

        mWebView.addJavascriptInterface(new JsBridge(), "mm");

        // WebChromeClient needed for JS dialogs and console logging
        mWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                Slog.w(TAG, "WebView console [" + msg.messageLevel() + "]: " + msg.message());
                return true;
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mProgressBar.setVisibility(View.GONE);
                if (url == null) return;

                Uri uri = Uri.parse(url);
                if ("close".equals(uri.getFragment()) ||
                        url.startsWith("https://accounts.google.com/signin/continue") ||
                        url.startsWith("https://accounts.google.com/o/oauth2/programmatic_auth")) {
                    extractTokenAndFinish();
                }
            }
        });

        Locale locale = Locale.getDefault();
        Uri.Builder builder = Uri.parse(EMBEDDED_SETUP_URL).buildUpon()
                .appendQueryParameter("source", "android")
                .appendQueryParameter("xoauth_display_name", "Android Device")
                .appendQueryParameter("lang", locale.getLanguage())
                .appendQueryParameter("cc", locale.getCountry().toLowerCase(Locale.US))
                .appendQueryParameter("langCountry", locale.toString().toLowerCase(Locale.US))
                .appendQueryParameter("hl", locale.toString().replace("_", "-"))
                .appendQueryParameter("tmpl", "new_account");

        String loadUrl = builder.build().toString();
        Slog.w(TAG, "Loading Google embedded setup: " + loadUrl);
        mWebView.loadUrl(loadUrl);
    }

    private class JsBridge {
        @JavascriptInterface
        public void closeView() {
            mMainHandler.post(() -> extractTokenAndFinish());
        }

        @JavascriptInterface
        public void showView() {
            mMainHandler.post(() -> mWebView.setVisibility(View.VISIBLE));
        }

        @JavascriptInterface
        public String getAndroidId() {
            try {
                String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                return androidId != null ? androidId : "0123456789abcdef";
            } catch (Throwable t) {
                return "0123456789abcdef";
            }
        }

        @JavascriptInterface
        public String getAccounts() {
            return new JSONArray().toString();
        }

        @JavascriptInterface
        public int getPlayServicesVersionCode() { return 233013000; }

        @JavascriptInterface
        public int getAuthModuleVersionCode() { return 233013000; }

        @JavascriptInterface
        public int getBuildVersionSdk() { return Build.VERSION.SDK_INT; }

        @JavascriptInterface
        public void getDroidGuardResult(String s) {
            Slog.w(TAG, "JSBridge: getDroidGuardResult called with challenge: " + s);
            try {
                org.json.JSONArray array = new org.json.JSONArray(s);
                StringBuilder sb = new StringBuilder();
                sb.append(getAndroidId()).append(":").append(getBuildVersionSdk()).append(":").append(getPlayServicesVersionCode());
                for (int i = 0; i < array.length(); i++) {
                    sb.append(":").append(array.getString(i));
                }
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(sb.toString().getBytes("UTF-8"));
                String dg = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);

                mMainHandler.post(() -> {
                    String script = "if (window.setDgResult) { window.setDgResult('" + dg + "'); }";
                    if (Build.VERSION.SDK_INT >= 19) {
                        mWebView.evaluateJavascript(script, null);
                    } else {
                        mWebView.loadUrl("javascript:" + script);
                    }
                    Slog.w(TAG, "JSBridge: injected setDgResult callback");
                });
            } catch (Throwable t) {
                Slog.w(TAG, "JSBridge: getDroidGuardResult error: " + t.getMessage());
                mMainHandler.post(() -> {
                    String script = "if (window.setDgResult) { window.setDgResult(''); }";
                    if (Build.VERSION.SDK_INT >= 19) {
                        mWebView.evaluateJavascript(script, null);
                    } else {
                        mWebView.loadUrl("javascript:" + script);
                    }
                });
            }
        }

        @JavascriptInterface
        public void skipLogin() {
            mMainHandler.post(() -> {
                if (mAmResponse != null) {
                    try { mAmResponse.onError(AccountManager.ERROR_CODE_CANCELED, "Canceled"); } catch (Throwable ignored) {}
                }
                setResult(RESULT_CANCELED);
                finish();
            });
        }

        @JavascriptInterface public boolean hasPhoneNumber() { return false; }
        @JavascriptInterface public String getPhoneNumber() { return null; }
        @JavascriptInterface public String getSimSerial() { return null; }
        @JavascriptInterface public int getSimState() { return 0; }
    }

    private void extractTokenAndFinish() {
        if (mResultHandled) return;
        mResultHandled = true;

        mProgressBar.setVisibility(View.VISIBLE);
        String cookies = CookieManager.getInstance().getCookie(EMBEDDED_SETUP_URL);
        Slog.w(TAG, "extractTokenAndFinish: checking cookies");

        String oauthToken = null;
        if (cookies != null) {
            for (String cookie : cookies.split(";")) {
                String trimmed = cookie.trim();
                if (trimmed.startsWith("oauth_token=")) {
                    oauthToken = trimmed.substring("oauth_token=".length());
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(oauthToken)) {
            // Also check programmatic auth URL
            String progCookies = CookieManager.getInstance().getCookie("https://accounts.google.com/o/oauth2/programmatic_auth");
            if (progCookies != null) {
                for (String cookie : progCookies.split(";")) {
                    String trimmed = cookie.trim();
                    if (trimmed.startsWith("oauth_token=")) {
                        oauthToken = trimmed.substring("oauth_token=".length());
                        break;
                    }
                }
            }
        }

        if (TextUtils.isEmpty(oauthToken)) {
            Slog.w(TAG, "extractTokenAndFinish: no oauth_token found in cookies");
            Toast.makeText(this, "Gagal mendapatkan token login Google", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final String token = oauthToken;
        Slog.w(TAG, "extractTokenAndFinish: oauth_token obtained successfully, exchanging for master token");

        // Exchange oauth_token for Master Token on background thread
        new Thread(() -> exchangeTokenAndSaveAccount(token), "GoogleTokenExchange").start();
    }

    private void exchangeTokenAndSaveAccount(String oauthToken) {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "0123456789abcdef";

            Locale locale = Locale.getDefault();
            Map<String, String> params = new HashMap<>();
            params.put("app", GMS_PACKAGE_NAME);
            params.put("client_sig", GMS_SIG_SHA1);
            params.put("callerPkg", GMS_PACKAGE_NAME);
            params.put("callerSig", GMS_SIG_SHA1);
            params.put("androidId", androidId);
            params.put("sdk_version", String.valueOf(Build.VERSION.SDK_INT));
            params.put("device_country", locale.getCountry().toLowerCase(Locale.US));
            params.put("operatorCountry", locale.getCountry().toLowerCase(Locale.US));
            params.put("lang", locale.toString());
            params.put("google_play_services_version", "233013000");
            params.put("accountType", "com.google");
            params.put("service", "ac2dm");
            params.put("Token", oauthToken);
            params.put("ACCESS_TOKEN", "1");
            params.put("add_account", "1");
            params.put("get_accountid", "1");
            params.put("droidguard_results", "null");

            StringBuilder postData = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (postData.length() > 0) postData.append('&');
                postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }

            URL url = new URL(AUTH_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("User-Agent", "GoogleAuth/1.4 (" + Build.DEVICE + " " + Build.ID + ")");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("app", GMS_PACKAGE_NAME);

            OutputStream os = conn.getOutputStream();
            os.write(postData.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            Slog.w(TAG, "Google Auth exchange response code: " + code);

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code == 200 ? conn.getInputStream() : conn.getErrorStream()));
            Map<String, String> responseMap = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    responseMap.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            reader.close();

            String email = responseMap.get("Email");
            String masterToken = responseMap.get("Token");
            String sid = responseMap.get("SID");
            String lsid = responseMap.get("LSID");
            String accountId = responseMap.get("accountId");
            String services = responseMap.get("services");

            if (TextUtils.isEmpty(email) || TextUtils.isEmpty(masterToken)) {
                Slog.w(TAG, "Token exchange failed: " + responseMap);
                mMainHandler.post(() -> {
                    Toast.makeText(this, "Autentikasi Google gagal dari server", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_CANCELED);
                    finish();
                });
                return;
            }

            Slog.w(TAG, "Token exchange SUCCESS for: " + email + ", saving to sandbox userId=" + mUserId);

            // Save account directly into sandbox via AIDL IPC to BAccountManagerService
            Account account = new Account(email, "com.google");
            Bundle extras = new Bundle();
            if (services != null) extras.putString("services", services);
            if (accountId != null) extras.putString("GoogleUserId", accountId);

            boolean added = BAccountManager.get().getService().addAccountExplicitly(account, masterToken, extras, mUserId);
            Slog.w(TAG, "addAccountExplicitly to sandbox result: " + added);

            if (sid != null) BAccountManager.get().getService().setAuthToken(account, "SID", sid, mUserId);
            if (lsid != null) BAccountManager.get().getService().setAuthToken(account, "LSID", lsid, mUserId);
            if (accountId != null) BAccountManager.get().getService().setUserData(account, "GoogleUserId", accountId, mUserId);
            BAccountManager.get().getService().setUserData(account, "oauthAccessToken", "1", mUserId);
            BAccountManager.get().getService().setUserData(account, "flags", "1", mUserId);

            // Pre-fetch and cache Play Store essential tokens so Play Store never needs GMS auth
            String[] targetServices = new String[] {
                    "oauth2:https://www.googleapis.com/auth/googleplay",
                    "android"
            };
            for (String sName : targetServices) {
                try {
                    String subToken = fetchSubToken(email, masterToken, sName, "com.android.vending", androidId);
                    if (subToken != null) {
                        BAccountManager.get().getService().setAuthToken(account, sName, subToken, mUserId);
                        Slog.w(TAG, "Cached token for " + sName + " -> SUCCESS");
                    }
                } catch (Throwable t) {
                    Slog.w(TAG, "Failed pre-fetching token for " + sName + ": " + t.getMessage());
                }
            }

            // Perform Google device checkin to obtain valid GSF android_id
            // Play Store needs X-DFE-Device-Id header set to this value.
            try {
                DeviceCheckinHelper.CheckinResult checkinResult = DeviceCheckinHelper.checkin(this);
                Slog.w(TAG, "Device checkin SUCCESS: android_id=" + checkinResult.getAndroidIdHex() + " (decimal=" + checkinResult.androidId + ")");
                // Save to SharedPreferences and local shared file so ContentProvider hook can serve it
                getSharedPreferences("gsf_checkin", MODE_PRIVATE)
                        .edit()
                        .putLong("android_id", checkinResult.androidId)
                        .putLong("security_token", checkinResult.securityToken)
                        .apply();
                try {
                    java.io.File idFile = new java.io.File(getFilesDir(), "gsf_android_id");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(idFile);
                    fos.write(String.valueOf(checkinResult.androidId).getBytes());
                    fos.close();
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                Slog.w(TAG, "Device checkin failed (non-fatal): " + t.getMessage());
            }

            // Broadcast LOGIN_ACCOUNTS_CHANGED and GOOGLE_ACCOUNT_CHANGE inside sandbox
            try {
                Intent bc1 = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
                BlackBoxCore.getBActivityManager().sendBroadcast(bc1, null, mUserId);

                Intent bc2 = new Intent("com.google.android.gms.auth.GOOGLE_ACCOUNT_CHANGE");
                bc2.setPackage("com.android.vending");
                bc2.putExtra(AccountManager.KEY_ACCOUNT_NAME, email);
                BlackBoxCore.getBActivityManager().sendBroadcast(bc2, null, mUserId);

                Intent bc3 = new Intent("com.google.android.gms.auth.GOOGLE_ACCOUNT_CHANGE");
                bc3.setPackage("com.google.android.gms");
                bc3.putExtra(AccountManager.KEY_ACCOUNT_NAME, email);
                BlackBoxCore.getBActivityManager().sendBroadcast(bc3, null, mUserId);

                Slog.w(TAG, "Broadcasts sent inside sandbox to userId=" + mUserId);
            } catch (Throwable t) {
                Slog.w(TAG, "Broadcast failed: " + t.getMessage());
            }

            // Respond to AuthenticatorResponse or IAccountManagerResponse
            Bundle resBundle = new Bundle();
            resBundle.putString(AccountManager.KEY_ACCOUNT_NAME, email);
            resBundle.putString(AccountManager.KEY_ACCOUNT_TYPE, "com.google");
            resBundle.putBoolean("new_account_created", true);

            if (mResponse != null) {
                try { mResponse.onResult(resBundle); } catch (Throwable ignored) {}
            }
            if (mAmResponse != null) {
                try { mAmResponse.onResult(resBundle); } catch (Throwable ignored) {}
            }

            // Kill stale Play Store and GMS processes so they reload account from DB
            try {
                Slog.w(TAG, "Killing sandbox processes to force fresh account reload");
                BlackBoxCore.get().stopPackage("com.android.vending", mUserId);
                BlackBoxCore.get().stopPackage("com.google.android.gms", mUserId);
                BlackBoxCore.get().stopPackage("com.google.android.gsf", mUserId);
                Thread.sleep(600);
            } catch (Throwable t) {
                Slog.w(TAG, "stopPackage error (non-fatal): " + t.getMessage());
            }

            // Launch Play Store fresh — it will now find the account and show Dashboard
            try {
                Intent launchIntent = new Intent();
                launchIntent.setComponent(new android.content.ComponentName("com.android.vending", "com.android.vending.AssetBrowserActivity"));
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                BlackBoxCore.getBActivityManager().startActivityAms(mUserId, launchIntent, null, null, null, 0, 0, null);
            } catch (Throwable ignored) {}

            mMainHandler.post(() -> {
                Toast.makeText(this, "Berhasil login Google: " + email, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });

        } catch (Throwable t) {
            Slog.w(TAG, "Error during token exchange: " + t.getMessage(), t);
            mMainHandler.post(() -> {
                Toast.makeText(this, "Error saat menyimpan akun: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    /**
     * Fetch a sub-token for a specific service using the Master Token.
     * This replicates what microG's AuthRequest does when Play Store asks for getAuthToken.
     */
    private String fetchSubToken(String email, String masterToken, String service, String appPkg, String androidId) throws Exception {
        Locale locale = Locale.getDefault();
        Map<String, String> params = new HashMap<>();
        params.put("accountType", "com.google");
        params.put("Email", email);
        params.put("has_permission", "1");
        params.put("add_account", "0");
        params.put("service", service);
        params.put("source", "android");
        params.put("androidId", androidId);
        params.put("app", appPkg);
        params.put("client_sig", GMS_SIG_SHA1);
        params.put("callerPkg", appPkg);
        params.put("callerSig", GMS_SIG_SHA1);
        params.put("Token", masterToken);
        params.put("system_partition", "1");
        params.put("google_play_services_version", "233013000");
        params.put("sdk_version", String.valueOf(Build.VERSION.SDK_INT));
        params.put("device_country", locale.getCountry().toLowerCase(Locale.US));
        params.put("lang", locale.toString());

        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (postData.length() > 0) postData.append('&');
            postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }

        URL url = new URL(AUTH_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "GoogleAuth/1.4 (" + Build.DEVICE + " " + Build.ID + ")");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("app", appPkg);

        OutputStream os = conn.getOutputStream();
        os.write(postData.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream()));
        Map<String, String> resp = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                resp.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        reader.close();

        if (code == 200) {
            String auth = resp.get("Auth");
            if (auth == null) auth = resp.get("Token");
            return auth;
        } else {
            Slog.w(TAG, "fetchSubToken failed for " + service + ": code=" + code + " resp=" + resp);
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            if (mResponse != null) {
                try { mResponse.onError(AccountManager.ERROR_CODE_CANCELED, "Canceled"); } catch (Throwable ignored) {}
            }
            if (mAmResponse != null) {
                try { mAmResponse.onError(AccountManager.ERROR_CODE_CANCELED, "Canceled"); } catch (Throwable ignored) {}
            }
            super.onBackPressed();
        }
    }
}
