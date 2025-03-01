/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.lifecycle

import androidx.kruth.assertWithMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class WithLifecycleStateTest {
    @Test
    fun testInitialResumed() = runLifecycleTest {
        val owner = FakeLifecycleOwner(Lifecycle.State.RESUMED)

        val expected = "initial value"
        var toRead = expected
        launch { toRead = "value set by launch" }
        val readByWithStarted = owner.withStarted { toRead }
        assertEquals(expected, readByWithStarted)
    }

    @Test
    fun testBlockRunsWithLifecycleStateChange() = runLifecycleTest {
        val owner = FakeLifecycleOwner()

        val initial = "initial value"
        val afterSetState = "value set after setState"
        var toRead = initial
        launch {
            owner.setState(Lifecycle.State.RESUMED)
            toRead = afterSetState
        }
        val readByWithStarted = owner.withStarted { toRead }
        val readAfterResumed = toRead
        assertEquals(initial, readByWithStarted)
        assertEquals(afterSetState, readAfterResumed)
    }

    @Test
    fun testBlockCancelledWhenInitiallyDestroyed() = runLifecycleTest {
        val owner = FakeLifecycleOwner(Lifecycle.State.CREATED)
        owner.setState(Lifecycle.State.DESTROYED)

        assertFailsWith<LifecycleDestroyedException> {
            owner.withStarted {}
        }
    }

    @Test
    fun testBlockCancelledWhenDestroyedWhileSuspended() = runLifecycleTest {
        val owner = FakeLifecycleOwner(Lifecycle.State.CREATED)

        var launched = false
        val resultTask = async {
            launched = true
            runCatching { owner.withStarted {} }
        }
        yield()

        assertWithMessage("test ran to first suspension after successfully launching")
            .that(launched)
            .isTrue()
        assertWithMessage("withStarted is still active")
            .that(resultTask.isActive)
            .isTrue()

        owner.setState(Lifecycle.State.DESTROYED)

        assertWithMessage("result threw LifecycleDestroyedException")
            .that(resultTask.await().exceptionOrNull())
            .isInstanceOf<LifecycleDestroyedException>()
    }
}
