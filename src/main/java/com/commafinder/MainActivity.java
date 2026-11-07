package com.commafinder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SuppressLint({"SetTextI18n", "SpellCheckingInspection"})
public class MainActivity extends AppCompatActivity {

    static final String ACTION_SCAN_WEB = "com.commafinder.action.SCAN_WEB";
    private static final int SOCKET_TIMEOUT_MS = 800;
    private static final int THREAD_POOL = 50;
    private static final String COLOR_BG = "#0B0E0D";
    private static final String COLOR_SURFACE = "#141817";
    private static final String COLOR_SURFACE_RAISED = "#191E1C";
    private static final String COLOR_BORDER = "#29312E";
    private static final String COLOR_PRIMARY = "#69E59D";
    private static final String COLOR_PRIMARY_DARK = "#123222";
    private static final String COLOR_TEXT = "#F2F6F4";
    private static final String COLOR_TEXT_SECONDARY = "#9CA8A3";
    private static final String COLOR_INFO = "#79C7FF";
    private static final String COLOR_ERROR = "#FF806E";

    private TextView tvStatus, tvFound, tvLog;
    private ProgressBar progressBar;
    private TextView btnScan7000, btnScan22, btnUpdate;
    private ScrollView logScroll;

    private LinearLayout ipListContainer;
    private String lastDetectedIpString = "";

