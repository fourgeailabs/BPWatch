package com.fourgeailabs.bpwatch.wear.testutil

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Minimal fake Context for JVM unit tests: only [getFilesDir] and
 * [getSharedPreferences] do anything real; every other Context
 * method throws (tests must not touch the Android framework).
 *
 * The stub overrides below were generated from the android-35
 * android.jar method signatures including nullability annotations,
 * so they keep compiling if the stub list is regenerated.
 */
class FakeContext(
    private val filesDir: File,
    private val prefs: MutableMap<String, FakeSharedPreferences> = mutableMapOf(),
) : Context() {

    override fun getFilesDir(): File = filesDir.apply { mkdirs() }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FakeSharedPreferences() }
    override fun getAssets(): android.content.res.AssetManager = TODO("not stubbed in FakeContext")
    override fun getResources(): android.content.res.Resources = TODO("not stubbed in FakeContext")
    override fun getPackageManager(): android.content.pm.PackageManager = TODO("not stubbed in FakeContext")
    override fun getContentResolver(): android.content.ContentResolver = TODO("not stubbed in FakeContext")
    override fun getMainLooper(): android.os.Looper = TODO("not stubbed in FakeContext")
    override fun getApplicationContext(): android.content.Context = TODO("not stubbed in FakeContext")
    override fun setTheme(p0: Int): Unit = TODO("not stubbed in FakeContext")
    override fun getTheme(): android.content.res.Resources.Theme = TODO("not stubbed in FakeContext")
    override fun getClassLoader(): ClassLoader = TODO("not stubbed in FakeContext")
    override fun getPackageName(): String = TODO("not stubbed in FakeContext")
    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = TODO("not stubbed in FakeContext")
    override fun getPackageResourcePath(): String = TODO("not stubbed in FakeContext")
    override fun getPackageCodePath(): String = TODO("not stubbed in FakeContext")
    override fun moveSharedPreferencesFrom(p0: android.content.Context, p1: String): Boolean = TODO("not stubbed in FakeContext")
    override fun deleteSharedPreferences(p0: String): Boolean = TODO("not stubbed in FakeContext")
    override fun openFileInput(p0: String): java.io.FileInputStream = TODO("not stubbed in FakeContext")
    override fun openFileOutput(p0: String, p1: Int): java.io.FileOutputStream = TODO("not stubbed in FakeContext")
    override fun deleteFile(p0: String): Boolean = TODO("not stubbed in FakeContext")
    override fun getFileStreamPath(p0: String): java.io.File = TODO("not stubbed in FakeContext")
    override fun getDataDir(): java.io.File = TODO("not stubbed in FakeContext")
    override fun getNoBackupFilesDir(): java.io.File = TODO("not stubbed in FakeContext")
    override fun getExternalFilesDir(p0: String?): java.io.File? = TODO("not stubbed in FakeContext")
    override fun getExternalFilesDirs(p0: String): Array<java.io.File> = TODO("not stubbed in FakeContext")
    override fun getObbDir(): java.io.File = TODO("not stubbed in FakeContext")
    override fun getObbDirs(): Array<java.io.File> = TODO("not stubbed in FakeContext")
    override fun getCacheDir(): java.io.File = TODO("not stubbed in FakeContext")
    override fun getCodeCacheDir(): java.io.File = TODO("not stubbed in FakeContext")
    override fun getExternalCacheDir(): java.io.File? = TODO("not stubbed in FakeContext")
    override fun getExternalCacheDirs(): Array<java.io.File> = TODO("not stubbed in FakeContext")
    override fun getExternalMediaDirs(): Array<java.io.File> = TODO("not stubbed in FakeContext")
    override fun fileList(): Array<String> = TODO("not stubbed in FakeContext")
    override fun getDir(p0: String, p1: Int): java.io.File = TODO("not stubbed in FakeContext")
    override fun openOrCreateDatabase(p0: String, p1: Int, p2: android.database.sqlite.SQLiteDatabase.CursorFactory): android.database.sqlite.SQLiteDatabase = TODO("not stubbed in FakeContext")
    override fun openOrCreateDatabase(p0: String, p1: Int, p2: android.database.sqlite.SQLiteDatabase.CursorFactory, p3: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase = TODO("not stubbed in FakeContext")
    override fun moveDatabaseFrom(p0: android.content.Context, p1: String): Boolean = TODO("not stubbed in FakeContext")
    override fun deleteDatabase(p0: String): Boolean = TODO("not stubbed in FakeContext")
    override fun getDatabasePath(p0: String): java.io.File = TODO("not stubbed in FakeContext")
    override fun databaseList(): Array<String> = TODO("not stubbed in FakeContext")
    override fun getWallpaper(): android.graphics.drawable.Drawable = TODO("not stubbed in FakeContext")
    override fun peekWallpaper(): android.graphics.drawable.Drawable = TODO("not stubbed in FakeContext")
    override fun getWallpaperDesiredMinimumWidth(): Int = TODO("not stubbed in FakeContext")
    override fun getWallpaperDesiredMinimumHeight(): Int = TODO("not stubbed in FakeContext")
    override fun setWallpaper(p0: android.graphics.Bitmap): Unit = TODO("not stubbed in FakeContext")
    override fun setWallpaper(p0: java.io.InputStream): Unit = TODO("not stubbed in FakeContext")
    override fun clearWallpaper(): Unit = TODO("not stubbed in FakeContext")
    override fun startActivity(p0: android.content.Intent): Unit = TODO("not stubbed in FakeContext")
    override fun startActivity(p0: android.content.Intent, p1: android.os.Bundle?): Unit = TODO("not stubbed in FakeContext")
    override fun startActivities(p0: Array<android.content.Intent>): Unit = TODO("not stubbed in FakeContext")
    override fun startActivities(p0: Array<android.content.Intent>, p1: android.os.Bundle): Unit = TODO("not stubbed in FakeContext")
    override fun startIntentSender(p0: android.content.IntentSender, p1: android.content.Intent?, p2: Int, p3: Int, p4: Int): Unit = TODO("not stubbed in FakeContext")
    override fun startIntentSender(p0: android.content.IntentSender, p1: android.content.Intent?, p2: Int, p3: Int, p4: Int, p5: android.os.Bundle?): Unit = TODO("not stubbed in FakeContext")
    override fun sendBroadcast(p0: android.content.Intent): Unit = TODO("not stubbed in FakeContext")
    override fun sendBroadcast(p0: android.content.Intent, p1: String?): Unit = TODO("not stubbed in FakeContext")
    override fun sendOrderedBroadcast(p0: android.content.Intent, p1: String?): Unit = TODO("not stubbed in FakeContext")
    override fun sendOrderedBroadcast(p0: android.content.Intent, p1: String?, p2: android.content.BroadcastReceiver?, p3: android.os.Handler?, p4: Int, p5: String?, p6: android.os.Bundle?): Unit = TODO("not stubbed in FakeContext")
    override fun sendBroadcastAsUser(p0: android.content.Intent, p1: android.os.UserHandle): Unit = TODO("not stubbed in FakeContext")
    override fun sendBroadcastAsUser(p0: android.content.Intent, p1: android.os.UserHandle, p2: String?): Unit = TODO("not stubbed in FakeContext")
    override fun sendOrderedBroadcastAsUser(p0: android.content.Intent, p1: android.os.UserHandle, p2: String?, p3: android.content.BroadcastReceiver, p4: android.os.Handler?, p5: Int, p6: String?, p7: android.os.Bundle?): Unit = TODO("not stubbed in FakeContext")
    override fun sendStickyBroadcast(p0: android.content.Intent): Unit = TODO("not stubbed in FakeContext")
    override fun sendStickyOrderedBroadcast(p0: android.content.Intent, p1: android.content.BroadcastReceiver, p2: android.os.Handler?, p3: Int, p4: String?, p5: android.os.Bundle?): Unit = TODO("not stubbed in FakeContext")
    override fun removeStickyBroadcast(p0: android.content.Intent): Unit = TODO("not stubbed in FakeContext")
    override fun sendStickyBroadcastAsUser(p0: android.content.Intent, p1: android.os.UserHandle): Unit = TODO("not stubbed in FakeContext")
    override fun sendStickyOrderedBroadcastAsUser(p0: android.content.Intent, p1: android.os.UserHandle, p2: android.content.BroadcastReceiver, p3: android.os.Handler?, p4: Int, p5: String?, p6: android.os.Bundle?): Unit = TODO("not stubbed in FakeContext")
    override fun removeStickyBroadcastAsUser(p0: android.content.Intent, p1: android.os.UserHandle): Unit = TODO("not stubbed in FakeContext")
    override fun registerReceiver(p0: android.content.BroadcastReceiver?, p1: android.content.IntentFilter): android.content.Intent? = TODO("not stubbed in FakeContext")
    override fun registerReceiver(p0: android.content.BroadcastReceiver?, p1: android.content.IntentFilter, p2: Int): android.content.Intent? = TODO("not stubbed in FakeContext")
    override fun registerReceiver(p0: android.content.BroadcastReceiver, p1: android.content.IntentFilter, p2: String?, p3: android.os.Handler?): android.content.Intent? = TODO("not stubbed in FakeContext")
    override fun registerReceiver(p0: android.content.BroadcastReceiver, p1: android.content.IntentFilter, p2: String?, p3: android.os.Handler?, p4: Int): android.content.Intent? = TODO("not stubbed in FakeContext")
    override fun unregisterReceiver(p0: android.content.BroadcastReceiver): Unit = TODO("not stubbed in FakeContext")
    override fun startService(p0: android.content.Intent): android.content.ComponentName? = TODO("not stubbed in FakeContext")
    override fun startForegroundService(p0: android.content.Intent): android.content.ComponentName? = TODO("not stubbed in FakeContext")
    override fun stopService(p0: android.content.Intent): Boolean = TODO("not stubbed in FakeContext")
    override fun bindService(p0: android.content.Intent, p1: android.content.ServiceConnection, p2: Int): Boolean = TODO("not stubbed in FakeContext")
    override fun unbindService(p0: android.content.ServiceConnection): Unit = TODO("not stubbed in FakeContext")
    override fun startInstrumentation(p0: android.content.ComponentName, p1: String?, p2: android.os.Bundle?): Boolean = TODO("not stubbed in FakeContext")
    override fun getSystemService(p0: String): Any = TODO("not stubbed in FakeContext")
    override fun getSystemServiceName(p0: Class<*>): String? = TODO("not stubbed in FakeContext")
    override fun checkPermission(p0: String, p1: Int, p2: Int): Int = TODO("not stubbed in FakeContext")
    override fun checkCallingPermission(p0: String): Int = TODO("not stubbed in FakeContext")
    override fun checkCallingOrSelfPermission(p0: String): Int = TODO("not stubbed in FakeContext")
    override fun checkSelfPermission(p0: String): Int = TODO("not stubbed in FakeContext")
    override fun enforcePermission(p0: String, p1: Int, p2: Int, p3: String?): Unit = TODO("not stubbed in FakeContext")
    override fun enforceCallingPermission(p0: String, p1: String?): Unit = TODO("not stubbed in FakeContext")
    override fun enforceCallingOrSelfPermission(p0: String, p1: String?): Unit = TODO("not stubbed in FakeContext")
    override fun grantUriPermission(p0: String, p1: android.net.Uri, p2: Int): Unit = TODO("not stubbed in FakeContext")
    override fun revokeUriPermission(p0: android.net.Uri, p1: Int): Unit = TODO("not stubbed in FakeContext")
    override fun revokeUriPermission(p0: String, p1: android.net.Uri, p2: Int): Unit = TODO("not stubbed in FakeContext")
    override fun checkUriPermission(p0: android.net.Uri, p1: Int, p2: Int, p3: Int): Int = TODO("not stubbed in FakeContext")
    override fun checkCallingUriPermission(p0: android.net.Uri, p1: Int): Int = TODO("not stubbed in FakeContext")
    override fun checkCallingOrSelfUriPermission(p0: android.net.Uri, p1: Int): Int = TODO("not stubbed in FakeContext")
    override fun checkUriPermission(p0: android.net.Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int): Int = TODO("not stubbed in FakeContext")
    override fun enforceUriPermission(p0: android.net.Uri, p1: Int, p2: Int, p3: Int, p4: String): Unit = TODO("not stubbed in FakeContext")
    override fun enforceCallingUriPermission(p0: android.net.Uri, p1: Int, p2: String): Unit = TODO("not stubbed in FakeContext")
    override fun enforceCallingOrSelfUriPermission(p0: android.net.Uri, p1: Int, p2: String): Unit = TODO("not stubbed in FakeContext")
    override fun enforceUriPermission(p0: android.net.Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int, p6: String?): Unit = TODO("not stubbed in FakeContext")
    override fun createPackageContext(p0: String, p1: Int): android.content.Context = TODO("not stubbed in FakeContext")
    override fun createContextForSplit(p0: String): android.content.Context = TODO("not stubbed in FakeContext")
    override fun createConfigurationContext(p0: android.content.res.Configuration): android.content.Context = TODO("not stubbed in FakeContext")
    override fun createDisplayContext(p0: android.view.Display): android.content.Context = TODO("not stubbed in FakeContext")
    override fun createDeviceProtectedStorageContext(): android.content.Context = TODO("not stubbed in FakeContext")
    override fun isDeviceProtectedStorage(): Boolean = TODO("not stubbed in FakeContext")
}
