package com.factshield.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*

private const val TAG = "XFactService"
private const val TARGET_PKG = "com.twitter.android"
private const val SCROLL_DEBOUNCE_MS    = 2200L   // wait 2.2s after scrolling stops
private const val SCROLL_ABORT_EVENTS   = 5        // events during loading = user scrolled away
private const val PREFS_NAME = "factshield_prefs"
private const val KEY_DEMO_MODE = "demo_everywhere"

class XFactAccessibilityService : AccessibilityService() {

    // ── WindowManager overlay ────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var lifecycleOwner: OverlayLifecycleOwner
    private var overlayAttached = false

    // ── State (driven into Compose via mutableState) ─────────
    private var ballState = mutableStateOf(BallState.IDLE)
    private var factResult = mutableStateOf<FactCheckResult?>(null)

    // ── Coroutine scope ──────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Scroll debounce ──────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var scrollDebounceJob: Runnable? = null

    // ── Active analysis job (cancellable) ────────────────────
    private var analysisJob: Job? = null

    // ── Scroll-during-loading counter ────────────────────────
    private var scrollEventsDuringLoad = 0

    // ── Prefs ────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences

    // ── Active package tracking ──────────────────────────────
    private var currentPackage: String = ""

    // ────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onDestroy() {
        teardownOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────
    //  Accessibility Events
    // ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own overlay events
        if (pkg == applicationContext.packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Ignore transient system packages that fire while the user is still in X
                // (status bar, keyboard, edge effects, etc.) to prevent the overlay from
                // being hidden mid-scroll.
                if (isTransientSystemPackage(pkg)) return
                currentPackage = pkg
                updateOverlayVisibility(pkg)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Restore visibility if overlay was accidentally hidden while X is foreground
                val activeVisible = overlayAttached && shouldBeVisible(currentPackage)
                if (activeVisible && overlayView.visibility != android.view.View.VISIBLE) {
                    Handler(Looper.getMainLooper()).post {
                        overlayView.visibility = android.view.View.VISIBLE
                    }
                }
                if (!shouldBeVisible(pkg) && !shouldBeVisible(currentPackage)) return
                if (ballState.value == BallState.SCANNING) {
                    // User scrolled WHILE loading — count events to detect scroll-away
                    scrollEventsDuringLoad++
                    if (scrollEventsDuringLoad >= SCROLL_ABORT_EVENTS) {
                        abortAnalysis()
                    }
                } else {
                    triggerScrollDebounce()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // no-op for now; scroll debounce handles analysis
            }
        }
    }

    /**
     * Returns true for packages that produce transient WINDOW_STATE_CHANGED events
     * while the user is actually still viewing X — system UI, keyboards, edge effects, etc.
     * These must NOT trigger the visibility check so the overlay doesn't disappear.
     */
    private fun isTransientSystemPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
               pkg == "android" ||
               pkg == "com.android.launcher3" ||
               pkg == "com.google.android.inputmethod.latin" ||
               pkg == "com.samsung.android.honeyboard" ||
               pkg == "com.swiftkey.swiftkeyapp" ||
               pkg.endsWith(".inputmethod") ||
               pkg.endsWith(".keyboard")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    // ────────────────────────────────────────────────────────
    //  Overlay Setup
    // ────────────────────────────────────────────────────────

    private fun setupOverlay() {
        lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.onCreate()

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                val state by ballState
                val result by factResult

                FactShieldOverlay(
                    state = state,
                    result = result,
                    onTap = { triggerManualAnalysis() },
                    onLongPress = { triggerManualAnalysis() }
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(24)
            y = dpToPx(120)
        }

        windowManager.addView(overlayView, params)
        overlayAttached = true
        lifecycleOwner.onResume()

        // Initially hidden until we detect X is in foreground
        overlayView.visibility = View.GONE
    }

    private fun teardownOverlay() {
        if (overlayAttached) {
            lifecycleOwner.onDestroy()
            windowManager.removeView(overlayView)
            overlayAttached = false
        }
    }

    // ────────────────────────────────────────────────────────
    //  Visibility Control
    // ────────────────────────────────────────────────────────

    private fun shouldBeVisible(pkg: String): Boolean {
        val demoMode = prefs.getBoolean(KEY_DEMO_MODE, false)
        return demoMode || pkg == TARGET_PKG
    }

    private fun updateOverlayVisibility(pkg: String) {
        if (!overlayAttached) return
        val visible = shouldBeVisible(pkg)
        Handler(Looper.getMainLooper()).post {
            overlayView.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) {
                ballState.value = BallState.IDLE
                factResult.value = null
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  Scroll Debounce → Auto Trigger
    // ────────────────────────────────────────────────────────

    private fun triggerScrollDebounce() {
        scrollDebounceJob?.let { handler.removeCallbacks(it) }
        scrollDebounceJob = Runnable {
            val s = ballState.value
            if (s == BallState.IDLE || s == BallState.GREEN
                || s == BallState.YELLOW || s == BallState.RED) {
                triggerManualAnalysis()
            }
        }
        handler.postDelayed(scrollDebounceJob!!, SCROLL_DEBOUNCE_MS)
    }

    /** Cancel active analysis and return to IDLE (user scrolled away). */
    private fun abortAnalysis() {
        scrollDebounceJob?.let { handler.removeCallbacks(it) }
        analysisJob?.cancel()
        analysisJob = null
        scrollEventsDuringLoad = 0
        ballState.value = BallState.IDLE
        Log.d(TAG, "Analysis aborted — user scrolled away")
    }

    // ────────────────────────────────────────────────────────
    //  Analysis Pipeline
    // ────────────────────────────────────────────────────────

    fun triggerManualAnalysis() {
        if (ballState.value == BallState.SCANNING) return
        ballState.value = BallState.SCANNING
        scrollEventsDuringLoad = 0

        analysisJob = serviceScope.launch {
            try {
                // 1. Scrape tweet text from accessibility tree
                val tweetText = withContext(Dispatchers.Default) { scrapeTweetText() }

                // 2. Take screenshot (API 30+), graceful fallback if unavailable
                val screenshot = withContext(Dispatchers.IO) { captureScreenshot() }

                // Check if still relevant (user might have scrolled away)
                if (!isActive) return@launch

                // 3. Call Groq (text + vision)
                val result = FactCheckRepository.analyze(tweetText, screenshot)

                // 4. Update UI only if still scanning (not aborted)
                if (ballState.value == BallState.SCANNING) {
                    factResult.value = result
                    ballState.value = when (result.status) {
                        FactStatus.GREEN  -> BallState.GREEN
                        FactStatus.YELLOW -> BallState.YELLOW
                        FactStatus.RED    -> BallState.RED
                    }
                }
            } catch (e: CancellationException) {
                // Normal — user scrolled away; already set to IDLE in abortAnalysis()
                Log.d(TAG, "Analysis job cancelled")
            } catch (e: Exception) {
                if (ballState.value == BallState.SCANNING) {
                    Log.e(TAG, "Analysis failed: ${e.message}", e)
                    ballState.value = BallState.YELLOW
                    factResult.value = FactCheckResult(
                        status = FactStatus.YELLOW,
                        confidence = 0,
                        shortVerdict = "Analysis failed. Check your connection.",
                        sources = listOf("FactShield")
                    )
                }
            } finally {
                scrollEventsDuringLoad = 0
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  AccessibilityNodeInfo Text Scraper
    // ────────────────────────────────────────────────────────

    private fun scrapeTweetText(): String {
        val root = rootInActiveWindow ?: return ""
        val collected = mutableListOf<String>()
        collectText(root, collected)

        // Filter short noise (buttons, timestamps, handles) — keep >= 25 chars
        return collected
            .filter { it.length >= 25 }
            .distinct()
            .take(5)
            .joinToString(" | ")
            .take(1500) // stay within token budget
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) out.add(text)

        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc != text) out.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
        }
    }

    // ────────────────────────────────────────────────────────
    //  Screenshot (API 30 takeScreenshot)
    // ────────────────────────────────────────────────────────

    private suspend fun captureScreenshot(): Bitmap? {
        return try {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val hardwareBuffer = result.hardwareBuffer
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                                val softBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                cont.resume(softBitmap)
                            } catch (e: Exception) {
                                hardwareBuffer.close()
                                Log.w(TAG, "Bitmap conversion failed: ${e.message}")
                                cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed, errorCode=$errorCode — degrading to text-only")
                            cont.resume(null)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureScreenshot exception: ${e.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────
    //  Utility
    // ────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