    private final List<String> mySubnets = new ArrayList<>();
    private String foundIp = null;
    private int currentPort = 7000;
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private Future<?> scanTask;
    private Future<?> ipDetectionTask;
    private Future<?> updateCheckTask;
    private AppUpdater appUpdater;
    private final ActivityResultLauncher<Intent> installPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (appUpdater != null) appUpdater.onPermissionResult();
            });
    private PortScanner activeScanner;
    private int scanGeneration;
    private volatile boolean scanning = false;
    private volatile boolean destroyed = false;
    private boolean resumed;
    private int pendingScanPort;
    private boolean locationRequestInFlight;
    private boolean localNetworkRequestInFlight;
    private boolean waitingForLocalNetworkSettings;
    private boolean pendingDeviceOpen;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> localNetworkPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                localNetworkRequestInFlight = false;
                if (granted && LocalNetworkPermission.isGranted(this)) {
                    detectIP();
                } else {
                    int requestedPort = pendingScanPort;
                    pendingScanPort = 0;
                    showLocalNetworkPermissionRequired();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.local_network_permission_title)
                            .setMessage(R.string.local_network_permission_message)
                            .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                                pendingScanPort = requestedPort;
                                waitingForLocalNetworkSettings = true;
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                locationRequestInFlight = false;
                if (!Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))) {
                    appendLog("위치 권한이 거부되어 Wi-Fi 이름(SSID)을 가져올 수 없습니다.", "#AAAAAA");
                }
                detectIP();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingScanPort = savedInstanceState.getInt("pendingScanPort");
            locationRequestInFlight = savedInstanceState.getBoolean("locationRequestInFlight");
            localNetworkRequestInFlight = savedInstanceState.getBoolean("localNetworkRequestInFlight");
            waitingForLocalNetworkSettings = savedInstanceState.getBoolean("waitingForLocalNetworkSettings");
            pendingDeviceOpen = savedInstanceState.getBoolean("pendingDeviceOpen");
            foundIp = savedInstanceState.getString("foundIp");
            currentPort = savedInstanceState.getInt("currentPort", 7000);
        } else {
            consumeScanIntent(getIntent());
        }
        buildUI();
        if (foundIp != null) showFoundAddress();

        appUpdater = new AppUpdater(this, new AppUpdater.Listener() {
            @Override
            public void showState(String label, Runnable action) {
                showUpdateAction(label, action);
            }

            @Override
            public void showMessage(String message) {
                appendLog(message, COLOR_INFO);
            }

            @Override
            public void onIdle() {
                showCurrentVersionBadge();
                checkForUpdates();
            }

            @Override
            public void requestInstallPermission(Intent intent) {
                installPermissionLauncher.launch(intent);
            }
        });

        // Widget scans do not need the optional SSID/location prompt.
        if (savedInstanceState == null && pendingScanPort == 0
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationRequestInFlight = true;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (waitingForLocalNetworkSettings) {
            waitingForLocalNetworkSettings = false;
            if (!LocalNetworkPermission.isGranted(this)) {
                pendingScanPort = 0;
                showLocalNetworkPermissionRequired();
            }
        }
        if (scanning && !LocalNetworkPermission.isGranted(this)) {
            cancelScan(false);
            showLocalNetworkPermissionRequired();
        }
        detectIP();
        appUpdater.resume();
        checkForUpdates();
        if (pendingDeviceOpen) mainHandler.post(this::openPendingDevice);
    }

    @Override
    protected void onPause() {
        resumed = false;
        appUpdater.pause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (consumeScanIntent(intent)) {
            cancelScan(true);
            detectIP();
        }
    }

    private boolean consumeScanIntent(Intent intent) {
        if (intent == null || !ACTION_SCAN_WEB.equals(intent.getAction())) return false;
        intent.setAction(null);
        pendingScanPort = 7000;
        pendingDeviceOpen = false;
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("pendingScanPort", scanning ? currentPort : pendingScanPort);
        outState.putBoolean("locationRequestInFlight", locationRequestInFlight);
        outState.putBoolean("localNetworkRequestInFlight", localNetworkRequestInFlight);
        outState.putBoolean("waitingForLocalNetworkSettings", waitingForLocalNetworkSettings);
        outState.putBoolean("pendingDeviceOpen", pendingDeviceOpen);
        outState.putString("foundIp", foundIp);
        outState.putInt("currentPort", currentPort);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (appUpdater != null) appUpdater.destroy();
        cancelScan(false);
        cancelTask(ipDetectionTask);
        cancelTask(updateCheckTask);
        backgroundExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        List<String> savedSubnets = new ArrayList<>(mySubnets);
        String savedLog   = tvLog    != null ? tvLog.getText().toString()     : null;
        String savedFound = tvFound  != null ? tvFound.getText().toString()   : null;
        boolean foundVis  = tvFound  != null && tvFound.getVisibility() == View.VISIBLE;
        String savedStatus = tvStatus != null ? tvStatus.getText().toString() : null;
        int savedStatusColor = tvStatus != null ? tvStatus.getCurrentTextColor() : Color.WHITE;
        int savedProgress = progressBar != null ? progressBar.getProgress() : 0;
        int savedProgressMax = progressBar != null ? progressBar.getMax() : 254;

        mySubnets.clear();
        buildUI();
        mySubnets.addAll(savedSubnets);

        if (savedLog != null) tvLog.setText(savedLog);
        if (savedFound != null && foundVis) {
            tvFound.setText(savedFound);
            tvFound.setVisibility(View.VISIBLE);
        }
        if (savedStatus != null) {
            tvStatus.setText(savedStatus);
            tvStatus.setTextColor(savedStatusColor);
            restoreStatusAppearance(savedStatusColor);
            progressBar.setMax(savedProgressMax);
            progressBar.setProgress(savedProgress);
        }
        if (scanning) applyScanningButtons(currentPort);

        lastDetectedIpString = "";
        detectIP();
        appUpdater.refresh();
        checkForUpdates();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(COLOR_BG));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(0, 0, 0, dp(20));
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(28));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_comma_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(10), dp(10), dp(10), dp(10));
        logo.setContentDescription("네트워크 새로고침");
        logo.setBackground(roundedBackground(COLOR_PRIMARY_DARK, null, 0, 15));
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        logo.setClickable(true);
        logo.setFocusable(true);
        ViewCompat.setTooltipText(logo, "네트워크 새로고침");
        logo.setOnClickListener(v -> {
            v.animate().rotationBy(360f).setDuration(420L).start();
            refreshNetwork();
        });
        header.addView(logo);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headingParams.setMargins(dp(12), 0, dp(8), 0);
        heading.setLayoutParams(headingParams);

        TextView title = makeText("Comma Finder", 22, COLOR_TEXT, true);
        title.setLetterSpacing(-0.01f);
        heading.addView(title);

        TextView sub = makeText("네트워크에서 comma 기기를 찾아보세요", 12, COLOR_TEXT_SECONDARY, false);
        sub.setPadding(0, dp(3), 0, 0);
        heading.addView(sub);
        header.addView(heading);

        TextView tvVersion = makePill(
                String.format(Locale.getDefault(), "v%s", getCurrentVersionName()));
        tvVersion.setTextSize(13f);
        tvVersion.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnUpdate = tvVersion;
        btnUpdate.setEnabled(false);
        header.addView(tvVersion);

        content.addView(header);
        content.addView(vSpace(24));

        LinearLayout ipCard = makeCard();
        LinearLayout ipHeader = makeCardHeader("현재 네트워크", "자동 감지");
        ipCard.addView(ipHeader);

        ipListContainer = new LinearLayout(this);
        ipListContainer.setOrientation(LinearLayout.VERTICAL);
        ipListContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvLoading = makeText("IP 주소를 확인하는 중…", 15, COLOR_INFO, true);
        ipListContainer.addView(tvLoading);

        ipCard.addView(ipListContainer);
        content.addView(ipCard);
        content.addView(vSpace(15));

        LinearLayout statusCard = makeCard();
        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView statusLabel = makeLabel("기기 검색");
        statusLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusHeader.addView(statusLabel);

        tvStatus = makePill("대기 중");
        statusHeader.addView(tvStatus);
        statusCard.addView(statusHeader);

        TextView statusGuide = makeText(
                "연결 방식을 선택하면 현재 네트워크를 검색합니다.",
                13, COLOR_TEXT_SECONDARY, false);
        statusGuide.setPadding(0, dp(12), 0, dp(12));
        statusCard.addView(statusGuide);

        tvFound  = makeText("", 18, COLOR_PRIMARY, true);
        tvFound.setGravity(Gravity.CENTER);
        tvFound.setPadding(dp(12), dp(11), dp(12), dp(11));
        tvFound.setBackground(roundedBackground(COLOR_PRIMARY_DARK, null, 0, 10));
        tvFound.setOnClickListener(v -> openFoundDevice());
        tvFound.setOnLongClickListener(v -> {
            if (foundIp == null || currentPort != 7000) return false;
            openBrowser(true);
            return true;
        });
        tvFound.setContentDescription(getString(R.string.found_address_action));
        tvFound.setVisibility(View.GONE);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(254);
        progressBar.setProgress(0);
        progressBar.setProgressDrawable(makeProgressDrawable());
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));

        statusCard.addView(progressBar);
        statusCard.addView(vSpace(12));
        statusCard.addView(tvFound);
        content.addView(statusCard);

        content.addView(vSpace(22));

        LinearLayout gridRow1 = new LinearLayout(this);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        gridRow1.setBaselineAligned(false);
        gridRow1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnScan7000 = makeButton("웹으로 연결\n포트 7000 검색", COLOR_PRIMARY, COLOR_TEXT);
        setButtonGradient(btnScan7000, "#183F2B", "#10271D", "#3F805A", COLOR_TEXT);
        btnScan7000.setLayoutParams(new LinearLayout.LayoutParams(0, dp(76), 1.0f));
        btnScan7000.setOnClickListener(v -> { if (scanning) stopScan(); else startScan(7000); });
        gridRow1.addView(btnScan7000);

        gridRow1.addView(hSpace(22));

        btnScan22 = makeButton("SSH로 연결\n포트 22 검색", COLOR_INFO, COLOR_TEXT);
        setButtonGradient(btnScan22, "#183548", "#102431", "#3F789A", COLOR_TEXT);
        btnScan22.setLayoutParams(new LinearLayout.LayoutParams(0, dp(76), 1.0f));
        btnScan22.setOnClickListener(v -> { if (scanning) stopScan(); else startScan(22); });
        gridRow1.addView(btnScan22);

        content.addView(gridRow1);
        content.addView(vSpace(22));

        LinearLayout logCard = makeCard();
        logCard.addView(makeCardHeader("활동 로그", "실시간"));
        logScroll = new ScrollView(this);
        logScroll.setBackground(roundedBackground("#0A0D0C", null, 0, 10));

        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));
        logScroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#C9D2CE"));
        tvLog.setTextSize(11f);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setLineSpacing(dp(2), 1.0f);
        logScroll.addView(tvLog);
        logCard.addView(logScroll);
        content.addView(logCard);

        scroll.addView(content);
        root.addView(scroll);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        setContentView(root);
    }

    private void refreshNetwork() {
        tvLog.setText("");
        ipListContainer.removeAllViews();
        ipListContainer.addView(makeText(
                "IP 주소를 확인하는 중…", 15, COLOR_INFO, true));
        lastDetectedIpString = "";
        detectIP();
    }

    private void checkForUpdates() {
        if (appUpdater.hasDownload()) return;
        cancelTask(updateCheckTask);
        updateCheckTask = backgroundExecutor.submit(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.github.com/repos/crwusiz/comma_finder/releases/latest");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "CommaFinder-App");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }

                    JSONObject json = new JSONObject(sb.toString());
                    String tagName = json.getString("tag_name");
                    final String remoteVersionName = tagName.replaceAll("[^0-9.]", "");

                    String apkUrl = null;
                    JSONArray assets = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }

                    final String finalApkUrl = apkUrl;
                    boolean updateAvailable = VersionUtils.isUpdateAvailable(
                            getCurrentVersionName(), remoteVersionName);

                    postToMain(() -> {
                        if (appUpdater.hasDownload()) return;
                        if (updateAvailable) {
                            if (finalApkUrl == null) {
                                showUpdateAction("업데이트 준비 중", null);
                            } else {
                                showUpdateAction(String.format(Locale.getDefault(),
                                        "v%s 업데이트", remoteVersionName),
                                        () -> appUpdater.start(finalApkUrl, remoteVersionName));
                            }
                        } else {
                            showCurrentVersionBadge();
                        }
                    });
                } else {
                    postToMain(this::showCurrentVersionBadge);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    postToMain(this::showCurrentVersionBadge);
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showCurrentVersionBadge() {
        if (appUpdater != null && appUpdater.hasDownload()) return;
        btnUpdate.setText(String.format(Locale.getDefault(), "v%s", getCurrentVersionName()));
        btnUpdate.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY));
        btnUpdate.setBackground(roundedBackground(COLOR_SURFACE_RAISED, null, 0, 20));
        btnUpdate.setContentDescription(String.format(
                Locale.getDefault(), "현재 버전 %s", getCurrentVersionName()));
        btnUpdate.setOnClickListener(null);
        btnUpdate.setClickable(false);
        btnUpdate.setFocusable(false);
        btnUpdate.setEnabled(false);
    }

    private void showUpdateAction(String label, Runnable action) {
        btnUpdate.setText(label);
        btnUpdate.setTextColor(Color.parseColor(COLOR_PRIMARY));
        btnUpdate.setBackground(roundedBackground(COLOR_PRIMARY_DARK, COLOR_PRIMARY, 1, 20));
        btnUpdate.setContentDescription(label);
        btnUpdate.setOnClickListener(action == null ? null : v -> action.run());
        btnUpdate.setClickable(action != null);
        btnUpdate.setFocusable(action != null);
        btnUpdate.setEnabled(action != null);
    }

    private void detectIP() {
        cancelTask(ipDetectionTask);
        ipDetectionTask = backgroundExecutor.submit(() -> {
            LinkedHashMap<String, String> ipMap = getAllLocalIPsWithLabel();

            postToMain(() -> {
                List<String> ips = new ArrayList<>(ipMap.keySet());

                StringBuilder newTextBuilder = new StringBuilder();
                if (ips.isEmpty()) newTextBuilder.append("IP 감지 실패");
                else for (String ip : ips) newTextBuilder.append("[").append(ipMap.get(ip)).append("] ").append(ip).append("\n");

                String newText = newTextBuilder.toString().trim();

                if (lastDetectedIpString.equals(newText) && !mySubnets.isEmpty()) {
                    continuePendingScan();
                    return;
                }

                String oldText = lastDetectedIpString;
                lastDetectedIpString = newText;

                ipListContainer.removeAllViews();
                mySubnets.clear();

                if (!ips.isEmpty()) {
                    TableLayout table = new TableLayout(MainActivity.this);
                    table.setColumnStretchable(1, true);

                    for (String ip : ips) {
                        String label = ipMap.get(ip);

                        if (label != null && !label.startsWith("VPN")) {
                            String subnet = NetworkUtils.subnetOf(ip);
                            if (subnet != null && !mySubnets.contains(subnet)) mySubnets.add(subnet);
                        }

                        TableRow row = new TableRow(MainActivity.this);

                        TextView tvLabel = new TextView(MainActivity.this);
                        tvLabel.setText(label);
                        tvLabel.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY));
                        tvLabel.setTextSize(12f);
                        tvLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        tvLabel.setPadding(0, dp(2), dp(14), dp(6));
                        tvLabel.setMaxLines(1);
                        tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);

                        TextView tvIpVal = new TextView(MainActivity.this);
                        tvIpVal.setText(ip);
                        tvIpVal.setTextColor(Color.parseColor(COLOR_INFO));
                        tvIpVal.setTextSize(15f);
                        tvIpVal.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        tvIpVal.setPadding(0, 0, 0, dp(6));

                        row.addView(tvLabel);
                        row.addView(tvIpVal);
                        table.addView(row);
                    }
                    ipListContainer.addView(table);

                    if (!oldText.isEmpty()) appendLog("🌐 네트워크 상태 갱신됨", "#AAAAAA");

                    for (String ip : ips) {
                        String label = ipMap.get(ip);
                        appendLog("현재 IP: [" + label + "] " + ip, "#4FC3F7");
                        if (label != null && label.startsWith("VPN")) appendLog(" └ 이 대역은 스캔에서 제외됩니다.", "#555555");
                    }
                } else {
                    ipListContainer.addView(makeText("IP 감지 실패", 15, COLOR_ERROR, true));
                    appendLog("IP 감지 실패 — Wi-Fi 연결 확인", "#FF6B35");
                }
                continuePendingScan();
            });
        });
    }

    @SuppressLint("MissingPermission")
    private String getWifiSsid() {
        try {
            WifiInfo info = null;

            ConnectivityManager cm = (ConnectivityManager) getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                    if (capabilities != null
                            && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && capabilities.getTransportInfo() instanceof WifiInfo) {
                        info = (WifiInfo) capabilities.getTransportInfo();
                    }
                }
            }

            if (info == null || info.getSSID() == null || WifiManager.UNKNOWN_SSID.equals(info.getSSID())) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    @SuppressWarnings("deprecation")
                    WifiInfo legacyInfo = wm.getConnectionInfo();
                    if (legacyInfo != null) {
                        info = legacyInfo;
                    }
                }
            }

            if (info != null && info.getSSID() != null) {
                String ssid = info.getSSID().trim();
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (!ssid.isEmpty() && !WifiManager.UNKNOWN_SSID.equals(ssid)) {
                    return " - " + ssid;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private LinkedHashMap<String, String> getAllLocalIPsWithLabel() {
        try {
            return NetworkUtils.getLocalIpv4Addresses(getWifiSsid());
        } catch (Exception e) {
            appendLog("IP 오류: " + e.getMessage(), "#FF6B35");
            return new LinkedHashMap<>();
        }
    }

    private void startScan(int targetPort) {
        pendingScanPort = targetPort;
        continuePendingScan();
    }

    private void continuePendingScan() {
        if (!resumed || pendingScanPort == 0 || scanning || locationRequestInFlight
                || localNetworkRequestInFlight || waitingForLocalNetworkSettings) return;
        if (!LocalNetworkPermission.isGranted(this)) {
            localNetworkRequestInFlight = true;
            localNetworkPermissionLauncher.launch(LocalNetworkPermission.PERMISSION);
            return;
        }
        int targetPort = pendingScanPort;
        pendingScanPort = 0;
        if (mySubnets.isEmpty()) { appendLog("IP 대역을 먼저 감지해야 합니다", "#FF6B35"); return; }
        scanning = true;
        pendingDeviceOpen = false;
        foundIp = null;
        currentPort = targetPort;
        int generation = ++scanGeneration;
        List<String> subnets = new ArrayList<>(mySubnets);

        applyScanningButtons(targetPort);
        if (targetPort == 7000) {
            appendLog("7000 포트가 개방된 comma 기기를 찾으면 웹페이지를 자동으로 오픈합니다", "#AAAAAA");
        } else {
            appendLog("22 포트가 개방된 comma 기기를 찾으면 Private Key를 이용해 SSH에 접속합니다", "#AAAAAA");
        }

        tvFound.setVisibility(View.GONE);

        final int total = subnets.size() * NetworkUtils.LAST_HOST;
        progressBar.setMax(total);
        progressBar.setProgress(0);

        setStatus("검색 중 · " + targetPort, COLOR_INFO, "#142633");
        tvLog.setText("");

        for (String s : subnets)
            appendLog(String.format(Locale.getDefault(), "스캔: %s.1~254 (포트 %d)", s, currentPort), "#4FC3F7");

        PortScanner scanner = new PortScanner(THREAD_POOL, SOCKET_TIMEOUT_MS);
        activeScanner = scanner;
        scanTask = backgroundExecutor.submit(() -> runScan(scanner, subnets, targetPort, generation));
    }

    private void runScan(PortScanner scanner, List<String> subnets, int port, int generation) {
        int total = subnets.size() * NetworkUtils.LAST_HOST;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!isScanActive(generation)) return;
            if (!LocalNetworkPermission.isGranted(this)) {
                postForScan(generation, () -> {
                    scanning = false;
                    activeScanner = null;
                    showLocalNetworkPermissionRequired();
                });
                return;
            }
            if (attempt > 1) {
                postForScan(generation, () -> {
                    progressBar.setProgress(0);
                    appendLog("1차 스캔 미발견. 2차 정밀 스캔 재시도 중...", "#FFCA28");
                });
            }

            PortScanner.Result result = scanner.scan(subnets, port, completed -> {
                if (completed % 5 == 0 || completed == total) {
                    postForScan(generation, () -> progressBar.setProgress(completed));
                }
            });

            if (!LocalNetworkPermission.isGranted(this)) {
                postForScan(generation, () -> {
                    scanning = false;
                    activeScanner = null;
                    showLocalNetworkPermissionRequired();
                });
                return;
            }
            if (result.status() == PortScanner.Status.FOUND) {
                postForScan(generation, () -> {
                    scanning = false;
                    activeScanner = null;
                    onFound(result.ip());
                });
                return;
            }
            if (result.status() == PortScanner.Status.CANCELLED) return;
        }

        postForScan(generation, () -> {
            scanning = false;
            activeScanner = null;
            onNotFound();
        });
    }

    private void stopScan() {
        cancelScan(true);
    }

    private void cancelScan(boolean updateUi) {
        if (!scanning && activeScanner == null) return;
        scanning = false;
        scanGeneration++;
        if (activeScanner != null) activeScanner.cancel();
        activeScanner = null;
        cancelTask(scanTask);
        if (updateUi && !destroyed) onStopped();
    }

    private boolean isScanActive(int generation) {
        return !destroyed && scanning && generation == scanGeneration;
    }

    private void postForScan(int generation, Runnable action) {
        postToMain(() -> {
            if (isScanActive(generation)) action.run();
        });
    }

    private void applyScanningButtons(int port) {
        if (port == 7000) {
            btnScan7000.setText("검색 중단\n포트 7000 검색 중");
            setButtonColors(btnScan7000, "#45221F", COLOR_ERROR, COLOR_ERROR);
            btnScan22.setEnabled(false);
            btnScan22.setAlpha(0.42f);
        } else {
            btnScan22.setText("검색 중단\n포트 22 검색 중");
            setButtonColors(btnScan22, "#45221F", COLOR_ERROR, COLOR_ERROR);
            btnScan7000.setEnabled(false);
            btnScan7000.setAlpha(0.42f);
        }
    }

    private void resetScanButtons() {
        btnScan7000.setText("웹으로 연결\n포트 7000 검색");
        btnScan7000.setEnabled(true);
        btnScan7000.setAlpha(1f);
        setButtonGradient(btnScan7000, "#183F2B", "#10271D", "#3F805A", COLOR_TEXT);

        btnScan22.setText("SSH로 연결\n포트 22 검색");
        btnScan22.setEnabled(true);
        btnScan22.setAlpha(1f);
        setButtonGradient(btnScan22, "#183548", "#102431", "#3F789A", COLOR_TEXT);
    }

    private void onFound(String ip) {
        foundIp = ip;
        setStatus("발견 완료", COLOR_PRIMARY, COLOR_PRIMARY_DARK);
        showFoundAddress();
        progressBar.setProgress(progressBar.getMax());

        resetScanButtons();
        appendLog(String.format(Locale.getDefault(), "기기 발견! → %s:%d", ip, currentPort), "#00E676");
        if (currentPort == 7000) appendLog(getString(R.string.browser_connection_help), COLOR_TEXT_SECONDARY);
        pendingDeviceOpen = true;
        mainHandler.postDelayed(this::openPendingDevice, 800);
    }

    private void showFoundAddress() {
        tvFound.setText(String.format(Locale.getDefault(), "%s:%d", foundIp, currentPort));
        tvFound.setVisibility(View.VISIBLE);
    }

    private void openPendingDevice() {
        if (pendingDeviceOpen) openFoundDevice();
    }

    private void openFoundDevice() {
        // A scan may finish while the user is in another app. Open on return.
        if (!resumed || foundIp == null) return;
        pendingDeviceOpen = false;
        if (currentPort == 7000) {
            openBrowser(false);
        } else if (currentPort == 22) {
            if (!LocalNetworkPermission.isGranted(this)) {
                showLocalNetworkPermissionRequired();
                return;
            }
            Intent intent = new Intent(MainActivity.this, SshTerminalActivity.class);
            intent.putExtra("IP", foundIp);
            startActivity(intent);
        }
    }

    private void showLocalNetworkPermissionRequired() {
        resetScanButtons();
        setStatus(getString(R.string.local_network_permission_title), COLOR_ERROR, "#3B201D");
        appendLog(getString(R.string.local_network_permission_message), COLOR_ERROR);
    }

    private void onNotFound() {
        setStatus("검색 결과 없음", COLOR_ERROR, "#3B201D");
        progressBar.setProgress(progressBar.getMax());
        resetScanButtons();
        appendLog(String.format(Locale.getDefault(), "포트 %d 열린 기기 없음", currentPort), "#FF6B35");
    }

    private void onStopped() {
        setStatus("검색 중단됨", COLOR_TEXT_SECONDARY, COLOR_SURFACE_RAISED);
        resetScanButtons();
        appendLog("스캔 중단됨", "#DDDDDD");
    }

    private void openBrowser(boolean chooseBrowser) {
        if (!resumed || foundIp == null) return;
        String url = "http://"+foundIp+":"+currentPort+"/";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(chooseBrowser
                    ? Intent.createChooser(intent, getString(R.string.choose_browser)) : intent);
        } catch (Exception e) {
            appendLog("웹 브라우저를 찾을 수 없습니다", "#FF6B35");
        }
    }

    private void appendLog(String msg, String color) {
        postToMain(() -> {
            String ts = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(tvLog.getText());

            if (!android.text.TextUtils.isEmpty(tvLog.getText())) {
                sb.append("\n");
            }

            int start = sb.length();
            sb.append("[").append(ts).append("] ").append(msg);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor(color)),
                    start, sb.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvLog.setText(sb);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void postToMain(Runnable action) {
        mainHandler.post(() -> {
            if (!destroyed) action.run();
        });
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return packageInfo.versionName != null ? packageInfo.versionName : "0";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "0";
        }
    }

    private void cancelTask(Future<?> task) {
        if (task != null) task.cancel(true);
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundedBackground(COLOR_SURFACE, COLOR_BORDER, 1, 16));
        card.setElevation(dp(1));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private TextView makeText(String text, int sizeSp, String color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(Color.parseColor(color));
        tv.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private TextView makeLabel(String text) {
        TextView tv = makeText(text, 13, COLOR_TEXT, true);
        tv.setLetterSpacing(0.01f);
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private LinearLayout makeCardHeader(String title, String badge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(12));

        TextView label = makeLabel(title);
        label.setPadding(0, 0, 0, 0);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);
        row.addView(makePill(badge));
        return row;
    }

    private TextView makePill(String text) {
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY));
        pill.setTextSize(11f);
        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setIncludeFontPadding(false);
        pill.setPadding(dp(10), dp(6), dp(10), dp(6));
        pill.setBackground(roundedBackground(COLOR_SURFACE_RAISED, null, 0, 20));
        pill.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return pill;
    }

    private TextView makeButton(String label, String bgColor, String textColor) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor(textColor));
        btn.setTextSize(14f);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btn.setLetterSpacing(0.01f);

        btn.setIncludeFontPadding(false);
        btn.setPadding(dp(10), dp(4), dp(10), dp(5));
        btn.setLineSpacing(dp(3), 1.0f);
        btn.setGravity(Gravity.CENTER);

        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setBackground(roundedBackground(bgColor, null, 0, 13));

        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        return btn;
    }

    private void setButtonColors(TextView button, String background, String text, String stroke) {
        button.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        button.setTextColor(Color.parseColor(text));
        button.setBackground(roundedBackground(background, stroke, stroke == null ? 0 : 1, 13));
    }

    private void setButtonGradient(
            TextView button, String startColor, String endColor, String strokeColor, String textColor) {
        button.setTextColor(Color.parseColor(textColor));
        button.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        button.setBackground(gradientButtonBackground(startColor, endColor, strokeColor, 13));
        button.setElevation(dp(2));
    }

    private void setStatus(String text, String textColor, String backgroundColor) {
        tvStatus.setText(text);
        tvStatus.setTextColor(Color.parseColor(textColor));
        tvStatus.setBackground(roundedBackground(backgroundColor, null, 0, 20));
    }

    private void restoreStatusAppearance(int textColor) {
        if (textColor == Color.parseColor(COLOR_PRIMARY)) {
            tvStatus.setBackground(roundedBackground(COLOR_PRIMARY_DARK, null, 0, 20));
        } else if (textColor == Color.parseColor(COLOR_INFO)) {
            tvStatus.setBackground(roundedBackground("#142633", null, 0, 20));
        } else if (textColor == Color.parseColor(COLOR_ERROR)) {
            tvStatus.setBackground(roundedBackground("#3B201D", null, 0, 20));
        } else {
            tvStatus.setBackground(roundedBackground(COLOR_SURFACE_RAISED, null, 0, 20));
        }
    }

    private Drawable makeProgressDrawable() {
        GradientDrawable track = roundedBackground("#252B29", null, 0, 6);
        GradientDrawable fill = roundedBackground(COLOR_PRIMARY, null, 0, 6);
        ClipDrawable clippedFill = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clippedFill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private GradientDrawable roundedBackground(
            String fillColor, String strokeColor, int strokeWidthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fillColor));
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != null && strokeWidthDp > 0) {
            drawable.setStroke(dp(strokeWidthDp), Color.parseColor(strokeColor));
        }
        return drawable;
    }

    private Drawable gradientButtonBackground(
            String startColor, String endColor, String strokeColor, int radiusDp) {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor(startColor), Color.parseColor(endColor)});
        gradient.setCornerRadius(dp(radiusDp));
        gradient.setStroke(dp(1), Color.parseColor(strokeColor));

        GradientDrawable rippleMask = roundedBackground("#FFFFFF", null, 0, radiusDp);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
                gradient,
                rippleMask);
    }

    @SuppressWarnings("SameParameterValue")
    private View vSpace(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal)));
        return v;
    }

    @SuppressWarnings("SameParameterValue")
    private View hSpace(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dpVal), LinearLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
