package com.commafinder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.view.View;
import android.widget.FrameLayout;
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
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;

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

    private static final int SOCKET_TIMEOUT_MS = 800;
    private static final int THREAD_POOL = 50;

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
    private PortScanner activeScanner;
    private int scanGeneration;
    private volatile boolean scanning = false;
    private volatile boolean destroyed = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))) {
                    detectIP();
                } else {
                    appendLog("위치 권한이 거부되어 Wi-Fi 이름(SSID)을 가져올 수 없습니다.", "#AAAAAA");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        detectIP();
        checkForUpdates();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
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
            progressBar.setMax(savedProgressMax);
            progressBar.setProgress(savedProgress);
        }
        if (scanning) applyScanningButtons(currentPort);

        lastDetectedIpString = "";
        detectIP();
        checkForUpdates();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0A0A0A"));

        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(0, 0, 0, dp(30));
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(28), dp(20), dp(8));

        TextView title = makeText("◉  COMMA FINDER  ◉", 24, "#00E676", true);
        title.setLetterSpacing(0.15f);
        title.setGravity(android.view.Gravity.CENTER);
        content.addView(title);

        TextView sub = makeText("포트 자동 탐색", 13, "#AAAAAA", false);
        sub.setPadding(0, dp(4), 0, dp(24));
        sub.setGravity(android.view.Gravity.CENTER);
        content.addView(sub);

        LinearLayout ipCard = makeCard();
        ipCard.addView(makeLabel("내 기기 IP"));

        ipListContainer = new LinearLayout(this);
        ipListContainer.setOrientation(LinearLayout.VERTICAL);
        ipListContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvLoading = makeText("IP 감지 중...", 16, "#4FC3F7", true);
        ipListContainer.addView(tvLoading);

        ipCard.addView(ipListContainer);
        content.addView(ipCard);
        content.addView(vSpace(15));

        LinearLayout statusCard = makeCard();
        tvStatus = makeText("대기", 15, "#DDDDDD", true);
        tvFound  = makeText("", 22, "#00E676", true);
        tvFound.setPadding(0, dp(6), 0, 0);
        tvFound.setVisibility(View.GONE);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(254);
        progressBar.setProgress(0);
        progressBar.setPadding(0, dp(10), 0, dp(4));
        progressBar.getProgressDrawable().setColorFilter(
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                        Color.parseColor("#00E676"), BlendModeCompat.SRC_IN));

        statusCard.addView(makeLabel("스캔 상태"));
        statusCard.addView(tvStatus);
        statusCard.addView(progressBar);
        statusCard.addView(tvFound);
        content.addView(statusCard);

        content.addView(vSpace(15));

        LinearLayout gridRow1 = new LinearLayout(this);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        gridRow1.setBaselineAligned(false);
        gridRow1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnScan7000 = makeButton("스캔 시작\n(포트:7000)", "#00E676", "#000000");
        btnScan7000.setLayoutParams(new LinearLayout.LayoutParams(0, dp(60), 1.0f));
        btnScan7000.setOnClickListener(v -> { if (scanning) stopScan(); else startScan(7000); });
        gridRow1.addView(btnScan7000);

        gridRow1.addView(hSpace(10));

        btnScan22 = makeButton("스캔 시작\n(포트:22)", "#00E676", "#000000");
        btnScan22.setLayoutParams(new LinearLayout.LayoutParams(0, dp(60), 1.0f));
        btnScan22.setOnClickListener(v -> { if (scanning) stopScan(); else startScan(22); });
        gridRow1.addView(btnScan22);

        content.addView(gridRow1);
        content.addView(vSpace(10));

        LinearLayout gridRow2 = new LinearLayout(this);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        gridRow2.setBaselineAligned(false);
        gridRow2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView btnRefresh = makeButton("새로고침", "#111111", "#00E676");
        ((GradientDrawable) btnRefresh.getBackground()).setStroke(dp(1), Color.parseColor("#00E676"));
        btnRefresh.setLayoutParams(new LinearLayout.LayoutParams(0, dp(60), 1.0f));
        btnRefresh.setOnClickListener(v -> {
            tvLog.setText("");
            ipListContainer.removeAllViews();
            ipListContainer.addView(makeText("IP 감지 중...", 16, "#4FC3F7", true));
            lastDetectedIpString = "";
            detectIP();
        });
        gridRow2.addView(btnRefresh);

        gridRow2.addView(hSpace(10));

        btnUpdate = makeButton("업데이트\n확인 중...", "#333333", "#888888");
        ((GradientDrawable) btnUpdate.getBackground()).setStroke(0, Color.TRANSPARENT);
        btnUpdate.setLayoutParams(new LinearLayout.LayoutParams(0, dp(60), 1.0f));
        btnUpdate.setEnabled(false);
        gridRow2.addView(btnUpdate);

        content.addView(gridRow2);
        content.addView(vSpace(15));

        LinearLayout logCard = makeCard();
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#000000"));

        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#FFFFFF"));
        tvLog.setTextSize(11f);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setLineSpacing(dp(2), 1.0f);
        logScroll.addView(tvLog);
        logCard.addView(makeLabel("로그"));
        logCard.addView(logScroll);
        content.addView(logCard);

        scroll.addView(content);
        root.addView(scroll);

        TextView tvVersion = new TextView(this);
        tvVersion.setText(String.format(Locale.getDefault(), "v%s", getCurrentVersionName()));
        tvVersion.setTextColor(Color.parseColor("#666666"));
        tvVersion.setTextSize(12f);
        tvVersion.setTypeface(android.graphics.Typeface.MONOSPACE);

        FrameLayout.LayoutParams vParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        vParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        vParams.setMargins(0, 0, dp(16), dp(16));
        tvVersion.setLayoutParams(vParams);
        root.addView(tvVersion);

        setContentView(root);
    }

    private void checkForUpdates() {
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

                    final String finalApkUrl = (apkUrl != null) ? apkUrl : json.getString("html_url");
                    boolean updateAvailable = VersionUtils.isUpdateAvailable(
                            getCurrentVersionName(), remoteVersionName);

                    postToMain(() -> {
                        if (updateAvailable) {
                            btnUpdate.setText(String.format(Locale.getDefault(), "업데이트\n가능 (v%s)", remoteVersionName));
                            GradientDrawable bg = (GradientDrawable) btnUpdate.getBackground();
                            bg.setColor(Color.parseColor("#00E676"));
                            btnUpdate.setTextColor(Color.parseColor("#000000"));
                            btnUpdate.setEnabled(true);
                            btnUpdate.setOnClickListener(v -> {
                                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(finalApkUrl));
                                startActivity(i);
                            });
                        } else {
                            btnUpdate.setText("최신 버전");
                            GradientDrawable bg = (GradientDrawable) btnUpdate.getBackground();
                            bg.setColor(Color.parseColor("#333333"));
                            btnUpdate.setTextColor(Color.parseColor("#888888"));
                            btnUpdate.setEnabled(false);
                            btnUpdate.setOnClickListener(null);
                        }
                    });
                } else {
                    postToMain(() -> btnUpdate.setText("업데이트\n확인 실패"));
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    postToMain(() -> btnUpdate.setText("업데이트\n확인 실패"));
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
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

                if (lastDetectedIpString.equals(newText) && !mySubnets.isEmpty()) return;

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
                        tvLabel.setText(String.format("[%s]", label));
                        tvLabel.setTextColor(Color.parseColor("#4FC3F7"));
                        tvLabel.setTextSize(14f);
                        tvLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        tvLabel.setPadding(0, 0, dp(12), dp(4));
                        tvLabel.setMaxLines(1);
                        tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);

                        TextView tvIpVal = new TextView(MainActivity.this);
                        tvIpVal.setText(ip);
                        tvIpVal.setTextColor(Color.parseColor("#4FC3F7"));
                        tvIpVal.setTextSize(15f);
                        tvIpVal.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        tvIpVal.setPadding(0, 0, 0, dp(4));

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
                    ipListContainer.addView(makeText("IP 감지 실패", 16, "#FF6B35", true));
                    appendLog("IP 감지 실패 — Wi-Fi 연결 확인", "#FF6B35");
                }
            });
        });
    }

    @SuppressLint("MissingPermission")
    private String getWifiSsid() {
        try {
            WifiInfo info = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ConnectivityManager connectivityManager =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (connectivityManager != null) {
                    Network network = connectivityManager.getActiveNetwork();
                    if (network != null) {
                        NetworkCapabilities capabilities =
                                connectivityManager.getNetworkCapabilities(network);
                        if (capabilities != null
                                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                && capabilities.getTransportInfo() instanceof WifiInfo) {
                            info = (WifiInfo) capabilities.getTransportInfo();
                        }
                    }
                }
            } else {
                WifiManager wifiManager = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                @SuppressWarnings("deprecation")
                WifiInfo legacyInfo = wifiManager != null ? wifiManager.getConnectionInfo() : null;
                info = legacyInfo;
            }

            if (info != null && info.getSSID() != null) {
                String ssid = info.getSSID().replace("\"", "");
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
        if (mySubnets.isEmpty()) { appendLog("IP 대역을 먼저 감지해야 합니다", "#FF6B35"); return; }
        scanning = true;
        foundIp = null;
        currentPort = targetPort;
        int generation = ++scanGeneration;
        List<String> subnets = new ArrayList<>(mySubnets);

        applyScanningButtons(targetPort);
        if (targetPort == 7000) {
            appendLog("7000 포트가 개방된 comma 기기를 찾으면 웹페이지를 자동으로 오픈합니다", "#AAAAAA");
        } else {
            appendLog("22 포트가 개방된 comma 기기를 찾으면 저장된 Private Key를 이용해 ssh 접속합니다", "#AAAAAA");
        }

        tvFound.setVisibility(View.GONE);

        final int total = subnets.size() * NetworkUtils.LAST_HOST;
        progressBar.setMax(total);
        progressBar.setProgress(0);

        tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
        tvStatus.setText("● 스캔 중...");
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
            btnScan7000.setText("중  단");
            btnScan22.setEnabled(false);
            btnScan22.setTextColor(Color.parseColor("#555555"));
        } else {
            btnScan22.setText("중  단");
            btnScan7000.setEnabled(false);
            btnScan7000.setTextColor(Color.parseColor("#555555"));
        }
    }

    private void resetScanButtons() {
        btnScan7000.setText("스캔 시작\n(포트:7000)");
        btnScan7000.setEnabled(true);
        btnScan7000.setTextColor(Color.parseColor("#000000"));

        btnScan22.setText("스캔 시작\n(포트:22)");
        btnScan22.setEnabled(true);
        btnScan22.setTextColor(Color.parseColor("#000000"));
    }

    private void onFound(String ip) {
        foundIp = ip;
        tvStatus.setTextColor(Color.parseColor("#00E676")); tvStatus.setText("● 발견!");
        tvFound.setText(String.format(Locale.getDefault(), "%s:%d", ip, currentPort));
        tvFound.setVisibility(View.VISIBLE);
        progressBar.setProgress(progressBar.getMax());

        resetScanButtons();
        appendLog(String.format(Locale.getDefault(), "기기 발견! → %s:%d", ip, currentPort), "#00E676");

        if (currentPort == 7000) {
            mainHandler.postDelayed(this::openChrome, 800);
        } else if (currentPort == 22) {
            mainHandler.postDelayed(() -> {
                Intent intent = new Intent(MainActivity.this, SshTerminalActivity.class);
                intent.putExtra("IP", ip);
                startActivity(intent);
            }, 800);
        }
    }

    private void onNotFound() {
        tvStatus.setTextColor(Color.parseColor("#FF6B35")); tvStatus.setText("● 미발견");
        progressBar.setProgress(progressBar.getMax());
        resetScanButtons();
        appendLog(String.format(Locale.getDefault(), "포트 %d 열린 기기 없음", currentPort), "#FF6B35");
    }

    private void onStopped() {
        tvStatus.setTextColor(Color.parseColor("#DDDDDD")); tvStatus.setText("중단됨");
        resetScanButtons();
        appendLog("스캔 중단됨", "#DDDDDD");
    }

    private void openChrome() {
        if (foundIp == null) return;
        String url = "http://"+foundIp+":"+currentPort+"/";
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage("com.android.chrome");
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception noBrowser) {
                appendLog("웹 브라우저를 찾을 수 없습니다", "#FF6B35");
            }
        }
    }

    private void appendLog(String msg, String color) {
        postToMain(() -> {
            String ts = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(tvLog.getText());

            // 기존 텍스트가 있을 경우에만 개행 추가
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
                @SuppressWarnings("deprecation")
                PackageInfo legacyPackageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                packageInfo = legacyPackageInfo;
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
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111111"));
        bg.setStroke(dp(1), Color.parseColor("#1E1E1E"));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private TextView makeText(String text, int sizeSp, String color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(Color.parseColor(color));
        tv.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.MONOSPACE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private TextView makeLabel(String text) {
        TextView tv = makeText(text.toUpperCase(Locale.ROOT), 11, "#888888", true);
        tv.setLetterSpacing(0.10f);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView makeButton(String label, String bgColor, String textColor) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor(textColor));
        btn.setTextSize(14f);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btn.setLetterSpacing(0.05f);

        btn.setIncludeFontPadding(false);
        btn.setPadding(0, dp(2), 0, dp(4));
        btn.setLineSpacing(dp(2), 1.0f);
        btn.setGravity(android.view.Gravity.CENTER);

        btn.setClickable(true);
        btn.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColor));
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);

        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        return btn;
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
