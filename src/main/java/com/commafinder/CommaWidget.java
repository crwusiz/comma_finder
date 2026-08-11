package com.commafinder;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.List;

public class CommaWidget extends AppWidgetProvider {

    private static final String ACTION_SCAN_PREFIX = "com.commafinder.action.SCAN_WIDGET_";
    private static final String EXTRA_WIDGET_ID = "WIDGET_ID";
    private static final int PORT = 7000;
    private static final int TIMEOUT_MS = 800;
    private static final int THREAD_POOL = 50;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            updateWidgetUI(ctx, id, ",", "#00E676", "COMMA", "탭하여 접속", R.drawable.widget_bg_default);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (intent.getAction() != null && intent.getAction().startsWith(ACTION_SCAN_PREFIX)) {
            int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1);
            if (widgetId != -1) {
                updateWidgetUI(context, widgetId, ",", "#4FC3F7", "스캔중", "잠시 대기", R.drawable.widget_bg_scanning);

                PendingResult pendingResult = goAsync();
                new Thread(() -> {
                    try {
                        performScan(context.getApplicationContext(), widgetId);
                    } finally {
                        pendingResult.finish();
                    }
                }).start();
            }
        }
    }

    private void performScan(Context context, int widgetId) {
        List<String> subnets;
        try {
            subnets = NetworkUtils.getScannableSubnets();
        } catch (Exception ignored) {
            showTempResult(context, widgetId, "#FF4444", "오류", "연결 확인", R.drawable.widget_bg_error);
            return;
        }
        if (subnets.isEmpty()) {
            showTempResult(context, widgetId, "#FF4444", "오류", "연결 확인", R.drawable.widget_bg_error);
            return;
        }

        PortScanner scanner = new PortScanner(THREAD_POOL, TIMEOUT_MS);
        for (int attempt = 1; attempt <= 2; attempt++) {
            PortScanner.Result result = scanner.scan(subnets, PORT, ignored -> {});
            if (result.status() == PortScanner.Status.FOUND) {
                String ip = result.ip();
                updateWidgetUI(context, widgetId, ",", "#00E676", "발견!", ip, R.drawable.widget_bg);
                openBrowser(context, ip);
                MAIN_HANDLER.postDelayed(() ->
                        updateWidgetUI(context, widgetId, ",", "#00E676", "COMMA", "탭하여 접속", R.drawable.widget_bg_default), 3000);
                return;
            }

            if (attempt == 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        showTempResult(context, widgetId, "#FF4444", "미발견", "다시 시도", R.drawable.widget_bg_error);
    }

    private void openBrowser(Context context, String ip) {
        Uri uri = Uri.parse("http://" + ip + ":" + PORT + "/");
        Intent chromeIntent = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage("com.android.chrome")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chromeIntent);
        } catch (Exception chromeUnavailable) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // The result remains visible on the widget when no browser is installed.
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void updateWidgetUI(Context ctx, int widgetId, String icon, String color, String status, String sub, int bgRes) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        rv.setTextViewText(R.id.widget_icon, icon);
        rv.setTextColor(R.id.widget_icon, Color.parseColor(color));
        rv.setTextViewText(R.id.widget_status, status);
        rv.setTextViewText(R.id.widget_sub, sub);

        if (bgRes != 0) {
            rv.setInt(R.id.widget_root, "setBackgroundResource", bgRes);
        }

        Intent intent = new Intent(ctx, CommaWidget.class);
        intent.setAction(ACTION_SCAN_PREFIX + widgetId);
        intent.putExtra(EXTRA_WIDGET_ID, widgetId);

        PendingIntent pi = PendingIntent.getBroadcast(ctx, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        rv.setOnClickPendingIntent(R.id.widget_root, pi);
        AppWidgetManager.getInstance(ctx).updateAppWidget(widgetId, rv);
    }

    @SuppressWarnings("SameParameterValue")
    private void showTempResult(Context ctx, int widgetId, String color, String status, String sub, int bgRes) {
        updateWidgetUI(ctx, widgetId, ",", color, status, sub, bgRes);
        MAIN_HANDLER.postDelayed(() ->
                updateWidgetUI(ctx, widgetId, ",", "#00E676", "COMMA", "탭하여 접속", R.drawable.widget_bg_default), 3000);
    }
}
