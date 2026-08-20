# Podroid ProGuard rules

# TerminalView fields set directly from TerminalScreen to wire the session.
-keepclassmembers class com.termux.view.TerminalView {
    public com.termux.terminal.TerminalSession mTermSession;
    public com.termux.terminal.TerminalEmulator mEmulator;
}

# TerminalSession.mEmulator: read via the public getEmulator() to wire the
# emulator into TerminalView. The app no longer reflects on this field (the old
# DECSET-flag reflection was replaced by the public isCursorKeysApplicationMode
# / isFocusEventsEnabled accessors), so this keep rule is belt-and-suspenders
# against R8 stripping the field behind the getter.
-keepclassmembers class com.termux.terminal.TerminalSession {
    com.termux.terminal.TerminalEmulator mEmulator;
}

# java.net.UnixDomainSocketAddress (JDK 16) is present on Android API 34+ at
# runtime but absent from the compile-time SDK stubs. ConsoleFanout uses it
# only on AVF/API-34 devices and is guarded by @RequiresApi(34). Suppress the
# R8 missing-class error so release builds succeed.
-dontwarn java.net.UnixDomainSocketAddress

# androidx.security:security-crypto pulls in Google Tink, which is annotated
# with com.google.errorprone.annotations.*. Those annotations are compile-time
# only and are not on the runtime classpath, so R8 fails the release build
# with "Missing class com.google.errorprone.annotations.CanIgnoreReturnValue"
# and friends. They are annotations — nothing dereferences them at runtime —
# so suppressing the warning is the documented fix, not a workaround that
# hides a real missing dependency.
-dontwarn com.google.errorprone.annotations.**

# Tink loads key managers reflectively by class name; R8 cannot see those
# references and would strip them, which surfaces only at runtime as a
# GeneralSecurityException when the encrypted store is first opened.
-keep class com.google.crypto.tink.** { *; }

# Tink's KeysDownloader fetches keysets over HTTP and needs google-http-client
# plus joda-time. We never call it — DeadalusUnlock uses a local AES256-GCM key
# from the AndroidKeyStore — and neither library is a dependency, so R8 only
# needs to be told not to warn about the dead references.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
