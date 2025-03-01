/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.privacysandbox.ui.tests.endtoend

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.privacysandbox.ui.client.SandboxedUiAdapterFactory
import androidx.privacysandbox.ui.client.view.SandboxedSdkUiSessionState
import androidx.privacysandbox.ui.client.view.SandboxedSdkUiSessionStateChangedListener
import androidx.privacysandbox.ui.client.view.SandboxedSdkView
import androidx.privacysandbox.ui.core.BackwardCompatUtil
import androidx.privacysandbox.ui.core.SandboxedUiAdapter
import androidx.privacysandbox.ui.provider.toCoreLibInfo
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.testutils.withActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@MediumTest
// TODO(b/339384188): Simplify this file
class IntegrationTests(private val invokeBackwardsCompatFlow: Boolean) {

    @get:Rule
    var activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    companion object {
        const val TEST_ONLY_USE_REMOTE_ADAPTER = "testOnlyUseRemoteAdapter"
        const val TIMEOUT = 1000.toLong()
        const val INITIAL_HEIGHT = 100
        const val INITIAL_WIDTH = 100

        @JvmStatic
        @Parameterized.Parameters(name = "invokeBackwardsCompatFlow={0}")
        fun data(): Array<Any> = arrayOf(
            arrayOf(true),
            arrayOf(false),
        )
    }

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val sdkViewColor = Color.YELLOW

    private lateinit var view: SandboxedSdkView
    private lateinit var recyclerView: RecyclerView
    private lateinit var stateChangeListener: TestStateChangeListener
    private lateinit var activity: Activity
    private lateinit var errorLatch: CountDownLatch
    private lateinit var linearLayout: LinearLayout
    private lateinit var mInstrumentation: Instrumentation

    @Before
    fun setup() {
        if (!invokeBackwardsCompatFlow) {
            // Device needs to support remote provider to invoke non-backward-compat flow.
            assumeTrue(BackwardCompatUtil.canProviderBeRemote())
        }

        mInstrumentation = InstrumentationRegistry.getInstrumentation()

        activity = activityScenarioRule.withActivity { this }
        activityScenarioRule.withActivity {
            view = SandboxedSdkView(context)
            recyclerView = RecyclerView(context)
            errorLatch = CountDownLatch(1)
            stateChangeListener = TestStateChangeListener(errorLatch)
            view.addStateChangedListener(stateChangeListener)
            linearLayout = LinearLayout(context)
            linearLayout.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            linearLayout.setBackgroundColor(Color.RED)
            setContentView(linearLayout)
            view.layoutParams = LinearLayout.LayoutParams(INITIAL_WIDTH, INITIAL_HEIGHT)
            linearLayout.addView(view)
            linearLayout.addView(recyclerView)
            recyclerView.setBackgroundColor(Color.GREEN)
            recyclerView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            recyclerView.setLayoutManager(LinearLayoutManager(context))
        }
    }

    @Ignore // b/271299184
    @Test
    fun testChangingSandboxedSdkViewLayoutChangesChildLayout() {
        createAdapterAndEstablishSession()

        val layoutChangeLatch = CountDownLatch(1)
        val childAddedLatch = CountDownLatch(1)

        val hierarchyChangeListener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                childAddedLatch.countDown()
            }

