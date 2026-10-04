# Android framework stubs (type-check only)

The Android modules of this repository cannot be *built* without an Android SDK, but they can be
*type-checked*: `tools/typecheck-android.sh` compiles `engine-android` and `app-android` with
`kotlinc` against the hand-written declarations in this folder.

The stubs are **not** shipped, not packaged in the APK, and never executed — they only cover the
framework surface the engine actually calls, so a typo, a wrong argument or a renamed member is
caught in CI instead of on a phone. The real Android SDK is used by the Gradle build
(`:engine-android`, `:app-android`) and by `.github/workflows/android.yml`.

Keep the declarations faithful to the platform:

* signatures mirror `android.jar` (parameter order, nullability as annotated in the SDK),
* `open`/`abstract` modifiers follow the real classes,
* no behaviour is implemented — bodies are `TODO()` or trivial constants.
