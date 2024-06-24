package androidx.compose.ui

import java.util.concurrent.atomic.AtomicReference

// This should be kept internal by marking all actuals as internal. We can't mark the expect as
// internal since the typealias target on JVM is public, so the compiler complains about mismatched
// visibility.
@Suppress("ACTUAL_WITHOUT_EXPECT") // https://youtrack.jetbrains.com/issue/KT-37316
internal actual typealias AtomicReference<V> = AtomicReference<V>