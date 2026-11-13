package uk.ayachi.cftunnel;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** User-controlled background access; no silent changes to system policy. */
final class BackgroundPolicy {
    static boolean exempt(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }
    static boolean restricted(Context c) {
        return Build.VERSION.SDK_INT>=28 && c.getSystemService(ActivityManager.class).isBackgroundRestricted();
    }
    static String summary(Context c) {
        return "电池优化豁免："+(exempt(c)?"已允许":"未允许")+
            "；后台受限："+(restricted(c)?"是（请解除）":"否")+
            "。厂商的后台活动限制仍需在应用设置中允许。";
    }
    static String diagnostics(Context c) {
        ActivityManager.RunningAppProcessInfo info=new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        PowerManager pm=c.getSystemService(PowerManager.class);
        return "importance="+info.importance+"; batteryExempt="+exempt(c)+
            "; backgroundRestricted="+restricted(c)+"; powerSave="+pm.isPowerSaveMode()+
            "; deviceIdle="+pm.isDeviceIdleMode()+"; interactive="+pm.isInteractive();
    }
    static void openAppSettings(Activity a) {
        try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:"+a.getPackageName()))); }
        catch(android.content.ActivityNotFoundException e) { a.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }
    static void requestExemption(Activity a) {
        if(exempt(a)) { openAppSettings(a); return; }
        try { a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:"+a.getPackageName()))); }
        catch(android.content.ActivityNotFoundException | SecurityException e) { openAppSettings(a); }
    }
}
