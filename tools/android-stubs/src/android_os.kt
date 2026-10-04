// Android framework stubs — android.os (type-check only; never shipped).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.os

/** Marker for the types the app hands to the framework across process boundaries. */
interface Parcelable

object Build {
    object VERSION {
        const val SDK_INT: Int = 34
        const val RELEASE: String = "14"
    }
    object VERSION_CODES {
        const val LOLLIPOP = 21; const val M = 23; const val N = 24; const val O = 26
        const val P = 28; const val Q = 29; const val R = 30; const val S = 31
        const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34
    }
    const val MANUFACTURER: String = "Stub"
    const val MODEL: String = "Stub"
    const val BRAND: String = "Stub"
    const val DEVICE: String = "Stub"
}

class Bundle {
    constructor()
    constructor(other: Bundle?)
    fun putString(key: String, value: String?) {}
    fun putInt(key: String, value: Int) {}
    fun putLong(key: String, value: Long) {}
    fun putBoolean(key: String, value: Boolean) {}
    fun putStringArrayList(key: String, value: ArrayList<String>?) {}
    fun getString(key: String): String? = null
    fun getString(key: String, def: String?): String? = null
    fun getInt(key: String, def: Int): Int = def
    fun getLong(key: String, def: Long): Long = def
    fun getBoolean(key: String, def: Boolean): Boolean = def
    fun getStringArrayList(key: String): ArrayList<String>? = TODO()
    fun containsKey(key: String): Boolean = false
    fun keySet(): MutableSet<String> = HashSet()
    fun isEmpty(): Boolean = true
}

class Looper {
    companion object { fun getMainLooper(): Looper = Looper(); fun myLooper(): Looper? = null }
}

open class Handler {
    constructor()
    constructor(looper: Looper)
    open fun post(r: Runnable): Boolean = true
    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean = true
    open fun removeCallbacks(r: Runnable) {}
}

class SystemClock {
    companion object {
        fun uptimeMillis(): Long = 0L
        fun elapsedRealtime(): Long = 0L
        fun sleep(millis: Long) {}
    }
}

class VibrationEffect {
    companion object {
        const val DEFAULT_AMPLITUDE = -1
        fun createOneShot(milliseconds: Long, amplitude: Int): VibrationEffect = VibrationEffect()
        fun createWaveform(timings: LongArray, repeat: Int): VibrationEffect = VibrationEffect()
    }
}

open class Vibrator {
    open fun hasVibrator(): Boolean = true
    open fun vibrate(milliseconds: Long) {}
    open fun vibrate(pattern: LongArray, repeat: Int) {}
    open fun vibrate(effect: VibrationEffect) {}
    open fun cancel() {}
}
