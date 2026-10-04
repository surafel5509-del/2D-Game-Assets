// Android framework stubs — android.annotation (type-check only).
@file:Suppress("unused")

package android.annotation

@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE,
)
annotation class SuppressLint(vararg val value: String)

@Retention(AnnotationRetention.SOURCE)
annotation class TargetApi(val value: Int)
