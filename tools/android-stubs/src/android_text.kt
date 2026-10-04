// Android framework stubs — android.text (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.text

interface Editable {
    fun clear() {}
    fun append(text: CharSequence?): Editable = TODO()
    fun replace(start: Int, end: Int, text: CharSequence?): Editable = TODO()
    fun insert(where: Int, text: CharSequence?): Editable = TODO()
    fun delete(start: Int, end: Int): Editable = TODO()
    val length: Int
}

interface Spanned {
    val length: Int
}

interface TextWatcher {
    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    fun afterTextChanged(s: Editable?) {}
}

open class InputFilter {
    open fun filter(source: CharSequence?, start: Int, end: Int, dest: Spanned?, dstart: Int, dend: Int): CharSequence? = null
    class LengthFilter(max: Int) : InputFilter()
    class AllCaps : InputFilter()
}

object TextUtils {
    fun isEmpty(str: CharSequence?): Boolean = str == null || str.isEmpty()
    fun join(delimiter: CharSequence?, tokens: Iterable<*>?): String = ""
    fun join(delimiter: CharSequence?, vararg tokens: Any?): String = ""
    fun split(text: String?, expression: String?): Array<String> = emptyArray()
    fun htmlEncode(s: String?): String = ""
    fun concat(vararg text: CharSequence?): CharSequence = ""
    enum class TruncateAt { START, MIDDLE, END, MARQUEE }
}

object InputType {
    const val TYPE_NULL = 0
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_CLASS_NUMBER = 2
    const val TYPE_CLASS_PHONE = 3
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x00080000
    const val TYPE_TEXT_FLAG_CAP_SENTENCES = 0x00004000
    const val TYPE_TEXT_FLAG_CAP_WORDS = 0x00002000
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    const val TYPE_TEXT_VARIATION_URI = 0x00000010
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000010
    const val TYPE_NUMBER_FLAG_DECIMAL = 0x00002000
    const val TYPE_NUMBER_FLAG_SIGNED = 0x00001000
}

object Selection {
    fun setSelection(text: Spannable, start: Int, stop: Int) {}
}

open class Spannable : Spanned {
    override val length: Int get() = 0
}

class SpannableString(source: CharSequence?) : Spannable()
