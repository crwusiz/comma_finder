package com.commafinder;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.widget.RemoteViews;

public class CommaWidget extends AppWidgetProvider {

    private static final String ACTION_SCAN_PREFIX = "com.commafinder.action.SCAN_WIDGET_";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            updateWidget(context, manager, widgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || (action != null && action.startsWith(ACTION_SCAN_PREFIX))) {
            // Migrate installed widgets away from the old broadcast scan action.
            // The next tap opens the activity directly so permission and browser UI
            // follow a foreground user action.
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] widgetIds = manager.getAppWidgetIds(new ComponentName(context, CommaWidget.class));
            onUpdate(context, manager, widgetIds);
        }
    }

    private void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        views.setTextViewText(R.id.widget_icon, context.getString(R.string.widget_icon));
        views.setTextColor(R.id.widget_icon, Color.parseColor("#00E676"));
        views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_status_default));
        views.setTextViewText(R.id.widget_sub, context.getString(R.string.widget_action_hint));
        views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_default);

        Intent intent = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_SCAN_WEB)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        manager.updateAppWidget(widgetId, views);
    }
}