            override fun onChildViewRemoved(p0: View?, p1: View?) {
            }
        }
        view.setOnHierarchyChangeListener(hierarchyChangeListener)

        val onLayoutChangeListener: OnLayoutChangeListener =
            object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    assertTrue(left == 10 && top == 10 && right == 10 && bottom == 10)
                    layoutChangeLatch.countDown()
                    view?.removeOnLayoutChangeListener(this)
                }
            }
        childAddedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
        assertTrue(childAddedLatch.count == 0.toLong())
        view.getChildAt(0).addOnLayoutChangeListener(onLayoutChangeListener)
        view.layout(10, 10, 10, 10)
        layoutChangeLatch.await(2000, TimeUnit.MILLISECONDS)
        assertTrue(layoutChangeLatch.count == 0.toLong())
        assertTrue(stateChangeListener.currentState == SandboxedSdkUiSessionState.Active)
    }

    @Test
    fun testOpenSession_onSetAdapter() {
        val adapter = createAdapterAndEstablishSession()
        assertThat(adapter.session).isNotNull()
    }

    @Test
    fun testOpenSession_fromAdapter() {
        val adapter = createAdapterAndEstablishSession(viewForSession = null)
        assertThat(adapter.session).isNotNull()
    }

    @Test
    fun testConfigurationChanged() {
        val sdkAdapter = createAdapterAndEstablishSession()

        activityScenarioRule.withActivity {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        assertWithMessage("Configuration changed").that(testSession.config?.orientation)
                .isEqualTo(Configuration.ORIENTATION_LANDSCAPE)
    }

    /**
     * Tests that the provider receives Z-order change updates.
     */
    @Test
    @Ignore("b/302090927")
    fun testZOrderChanged() {
        val adapter = createAdapterAndEstablishSession()

        view.orderProviderUiAboveClientUi(!adapter.initialZOrderOnTop)
        val testSession = adapter.session as TestSandboxedUiAdapter.TestSession
        assertThat(testSession.zOrderChanged).isTrue()
    }

    /**
     * Tests that the provider does not receive Z-order updates if the Z-order is unchanged.
     */
    @Test
    fun testZOrderUnchanged() {
        val adapter = createAdapterAndEstablishSession()

        view.orderProviderUiAboveClientUi(adapter.initialZOrderOnTop)
        val testSession = adapter.session as TestSandboxedUiAdapter.TestSession
        assertThat(testSession.zOrderChanged).isFalse()
    }

    @Test
    fun testHostCanSetZOrderAboveBeforeOpeningSession() {
        // TODO(b/301976432): Stop skipping this for backwards compat flow
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = createAdapterAndWaitToBeActive(initialZOrder = true)
        injectInputEventOnView()
        // the injected touch should be handled by the provider in Z-above mode
        assertThat(adapter.touchedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
    }

    @Test
    @Ignore("b/302006586")
    fun testHostCanSetZOrderBelowBeforeOpeningSession() {
        // TODO(b/300396631): Skip for backward compat
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = createAdapterAndWaitToBeActive(initialZOrder = false)
        injectInputEventOnView()
        // the injected touch should not reach the provider in Z-below mode
        assertThat(adapter.touchedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isFalse()
    }

    @Test
    fun testSessionError() {
        createAdapterAndEstablishSession(hasFailingTestSession = true)

        assertThat(errorLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(stateChangeListener.error?.message).isEqualTo("Test Session Exception")
    }

    /**
     * Tests that a provider-initiated resize is accepted if the view's parent does not impose
     * exact restrictions on the view's size.
     */
    @Test
    fun testResizeRequested_requestedAccepted_atMostMeasureSpec() {
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val sdkAdapter = createAdapterAndWaitToBeActive()

        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        val newWidth = INITIAL_WIDTH - 10
        val newHeight = INITIAL_HEIGHT - 10

        activityScenarioRule.withActivity {
            testSession.sessionClient.onResizeRequested(newWidth, newHeight)
        }
        assertWithMessage("Resized height").that(testSession.resizedWidth).isEqualTo(newWidth)
        assertWithMessage("Resized width").that(testSession.resizedHeight).isEqualTo(newHeight)
        testSession.assertResizeOccurred(
            /* expectedWidth=*/ newWidth,
            /* expectedHeight=*/ newHeight)
    }

    /**
     * Tests that a provider-initiated resize is ignored if the view's parent provides exact
     * measurements.
     */
    @Test
    fun testResizeRequested_requestIgnored_exactlyMeasureSpec() {
        view.layoutParams = LinearLayout.LayoutParams(INITIAL_WIDTH, INITIAL_HEIGHT)
        val sdkAdapter = createAdapterAndWaitToBeActive()
        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession

        activityScenarioRule.withActivity {
            testSession.sessionClient.onResizeRequested(INITIAL_WIDTH - 10, INITIAL_HEIGHT - 10)
        }
        testSession.assertResizeDidNotOccur()
    }

    @Test
    fun testResize_ClientInitiated() {
        val sdkAdapter = createAdapterAndWaitToBeActive()
        val newWidth = INITIAL_WIDTH - 10
        val newHeight = INITIAL_HEIGHT - 10
        activityScenarioRule.withActivity {
            view.layoutParams = LinearLayout.LayoutParams(newWidth, newHeight)
        }

        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        assertWithMessage("Resized width").that(testSession.resizedWidth)
            .isEqualTo(newWidth)
        assertWithMessage("Resized height").that(testSession.resizedHeight)
            .isEqualTo(newHeight)
        testSession.assertResizeOccurred(
            /* expectedWidth=*/ newWidth,
            /* expectedHeight=*/ newHeight)
    }

    @Test
    fun testSessionClientProxy_methodsOnObjectClass() {
        // Only makes sense when a dynamic proxy is involved in the flow
        assumeTrue(invokeBackwardsCompatFlow)

        val testSessionClient = TestSessionClient()
        val sdkAdapter = createAdapterAndEstablishSession(
            viewForSession = null,
            testSessionClient = testSessionClient
        )

        // Verify toString, hashCode and equals have been implemented for dynamic proxy
        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        val client = testSession.sessionClient
        assertThat(client.toString()).isEqualTo(testSessionClient.toString())

        assertThat(client.equals(client)).isTrue()
        assertThat(client).isNotEqualTo(testSessionClient)
        assertThat(client.hashCode()).isEqualTo(client.hashCode())
    }

    @Test
    fun testPoolingContainerListener_AllViewsRemovedFromContainer() {
        // TODO(b/309848703): Stop skipping this for backwards compat flow
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = createRecyclerViewTestAdapterAndWaitForChildrenToBeActive(
            isNestedView = false)

        activityScenarioRule.withActivity {
            recyclerView.layoutManager!!.removeAllViews()
        }

        adapter.waitForViewsToBeDetached()
        adapter.ensureChildrenDoNotBecomeIdleFromActive()
    }

    @Test
    fun testPoolingContainerListener_ContainerRemovedFromLayout() {
        // TODO(b/309848703): Stop skipping this for backwards compat flow
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = createRecyclerViewTestAdapterAndWaitForChildrenToBeActive(
            isNestedView = true)

        activityScenarioRule.withActivity {
            linearLayout.removeView(recyclerView)
        }

        adapter.ensureAllChildrenBecomeIdleFromActive()
    }

    @Test
    fun testPoolingContainerListener_ViewWithinAnotherView_AllViewsRemovedFromContainer() {
        // TODO(b/309848703): Stop skipping this for backwards compat flow
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = createRecyclerViewTestAdapterAndWaitForChildrenToBeActive(
            isNestedView = false)

        activityScenarioRule.withActivity {
            recyclerView.layoutManager!!.removeAllViews()
        }

        adapter.waitForViewsToBeDetached()
        adapter.ensureChildrenDoNotBecomeIdleFromActive()
    }

    @Test
    fun testPoolingContainerListener_ViewWithinAnotherView_ContainerRemovedFromLayout() {
        // TODO(b/309848703): Stop skipping this for backwards compat flow
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = createRecyclerViewTestAdapterAndWaitForChildrenToBeActive(
            isNestedView = true)

        activityScenarioRule.withActivity {
            linearLayout.removeView(recyclerView)
        }

        adapter.ensureAllChildrenBecomeIdleFromActive()
    }

    /**
     * Verifies that when the [View] returned as part of a [SandboxedUiAdapter.Session] is a
     * [ViewGroup], that the child view is measured and laid out by its parent.
     */
    @Test
    fun testViewGroup_ChildViewIsLaidOut() {
        val adapter = createAdapterAndWaitToBeActive(placeViewInsideFrameLayout = true)
        val session = adapter.session as TestSandboxedUiAdapter.TestSession

        // Force a layout pass by changing the size of the view
        activityScenarioRule.withActivity {
            session.sessionClient.onResizeRequested(INITIAL_WIDTH - 10, INITIAL_HEIGHT - 10)
        }
        session.assertViewWasLaidOut()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testPoolingContainerListener_NotifyFetchUiForSession() {
        // verifyColorOfScreenshot is only available for U+ devices.
        assumeTrue(SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // TODO(b/309848703): Stop skipping this for backwards compat flow
        assumeTrue(!invokeBackwardsCompatFlow)

        val recyclerViewAdapter = RecyclerViewTestAdapterForFetchingUi()

        activityScenarioRule.withActivity {
            recyclerView.setAdapter(recyclerViewAdapter)
        }

        recyclerViewAdapter
            .scrollSmoothlyToPosition(
                recyclerViewAdapter.itemCount - 1)
        recyclerViewAdapter.ensureAllChildrenBecomeActive()
        recyclerViewAdapter.scrollSmoothlyToPosition(0)
        val displayMetrics = activity.resources.displayMetrics
        // We don't need to check all the pixels since we only care that at least some of
        // them are equal to sdkViewColor. The smaller rectangle that we will be checking
        // of size 10*10. This will make the test run faster.
        val midPixelLocation =
            Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels) / 2
        assertThat(
            verifyColorOfScreenshot(
                midPixelLocation, midPixelLocation,
                midPixelLocation + 10, midPixelLocation + 10, sdkViewColor
            )
        ).isTrue()
    }

    fun createRecyclerViewTestAdapterAndWaitForChildrenToBeActive(isNestedView: Boolean):
        RecyclerViewTestAdapter {
        val recyclerViewAdapter = RecyclerViewTestAdapter(context, isNestedView)
        activityScenarioRule.withActivity {
            recyclerView.setAdapter(recyclerViewAdapter)
        }

        recyclerViewAdapter.waitForViewsToBeAttached()

        for (i in 0 until recyclerView.childCount) {
            lateinit var childView: SandboxedSdkView
            if (isNestedView) {
                childView = (recyclerView.getChildAt(i) as ViewGroup)
                    .getChildAt(0) as SandboxedSdkView
            } else {
                childView = recyclerView.getChildAt(i) as SandboxedSdkView
            }
            createAdapterAndWaitToBeActive(true, childView)
        }

        recyclerViewAdapter.ensureAllChildrenBecomeActive()
        return recyclerViewAdapter
    }

    // TODO(b/339404828): Remove inner keyword and make this private class
    inner class RecyclerViewTestAdapterForFetchingUi :
        RecyclerView.Adapter<RecyclerViewTestAdapterForFetchingUi.ViewHolder>() {

        private val sandboxedSdkViewSet = mutableSetOf<SandboxedSdkView>()
        private val itemCount = 5
        private val activeLatch = CountDownLatch(itemCount)

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val sandboxedSdkView: SandboxedSdkView = (view as LinearLayout)
                .getChildAt(0) as SandboxedSdkView
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            val view = LinearLayout(context)
            val childSandboxedSdkView = SandboxedSdkView(context)
            view.addView(childSandboxedSdkView)
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            layoutParams.setMargins(20, 20, 20, 20)
            view.layoutParams = layoutParams
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
            val childSandboxedSdkView = viewHolder.sandboxedSdkView

            if (!sandboxedSdkViewSet.contains(childSandboxedSdkView)) {
                val adapter = TestSandboxedUiAdapter()

                childSandboxedSdkView.addStateChangedListener { state ->
                    if (state is SandboxedSdkUiSessionState.Active) {
                        activeLatch.countDown()
                    }
                }

                val adapterFromCoreLibInfo = SandboxedUiAdapterFactory.createFromCoreLibInfo(
                    getCoreLibInfoFromAdapter(adapter)
                )

                childSandboxedSdkView.setAdapter(adapterFromCoreLibInfo)
                sandboxedSdkViewSet.add(childSandboxedSdkView)
            }
        }

        fun scrollSmoothlyToPosition(position: Int) {
            activityScenarioRule.withActivity {
                recyclerView.smoothScrollToPosition(position)
            }
        }

        fun ensureAllChildrenBecomeActive() {
            assertThat(activeLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        }

        override fun getItemCount(): Int = itemCount
    }

    class RecyclerViewTestAdapter(
        private val context: Context,
        val isNestedView: Boolean = false,
    ) :
        RecyclerView.Adapter<RecyclerViewTestAdapter.ViewHolder>() {
        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

        var numberOfSandboxedSdkViews = 0
        val items = 5
        private val activeLatch = CountDownLatch(items)

        // The session will first be idle -> active -> idle in
        // our tests, hence the count is items*2
        private val idleLatch = CountDownLatch(items * 2)
        private val attachedLatch = CountDownLatch(items)
        private val detachedLatch = CountDownLatch(items)
        val onAttachStateChangeListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachedLatch.countDown()
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (attachedLatch.count.equals(0.toLong())) {
                    detachedLatch.countDown()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            if (numberOfSandboxedSdkViews >= items) {
                // We should return without creating a SandboxedSdkView if the
                // number of SandboxedSdkViews is already equal to items. Recycler
                // view will create new ViewHolders once SandboxedSdkViews are
                // removed. We do not want to count latch down at that point of time.
                return ViewHolder(View(context))
            }

            val listener = SandboxedSdkUiSessionStateChangedListener { state ->
                if (state is SandboxedSdkUiSessionState.Active) {
                    activeLatch.countDown()
                } else if (state is SandboxedSdkUiSessionState.Idle) {
                    idleLatch.countDown()
                }
            }

            numberOfSandboxedSdkViews++
            var view: View = SandboxedSdkView(context)
            (view as SandboxedSdkView).addStateChangedListener(listener)
            if (isNestedView) {
                val parentView = LinearLayout(context)
                parentView.addView(view)
                view = parentView
            }
            view.layoutParams =
                RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            view.addOnAttachStateChangeListener(onAttachStateChangeListener)
            return ViewHolder(view)
        }

        fun waitForViewsToBeAttached() {
            assertThat(attachedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        }

        fun waitForViewsToBeDetached() {
            assertThat(detachedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        }

        fun ensureAllChildrenBecomeActive() {
            assertThat(activeLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        }

        fun ensureAllChildrenBecomeIdleFromActive() {
            assertThat(idleLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        }

        fun ensureChildrenDoNotBecomeIdleFromActive() {
            assertThat(idleLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(idleLatch.count).isEqualTo(items)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        }

        override fun getItemCount(): Int = items
    }

    private fun getCoreLibInfoFromAdapter(sdkAdapter: SandboxedUiAdapter): Bundle {
        val bundle = sdkAdapter.toCoreLibInfo(context)
        bundle.putBoolean(TEST_ONLY_USE_REMOTE_ADAPTER, !invokeBackwardsCompatFlow)
        return bundle
    }

    /**
     * Creates a [TestSandboxedUiAdapter] and establishes session.
     *
     * If [view] is null, then session is opened using the adapter directly. Otherwise, the
     * created adapter is set on [view] to establish session.
     */
    private fun createAdapterAndEstablishSession(
        hasFailingTestSession: Boolean = false,
        placeViewInsideFrameLayout: Boolean = false,
        viewForSession: SandboxedSdkView? = view,
        testSessionClient: TestSessionClient = TestSessionClient()
    ): TestSandboxedUiAdapter {

        val adapter = TestSandboxedUiAdapter(hasFailingTestSession, placeViewInsideFrameLayout)
        val adapterFromCoreLibInfo = SandboxedUiAdapterFactory.createFromCoreLibInfo(
            getCoreLibInfoFromAdapter(adapter)
        )
        if (viewForSession != null) {
            viewForSession.setAdapter(adapterFromCoreLibInfo)
        } else {
            adapterFromCoreLibInfo.openSession(
                context,
                windowInputToken = Binder(),
                INITIAL_WIDTH,
                INITIAL_HEIGHT,
                isZOrderOnTop = true,
                clientExecutor = Runnable::run,
                testSessionClient
            )
        }

        assertWithMessage("openSession is called on adapter")
            .that(adapter.isOpenSessionCalled).isTrue()
        if (viewForSession == null) {
            assertWithMessage("onSessionOpened received by SessionClient")
                .that(testSessionClient.isSessionOpened).isTrue()
        }
        return adapter
    }

    private fun createAdapterAndWaitToBeActive(
        initialZOrder: Boolean = true,
        viewForSession: SandboxedSdkView = view,
        placeViewInsideFrameLayout: Boolean = false
    ):
        TestSandboxedUiAdapter {
        viewForSession.orderProviderUiAboveClientUi(initialZOrder)

        val adapter = createAdapterAndEstablishSession(
            placeViewInsideFrameLayout = placeViewInsideFrameLayout,
            viewForSession = viewForSession
        )

        val activeLatch = CountDownLatch(1)
        viewForSession.addStateChangedListener { state ->
            if (state is SandboxedSdkUiSessionState.Active) {
                activeLatch.countDown()
            }
        }
        assertThat(activeLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        return adapter
    }

    private fun injectInputEventOnView() {
        activityScenarioRule.withActivity {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(
                MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN,
                    (location[0] + 1).toFloat(),
                    (location[1] + 1).toFloat(),
                    0
                ), false
            )
        }
    }

    class TestStateChangeListener(private val errorLatch: CountDownLatch) :
        SandboxedSdkUiSessionStateChangedListener {
        var currentState: SandboxedSdkUiSessionState? = null
        var error: Throwable? = null

        override fun onStateChanged(state: SandboxedSdkUiSessionState) {
            currentState = state
            if (state is SandboxedSdkUiSessionState.Error) {
                error = state.throwable
                errorLatch.countDown()
            }
        }
    }

    /**
     *  TestSandboxedUiAdapter provides content from a fake SDK to show on the host's UI.
     *
     *  A [SandboxedUiAdapter] is supposed to fetch the content from SandboxedSdk, but we fake the
     *  source of content in this class.
     *
     *  If [hasFailingTestSession] is true, the fake server side logic returns error.
     */
    inner class TestSandboxedUiAdapter(
        private val hasFailingTestSession: Boolean = false,
        private val placeViewInsideFrameLayout: Boolean = false
    ) : SandboxedUiAdapter {

        private val openSessionLatch: CountDownLatch = CountDownLatch(1)

        val isOpenSessionCalled: Boolean
            get() = openSessionLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)

        var initialZOrderOnTop = false
        var touchedLatch = CountDownLatch(1)

        lateinit var session: SandboxedUiAdapter.Session
        var initialHeight: Int = -1
        var initialWidth: Int = -1

        override fun openSession(
            context: Context,
            windowInputToken: IBinder,
            initialWidth: Int,
            initialHeight: Int,
            isZOrderOnTop: Boolean,
            clientExecutor: Executor,
            client: SandboxedUiAdapter.SessionClient
        ) {
            initialZOrderOnTop = isZOrderOnTop
            this.initialHeight = initialHeight
            this.initialWidth = initialWidth
            session = if (hasFailingTestSession) {
                FailingTestSession(context, client)
            } else {
                TestSession(context, client, placeViewInsideFrameLayout)
            }
            client.onSessionOpened(session)
            openSessionLatch.countDown()
        }

        /**
         * A failing session that always sends error notice to the client when content is requested.
         */
        inner class FailingTestSession(
            private val context: Context,
            private val sessionClient: SandboxedUiAdapter.SessionClient
        ) : SandboxedUiAdapter.Session {
            override val view: View
                get() {
                    sessionClient.onSessionError(Throwable("Test Session Exception"))
                    return View(context)
                }

            override fun notifyResized(width: Int, height: Int) {
            }

            override fun notifyZOrderChanged(isZOrderOnTop: Boolean) {
            }

            override fun notifyConfigurationChanged(configuration: Configuration) {
            }

            override fun close() {
            }
        }

        inner class TestSession(
            private val context: Context,
            val sessionClient: SandboxedUiAdapter.SessionClient,
            private val placeViewInsideFrameLayout: Boolean = false
        ) : SandboxedUiAdapter.Session {

            private val configLatch = CountDownLatch(1)
            private val resizeLatch = CountDownLatch(1)
            private val zOrderLatch = CountDownLatch(1)
            private val sizeChangedLatch = CountDownLatch(1)
            private val layoutLatch = CountDownLatch(1)
            private var width = -1
            private var height = -1

            var config: Configuration? = null
                get() {
                    configLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    return field
                }

            var zOrderChanged = false
                get() {
                    zOrderLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    return field
                }

            var resizedWidth = 0
                get() {
                    resizeLatch.await(TIMEOUT * 2, TimeUnit.MILLISECONDS)
                    return field
                }

            var resizedHeight = 0
                get() {
                    resizeLatch.await(TIMEOUT * 2, TimeUnit.MILLISECONDS)
                    return field
                }

            inner class TestView(context: Context) : View(context) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    canvas.drawColor(sdkViewColor)
                }
            }

            private val testView: View = TestView(context).also {
                it.setOnTouchListener { _, _ ->
                    touchedLatch.countDown()
                    true
                }
                it.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    width = right - left
                    height = bottom - top
                    // Don't count down for the initial layout. We want to capture the
                    // layout change for a size change.
                    if (width != initialWidth || height != initialHeight) {
                        sizeChangedLatch.countDown()
                    }
                    layoutLatch.countDown()
                }
            }

            override val view: View
                get() {
                    return if (placeViewInsideFrameLayout) {
                        FrameLayout(context).also {
                            it.addView(testView)
                        }
                    } else {
                        testView
                    }
                }

            override fun notifyResized(width: Int, height: Int) {
                resizedWidth = width
                resizedHeight = height
                resizeLatch.countDown()
            }

            override fun notifyZOrderChanged(isZOrderOnTop: Boolean) {
                zOrderChanged = true
                zOrderLatch.countDown()
            }

            override fun notifyConfigurationChanged(configuration: Configuration) {
                config = configuration
                configLatch.countDown()
            }

            override fun close() {
            }

            internal fun assertResizeOccurred(expectedWidth: Int, expectedHeight: Int) {
                assertThat(sizeChangedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
                assertThat(width).isEqualTo(expectedWidth)
                assertThat(height).isEqualTo(expectedHeight)
            }

            internal fun assertResizeDidNotOccur() {
                assertThat(sizeChangedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isFalse()
            }

            internal fun assertViewWasLaidOut() {
                assertThat(layoutLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
            }
        }
    }

    class TestSessionClient : SandboxedUiAdapter.SessionClient {
        private val sessionOpenedLatch = CountDownLatch(1)
        private val resizeRequestedLatch = CountDownLatch(1)

        var session: SandboxedUiAdapter.Session? = null
            get() {
                sessionOpenedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                return field
            }

        val isSessionOpened: Boolean
            get() = sessionOpenedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)

        var resizedWidth = 0
            get() {
                resizeRequestedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                return field
            }

        var resizedHeight = 0
            get() {
                resizeRequestedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                return field
            }

        override fun onSessionOpened(session: SandboxedUiAdapter.Session) {
            this.session = session
            sessionOpenedLatch.countDown()
        }

        override fun onSessionError(throwable: Throwable) {
        }

        override fun onResizeRequested(width: Int, height: Int) {
            resizedWidth = width
            resizedHeight = height
            resizeRequestedLatch.countDown()
        }
    }

    // This logic is similar to the one used in BitmapPixelChecker class under cts tests
    // If session is created from another process we should make changes to the test to
    // make this logic work.
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun verifyColorOfScreenshot(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        requiredColor: Int
    ): Boolean {
        val screenshot = mInstrumentation.uiAutomation.takeScreenshot(
            activity.window
        )
        assertNotNull("Failed to generate a screenshot", screenshot)

        val swBitmap = screenshot!!.copy(Bitmap.Config.ARGB_8888, false)
        screenshot.recycle()
        val bounds = Rect(left, top, right, bottom)
        val rectangleArea = (bottom - top) * (right - left)
        var numMatchingPixels: Int = getNumMatchingPixels(swBitmap, bounds, requiredColor)

        swBitmap.recycle()

        return numMatchingPixels == rectangleArea
    }

    // This logic is from the method named AreSame in AlmostPerfectMatcher class under
    // platform_testing. Depending on the hardware used, the colors in pixel look similar
    // to assigned color to the naked eye but their value can be slightly different from
    // the assigned value. This method takes care to verify whether both the assigned and
    // the actual value of the color are almost same.
    // ref
    // R. F. Witzel, R. W. Burnham, and J. W. Onley. Threshold and suprathreshold perceptual color
    // differences. J. Optical Society of America, 63:615{625, 1973. 14
    // TODO(b/339201299): Replace with original implementation
    private fun areAlmostSameColors(referenceColor: Int, testColor: Int): Boolean {
        val green = Color.green(referenceColor) - Color.green(testColor)
        val blue = Color.blue(referenceColor) - Color.blue(testColor)
        val red = Color.red(referenceColor) - Color.red(testColor)
        val redMean = (Color.red(referenceColor) + Color.red(testColor)) / 2
        val redScalar = if (redMean < 128) 2 else 3
        val blueScalar = if (redMean < 128) 3 else 2
        val greenScalar = 4
        val correction =
            (redScalar * red * red) + (greenScalar * green * green) + (blueScalar * blue * blue)
        val thresholdSq = 3 * 3
        // 1.5 no difference
        // 3.0 observable by experienced human observer
        // 6.0 minimal difference
        // 12.0 perceivable difference
        return correction <= thresholdSq
    }

    private fun getNumMatchingPixels(bitmap: Bitmap, bounds: Rect, requiredColor: Int): Int {
        var numMatchingPixels = 0

        for (x in bounds.left until bounds.right) {
            for (y in bounds.top until bounds.bottom) {
                val color = bitmap.getPixel(x, y)
                if (areAlmostSameColors(color, requiredColor)) {
                    numMatchingPixels++
                }
            }
        }

        return numMatchingPixels
    }
}
