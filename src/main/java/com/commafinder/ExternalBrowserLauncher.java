package com.commafinder;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opens a URL in a chosen browser after checking that browser's own LAN permission. */
final class ExternalBrowserLauncher {
    private static final String LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String STATE_URL = "externalBrowser.pendingUrl";
    private static final String STATE_COMPONENT = "externalBrowser.pendingComponent";

    private final AppCompatActivity activity;
    private AlertDialog dialog;
    private String pendingUrl;
    private ComponentName pendingComponent;
    private boolean destroyed;

    ExternalBrowserLauncher(AppCompatActivity activity) {
        this.activity = activity;
    }

    void open(String url) {
        if (destroyed || !LocalWebTarget.isExternalWebUrl(url)) return;
        clearPending();
        dismissDialog();
        List<BrowserTarget> browsers = findBrowsers(url);
        if (browsers.isEmpty()) {
            showNoBrowser();
        } else if (browsers.size() == 1) {
            openInBrowser(url, browsers.get(0));
        } else {
            CharSequence[] labels = new CharSequence[browsers.size()];
            for (int i = 0; i < browsers.size(); i++) {
                BrowserTarget browser = browsers.get(i);
                boolean duplicateLabel = false;
                for (BrowserTarget other : browsers) {
                    if (other != browser && browser.label.equals(other.label)) {
                        duplicateLabel = true;
                        break;
                    }
                }
                labels[i] = duplicateLabel
                        ? browser.label + " (" + browser.component.getPackageName() + ")"
                        : browser.label;
            }
            dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.choose_browser)
                    .setItems(labels, (selectedDialog, index) ->
                            openInBrowser(url, browsers.get(index)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /** Called after returning from the selected browser's app settings. */
    void onResume() {
        if (destroyed || pendingUrl == null || pendingComponent == null) return;
        String url = pendingUrl;
        ComponentName component = pendingComponent;
        // A denied return should not reopen Settings or show the permission prompt again.
        clearPending();
        for (BrowserTarget browser : findBrowsers(url)) {
            if (!browser.component.equals(component)) continue;
            if (canAccessLocalNetwork(url, browser)) launch(url, browser);
            else Toast.makeText(activity,
                    activity.getString(R.string.browser_local_network_still_denied, browser.label),
                    Toast.LENGTH_LONG).show();
            return;
        }
        showNoBrowser();
    }

    void saveState(Bundle state) {
        if (pendingUrl != null && pendingComponent != null) {
            state.putString(STATE_URL, pendingUrl);
            state.putString(STATE_COMPONENT, pendingComponent.flattenToString());
        }
    }

    void restoreState(Bundle state) {
        if (state == null) return;
        String url = state.getString(STATE_URL);
        String component = state.getString(STATE_COMPONENT);
        if (LocalWebTarget.isExternalWebUrl(url) && component != null) {
            pendingUrl = url;
            pendingComponent = ComponentName.unflattenFromString(component);
        }
    }

    void destroy() {
        destroyed = true;
        dismissDialog();
        clearPending();
    }

    private void openInBrowser(String url, BrowserTarget browser) {
        if (destroyed) return;
        dismissDialog();
        if (canAccessLocalNetwork(url, browser)) {
            launch(url, browser);
            return;
        }
        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.browser_local_network_permission_title)
                .setMessage(activity.getString(
                        R.string.browser_local_network_permission_message, browser.label))
                .setPositiveButton(R.string.open_settings, (selectedDialog, which) -> {
                    pendingUrl = url;
                    pendingComponent = browser.component;
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + browser.component.getPackageName())));
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        clearPending();
                        Toast.makeText(activity, R.string.browser_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean canAccessLocalNetwork(String url, BrowserTarget browser) {
        if (Build.VERSION.SDK_INT < 37
                || !NetworkUtils.isPrivateIpv4(Uri.parse(url).getHost())) return true;
        try {
            ApplicationInfo application = activity.getPackageManager().getApplicationInfo(
                    browser.component.getPackageName(), 0);
            // Check the effective UID permission even for browsers targeting older SDKs:
            // Android 17's local-network compatibility protection can apply to them too.
            return activity.checkPermission(LOCAL_NETWORK_PERMISSION, -1, application.uid)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException missingBrowser) {
            return false;
        }
    }

    private void launch(String url, BrowserTarget browser) {
        if (destroyed) return;
        try {
            activity.startActivity(webIntent(url).setComponent(browser.component));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            showNoBrowser();
        }
    }

    private List<BrowserTarget> findBrowsers(String url) {
        PackageManager packageManager = activity.getPackageManager();
        // Web intents can otherwise be filtered down to the default browser,
        // preventing the user from choosing another installed browser.
        int queryFlags = PackageManager.MATCH_ALL | PackageManager.MATCH_DEFAULT_ONLY;
        // Intersect generic HTTP handlers with this URL's handlers. A specialized
        // deep-link application should not appear as a general-purpose browser.
        Set<String> httpBrowsers = new HashSet<>();
        for (ResolveInfo resolved : packageManager.queryIntentActivities(
                webIntent("http://example.com/"), queryFlags)) {
            if (isEligible(resolved.activityInfo)) {
                httpBrowsers.add(resolved.activityInfo.packageName);
            }
        }
        Map<String, BrowserTarget> byPackage = new LinkedHashMap<>();
        for (ResolveInfo resolved : packageManager.queryIntentActivities(
                webIntent(url), queryFlags)) {
            ActivityInfo info = resolved.activityInfo;
            if (!isEligible(info) || !httpBrowsers.contains(info.packageName)) continue;
            ComponentName component = new ComponentName(info.packageName, info.name);
            CharSequence loadedLabel = info.applicationInfo.loadLabel(packageManager);
            String label = loadedLabel == null ? info.packageName : loadedLabel.toString();
            BrowserTarget previous = byPackage.get(info.packageName);
            if (previous == null || component.flattenToString().compareTo(
                    previous.component.flattenToString()) < 0) {
                byPackage.put(info.packageName, new BrowserTarget(component, label));
            }
        }
        List<BrowserTarget> browsers = new ArrayList<>(byPackage.values());
        Collator collator = Collator.getInstance();
        browsers.sort((left, right) -> {
            int labelOrder = collator.compare(left.label, right.label);
            return labelOrder != 0 ? labelOrder
                    : left.component.getPackageName().compareTo(right.component.getPackageName());
        });
        return browsers;
    }

    private boolean isEligible(ActivityInfo info) {
        return info != null && info.exported && info.enabled
                && info.applicationInfo != null && info.applicationInfo.enabled
                && !activity.getPackageName().equals(info.packageName)
                && (info.permission == null || activity.checkSelfPermission(info.permission)
                        == PackageManager.PERMISSION_GRANTED);
    }

    private static Intent webIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
    }

    private void clearPending() {
        pendingUrl = null;
        pendingComponent = null;
    }

    private void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void showNoBrowser() {
        Toast.makeText(activity, R.string.local_web_no_browser, Toast.LENGTH_LONG).show();
    }

    private static final class BrowserTarget {
        final ComponentName component;
        final String label;

        BrowserTarget(ComponentName component, String label) {
            this.component = component;
            this.label = label;
        }
    }
}
