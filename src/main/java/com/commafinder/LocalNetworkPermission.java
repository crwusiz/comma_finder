package com.commafinder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

final class LocalNetworkPermission {
    // Inlined string is safe on older Android; runtime requests are guarded by isGranted().
    @SuppressLint("InlinedApi")
    static final String PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK;

    private LocalNetworkPermission() {}

    static boolean isGranted(Context context) {
        // Android 17 requires runtime permission for apps targeting API 37+.
        return Build.VERSION.SDK_INT < 37
                || context.getApplicationInfo().targetSdkVersion < 37
                || ContextCompat.checkSelfPermission(context, PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
