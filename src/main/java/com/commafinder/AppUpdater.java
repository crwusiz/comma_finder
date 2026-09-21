package com.commafinder;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** DownloadManager owns the transfer so it survives Activity and process recreation. */
final class AppUpdater {
    interface Listener {
        void showState(String label, Runnable action);
        void showMessage(String message);
        void onIdle();
        void requestInstallPermission(Intent intent);
    }

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private final Activity activity;
    private final Listener listener;
    private final DownloadManager downloads;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean resumed;
    private int generation;

    AppUpdater(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        downloads = activity.getSystemService(DownloadManager.class);
        preferences = activity.getSharedPreferences("app_update", Context.MODE_PRIVATE);
    }

    boolean hasDownload() {
        return preferences.getLong("id", -1) != -1;
    }

    void resume() {
        resumed = true;
        refresh();
    }

    void pause() {
        resumed = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
    }

    void destroy() {
        pause();
        worker.shutdownNow();
    }

    void start(String url, String version) {
        if (hasDownload()) return;
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("안전한 APK 다운로드 주소가 아닙니다.");
            }
            String filename = "update-" + System.currentTimeMillis() + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("Comma Finder v" + version)
                    .setDescription("앱 업데이트 다운로드")
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(activity, null, "updates/" + filename);
            request.addRequestHeader("User-Agent", "CommaFinder-App");
            long id = downloads.enqueue(request);
            preferences.edit().putLong("id", id).putString("file", filename)
                    .putString("url", url).putString("version", version)
                    .putBoolean("auto_install", true).apply();
            listener.showMessage("업데이트 다운로드를 시작합니다. 완료되면 설치 화면이 열립니다.");
            refresh();
        } catch (Exception e) {
            listener.showMessage("다운로드를 시작할 수 없습니다: " + e.getMessage());
            listener.showState("다운로드 재시도", () -> start(url, version));
        }
    }

    void onPermissionResult() {
        if (!hasDownload()) return;
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            preferences.edit().putBoolean("auto_install", true).apply();
        } else {
            listener.showMessage("설치 권한이 허용되지 않았습니다. ‘업데이트 설치’를 눌러 다시 진행할 수 있습니다.");
        }
        refresh();
    }

    void refresh() {
        int token = ++generation;
        handler.removeCallbacksAndMessages(null);
        if (!resumed || !hasDownload()) return;
        listener.showState("다운로드 확인 중…", null);
        poll(token);
    }

    private void poll(int token) {
        long id = preferences.getLong("id", -1);
        File base = activity.getExternalFilesDir(null);
        String filename = preferences.getString("file", "");
        File apk = base == null || filename.isEmpty() ? null : new File(base, "updates/" + filename);
        worker.execute(() -> {
            try {
                int status;
                long downloaded;
                long total;
                int reason;
                try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        throw new IOException("다운로드 기록이 없습니다. 다시 다운로드해 주세요.");
                    }
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    throw new IOException(downloadError(reason));
                }
                boolean alreadyInstalled = false;
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (apk == null || !apk.isFile()) throw new IOException("다운로드한 APK를 찾을 수 없습니다.");
                    PackageInfo incoming = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (incoming == null || !activity.getPackageName().equals(incoming.packageName)) {
                        throw new IOException("올바른 Comma Finder APK가 아닙니다.");
                    }
                    PackageInfo current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                    alreadyInstalled = incoming.getLongVersionCode() <= current.getLongVersionCode();
                }
                boolean installed = alreadyInstalled;
                post(token, id, () -> {
                    if (installed) {
                        clear();
                        listener.onIdle();
                    } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        listener.showState("업데이트 설치", () -> install(apk));
                        if (preferences.getBoolean("auto_install", false)) install(apk);
                    } else {
                        String label = status == DownloadManager.STATUS_PAUSED ? "다운로드 대기 중…"
                                : total > 0 ? String.format(Locale.getDefault(), "다운로드 %d%%", downloaded * 100 / total)
                                : "다운로드 중…";
                        listener.showState(label, null);
                        handler.postDelayed(() -> poll(token), 1000);
                    }
                });
            } catch (Exception e) {
                post(token, id, () -> {
                    listener.showMessage("업데이트 다운로드 실패: " + e.getMessage());
                    listener.showState("다운로드 재시도", this::retry);
                });
            }
        });
    }

    private void post(int token, long id, Runnable action) {
        handler.post(() -> {
            if (resumed && generation == token && preferences.getLong("id", -1) == id) action.run();
        });
    }

    private void install(File apk) {
        if (!resumed) return;
        // A cancelled permission/installer screen must not automatically reopen on resume.
        preferences.edit().putBoolean("auto_install", false).apply();
        try {
            if (!apk.isFile()) {
                listener.showMessage("APK 파일이 없습니다. 다시 다운로드해 주세요.");
                listener.showState("다운로드 재시도", this::retry);
                return;
            }
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                listener.showMessage("설정에서 ‘이 출처 허용’을 켜면 설치를 이어서 진행합니다.");
                listener.requestInstallPermission(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("update", uri));
            activity.startActivity(intent);
        } catch (Exception e) {
            listener.showMessage("설치 화면을 열 수 없습니다: " + e.getMessage());
        }
    }

    private void retry() {
        String url = preferences.getString("url", "");
        String version = preferences.getString("version", "");
        clear();
        start(url, version);
    }

    private void clear() {
        long id = preferences.getLong("id", -1);
        if (id != -1) downloads.remove(id);
        preferences.edit().clear().apply();
    }

    private static String downloadError(int reason) {
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "저장 공간이 부족합니다.";
        if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND) return "저장소를 사용할 수 없습니다.";
        if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR) return "네트워크 연결을 확인한 뒤 다시 시도해 주세요.";
        return "다운로드 오류 (" + reason + "). 다시 시도해 주세요.";
    }
}
