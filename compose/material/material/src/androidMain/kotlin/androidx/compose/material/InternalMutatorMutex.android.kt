package androidx.compose.material

import java.util.concurrent.atomic.AtomicReference


/*** This is an internal copy of androidx.compose.foundation.MutatorMutex with an additional
 * tryMutate method. Do not modify, except for tryMutate. ***/
@Suppress("ACTUAL_WITHOUT_EXPECT") // https://youtrack.jetbrains.com/issue/KT-37316
internal actual typealias AtomicReference<V> = AtomicReference<V>