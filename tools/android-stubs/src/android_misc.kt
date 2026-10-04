// Android framework stubs — remaining platform packages (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.database

interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean = false
    fun moveToNext(): Boolean = false
    fun getCount(): Int = 0
    fun getString(columnIndex: Int): String? = null
    fun getInt(columnIndex: Int): Int = 0
    fun getLong(columnIndex: Int): Long = 0L
    fun getColumnIndex(name: String): Int = 0
    fun getColumnIndexOrThrow(name: String): Int = 0
    fun getColumnName(columnIndex: Int): String = ""
    fun isNull(columnIndex: Int): Boolean = false
    override fun close() {}
}
