// Android framework stubs — android.content.pm (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.content.pm

class PackageInfo {
    var packageName: String = ""
    var versionName: String? = null
    var versionCode: Int = 0
    var firstInstallTime: Long = 0
    var lastUpdateTime: Long = 0
}

class ApplicationInfo {
    var packageName: String = ""
    var className: String = ""
    var name: String = ""
}

open class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo()
    fun getApplicationLabel(info: ApplicationInfo): CharSequence = ""
    fun getLaunchIntentForPackage(packageName: String): android.content.Intent? = TODO()
    fun getInstalledApplications(flags: Int): List<ApplicationInfo> = emptyList()
    companion object { const val GET_META_DATA = 128; const val GET_ACTIVITIES = 1 }
}
