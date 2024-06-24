package androidx.compose.runtime

import kotlinx.atomicfu.atomic
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("ACTUAL_WITHOUT_EXPECT") // https://youtrack.jetbrains.com/issue/KT-37316
internal actual typealias AtomicReference<V> = AtomicReference<V>
internal actual class AtomicInt actual constructor(value: Int) {
    private val delegate = atomic(value)
    actual fun get(): Int = delegate.value
    actual fun set(value: Int) {
        delegate.value = value
    }
    actual fun add(amount: Int): Int = delegate.addAndGet(amount)
    actual fun compareAndSet(expect: Int, newValue: Int) = delegate.compareAndSet(expect, newValue)
}
