package androidx.compose.foundation

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Suppress("ACTUAL_WITHOUT_EXPECT") // https://youtrack.jetbrains.com/issue/KT-37316
actual typealias AtomicReference<V> = AtomicReference<V>

@Suppress("ACTUAL_WITHOUT_EXPECT") // https://youtrack.jetbrains.com/issue/KT-37316
actual typealias AtomicLong = AtomicLong