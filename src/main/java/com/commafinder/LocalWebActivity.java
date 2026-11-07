package com.commafinder;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Displays the selected device using this app's local-network permission. */
public class LocalWebActivity extends AppCompatActivity {
    static final String EXTRA_IP = "IP";

    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private Future<?> probeTask;
    private int probeGeneration;
    private LocalWebTarget target;
    private ExternalBrowserLauncher externalBrowserLauncher;
    private WebView webView;
    private FrameLayout content;
    private ScrollView errorPanel;
    private TextView status;
    private TextView errorDetails;
    private Button settingsButton;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileCallback;
    private String pageUrl;
    private String failureDetail;
    private LocalHttpProbe.Result probeResult;
    private boolean pageFailed;
    private boolean loadStarted;
    private boolean resumed;
    private boolean destroyed;
    private final Runnable loadTimeout = () -> {
        if (!destroyed && !pageFailed && webView != null) {
            webView.stopLoading();
            showFailure(getString(R.string.local_web_timeout));
        }
    };

    private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) loadPage();
                else showFailure(getString(R.string.local_network_permission_message));
            });
    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (LocalNetworkPermission.isGranted(this)) loadPage();
                else showFailure(getString(R.string.local_network_permission_message));
            });
    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback == null) return;
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(
                        result.getResultCode(), result.getData()));
                fileCallback = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        externalBrowserLauncher = new ExternalBrowserLauncher(this);
        if (savedInstanceState != null) externalBrowserLauncher.restoreState(savedInstanceState);
        try {
            target = LocalWebTarget.fromIp(getIntent().getStringExtra(EXTRA_IP));
        } catch (IllegalArgumentException invalidAddress) {
            Toast.makeText(this, R.string.local_web_invalid_address, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        pageUrl = target.rootUrl();
        if (savedInstanceState != null) {
            String savedUrl = savedInstanceState.getString("pageUrl");
            if (target.isSameOrigin(savedUrl)) pageUrl = savedUrl;
        }
        buildUi();
        createWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack() && !pageFailed) webView.goBack();
                else finish();
            }
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 14, 13));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(button(R.string.local_web_close, v -> finish()));
        TextView address = text(target.rootUrl(), 14);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        toolbar.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button(R.string.local_web_browser, v -> {
            Toast.makeText(this, R.string.external_browser_notice, Toast.LENGTH_LONG).show();
            openExternal(pageUrl);
        }));
        root.addView(toolbar);
        status = text(getString(R.string.local_web_loading), 13);
        status.setPadding(dp(12), 0, dp(12), dp(6));
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        errorPanel = new ScrollView(this);
        errorPanel.setFillViewport(true);
        errorPanel.setBackgroundColor(Color.rgb(11, 14, 13));
        LinearLayout errorContent = new LinearLayout(this);
        errorContent.setOrientation(LinearLayout.VERTICAL);
        errorContent.setGravity(Gravity.CENTER);
        errorContent.setPadding(dp(20), dp(20), dp(20), dp(20));
        errorContent.addView(text(getString(R.string.local_web_failed), 22));
        errorDetails = text("", 14);
        errorDetails.setTextIsSelectable(true);
        errorDetails.setPadding(0, dp(16), 0, dp(16));
        errorContent.addView(errorDetails);
        errorContent.addView(button(R.string.local_web_retry, v -> retryConnection()));
        settingsButton = button(R.string.open_settings, v -> settingsLauncher.launch(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        errorContent.addView(settingsButton);
        errorPanel.addView(errorContent);
        errorPanel.setVisibility(View.GONE);
        content.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        try {
            webView = new WebView(this);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if (target.isSameOrigin(url)) return false;
                    if (request.isForMainFrame()) {
                        if (request.hasGesture() && LocalWebTarget.isExternalWebUrl(url)) openExternal(url);
                        else showFailure(getString(R.string.local_web_blocked_address));
                    }
                    return true;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    // Dynamic LAN IPs require app cleartext support. Limit direct
                    // HTTP requests to the selected device, including subresources.
                    String scheme = request.getUrl().getScheme();
                    if (("http".equalsIgnoreCase(scheme) && !target.isSameOrigin(request.getUrl().toString()))
                            || "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme)) {
                        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                                null, new ByteArrayInputStream(new byte[0]));
                    }
                    return null;
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    if (!target.isSameOrigin(url)) {
                        view.stopLoading();
                        showFailure(getString(R.string.local_web_blocked_address));
                        return;
                    }
                    pageUrl = url;
                    pageFailed = false;
                    errorPanel.setVisibility(View.GONE);
                    status.setText(R.string.local_web_loading);
                    progress.setVisibility(View.VISIBLE);
                    scheduleLoadTimeout();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (!pageFailed && target.isSameOrigin(url)) {
                        status.setText(R.string.local_web_connected);
                        progress.setVisibility(View.GONE);
                        content.removeCallbacks(loadTimeout);
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) {
                        showFailure(error.getDescription() + " (" + error.getErrorCode() + ")");
                    }
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                WebResourceResponse response) {
                    if (request.isForMainFrame()) {
                        showFailure("HTTP " + response.getStatusCode() + " " + response.getReasonPhrase());
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    if (fileCallback != null) {
                        fileCallback.onReceiveValue(null);
                        fileCallback = null;
                    }
                    content.removeView(view);
                    view.destroy();
                    webView = null;
                    loadStarted = false;
                    showFailure(getString(R.string.local_web_unavailable));
                    return true;
                }
            });
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int value) {
                    if (!pageFailed) progress.setProgress(value);
                }

                @Override
                public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                 FileChooserParams params) {
                    if (fileCallback != null) fileCallback.onReceiveValue(null);
                    fileCallback = callback;
                    try {
                        filePicker.launch(params.createIntent());
                    } catch (RuntimeException noPicker) {
                        fileCallback.onReceiveValue(null);
                        fileCallback = null;
                    }
                    return true;
                }
            });
            webView.setDownloadListener((url, userAgent, disposition, mimeType, length) -> {
                if (target.isSameOrigin(url)) {
                    Toast.makeText(this, R.string.local_web_download_browser, Toast.LENGTH_LONG).show();
                    openExternal(url);
                }
            });
            content.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
        } catch (RuntimeException unavailable) {
            if (webView != null) webView.destroy();
            webView = null;
            showFailure(getString(R.string.local_web_unavailable));
        }
    }

    private void retryConnection() {
        loadStarted = false;
        if (!LocalNetworkPermission.isGranted(this)) {
            permissionLauncher.launch(LocalNetworkPermission.PERMISSION);
        } else {
            loadPage();
        }
    }

    private void loadPage() {
        if (!resumed || destroyed) return;
        if (!LocalNetworkPermission.isGranted(this)) {
            showFailure(getString(R.string.local_network_permission_message));
            return;
        }
        if (webView == null) createWebView();
        if (webView == null) return;
        loadStarted = true;
        pageFailed = false;
        errorPanel.setVisibility(View.GONE);
        status.setText(R.string.local_web_loading);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        startHttpProbe();
        scheduleLoadTimeout();
        webView.loadUrl(pageUrl);
    }

    private void scheduleLoadTimeout() {
        content.removeCallbacks(loadTimeout);
        content.postDelayed(loadTimeout, 15_000);
    }

    private void startHttpProbe() {
        int generation = ++probeGeneration;
        if (probeTask != null) probeTask.cancel(true);
        probeResult = null;
        String url = target.rootUrl();
        probeTask = probeExecutor.submit(() -> {
            LocalHttpProbe.Result result = LocalHttpProbe.check(url);
            runOnUiThread(() -> {
                if (destroyed || generation != probeGeneration) return;
                probeResult = result;
                if (pageFailed) updateErrorDetails();
            });
        });
    }

    private void showFailure(String detail) {
        pageFailed = true;
        failureDetail = detail;
        if (status == null) return;
        content.removeCallbacks(loadTimeout);
        status.setText(R.string.local_web_failed);
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(LocalNetworkPermission.isGranted(this) ? View.GONE : View.VISIBLE);
        updateErrorDetails();
    }

    private void updateErrorDetails() {
        String http = probeResult == null
                ? getString(probeTask == null ? R.string.local_web_http_not_run : R.string.local_web_http_pending)
                : probeResult.detail();
        errorDetails.setText(getString(R.string.local_web_error_details, pageUrl, failureDetail,
                http, getString(R.string.local_web_error_help)));
    }

    private void openExternal(String url) {
        externalBrowserLauncher.open(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (target == null) return;
        if (!LocalNetworkPermission.isGranted(this)) {
            if (webView != null) webView.stopLoading();
            loadStarted = false;
            showFailure(getString(R.string.local_network_permission_message));
        } else {
            if (webView != null) webView.onResume();
            if (!loadStarted) loadPage();
        }
        externalBrowserLauncher.onResume();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("pageUrl", pageUrl);
        externalBrowserLauncher.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (externalBrowserLauncher != null) externalBrowserLauncher.destroy();
        probeGeneration++;
        if (probeTask != null) probeTask.cancel(true);
        probeExecutor.shutdownNow();
        if (content != null) content.removeCallbacks(loadTimeout);
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) {
            content.removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(242, 246, 244));
        return view;
    }

    private Button button(int label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setOnClickListener(action);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
