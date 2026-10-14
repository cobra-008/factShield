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

private const val TAG              = "XFactService"
private const val TARGET_PKG       = "com.twitter.android"

/**
 * How long to wait after the last scroll event before triggering analysis.
 * 1.8s feels snappy; 2.2s was too slow.
 */
private const val SCROLL_DEBOUNCE_MS   = 1800L

/**
 * Number of scroll events while SCANNING that indicate the user has moved on.
 * Reduced from 5 → 2: any intentional scroll away fires just 2+ events.
 */
private const val SCROLL_ABORT_EVENTS  = 2

private const val PREFS_NAME      = "factshield_prefs"
private const val KEY_DEMO_MODE   = "demo_everywhere"

class XFactAccessibilityService : AccessibilityService() {

    // ── WindowManager overlay ────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var lifecycleOwner: OverlayLifecycleOwner
    private var overlayAttached = false

    // ── State (driven into Compose via mutableState) ─────────
    private var ballState  = mutableStateOf(BallState.IDLE)
    private var factResult = mutableStateOf<FactCheckResult?>(null)

    // ── Coroutine scope ──────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Scroll debounce ──────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrollDebounceRunnable: Runnable? = null

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

        // Never react to our own overlay events
        if (pkg == applicationContext.packageName) return

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Ignore transient system packages (SystemUI, keyboards, edge panels) that fire
                // while the user is still in X — they must not hide the overlay.
                if (isTransientSystemPackage(pkg)) return
                currentPackage = pkg
                updateOverlayVisibility(pkg)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Restore overlay visibility if it was accidentally hidden while X is foreground
                if (overlayAttached && shouldBeVisible(currentPackage)
                    && overlayView.visibility != View.VISIBLE) {
                    mainHandler.post { overlayView.visibility = View.VISIBLE }
                }

                // Only act on scroll events when X (or demo mode) is in the foreground
                if (!shouldBeVisible(currentPackage)) return

                when {
                    // ── User is actively loading: each scroll event counts toward abort ──
                    ballState.value == BallState.SCANNING -> {
                        scrollEventsDuringLoad++
                        if (scrollEventsDuringLoad >= SCROLL_ABORT_EVENTS) {
                            abortAndGoIdle()
                        }
                    }

                    // ── Result is displayed: clear it immediately so stale result isn't shown
                    //    on the next post. Go IDLE now, then schedule fresh analysis.
                    ballState.value != BallState.IDLE -> {
                        mainHandler.post {
                            factResult.value = null
                            ballState.value = BallState.IDLE
                        }
                        rescheduleScrollDebounce()
                    }

                    // ── Already IDLE: just schedule analysis after scroll settles ──
                    else -> {
                        rescheduleScrollDebounce()
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // no-op: scroll debounce handles analysis triggering
            }
        }
    }

    /**
     * Returns true for packages that emit WINDOW_STATE_CHANGED while the user
     * is actively inside X — these must not trigger the hide logic.
     */
    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        if (pkg == "android")              return true
        if (pkg == "com.android.launcher3") return true
        if (pkg.endsWith(".inputmethod"))  return true
        if (pkg.endsWith(".keyboard"))     return true
        // Common keyboard packages
        if (pkg == "com.google.android.inputmethod.latin") return true
        if (pkg == "com.samsung.android.honeyboard")       return true
        if (pkg == "com.swiftkey.swiftkeyapp")             return true
        return false
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
                val state  by ballState
                val result by factResult

                FactShieldOverlay(
                    state      = state,
                    result     = result,
                    onTap      = { triggerManualAnalysis() },
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

        // Initially hidden until X is detected in foreground
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
        mainHandler.post {
            overlayView.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) {
                // Cancel any pending work when leaving X
                cancelScrollDebounce()
                analysisJob?.cancel()
                ballState.value  = BallState.IDLE
                factResult.value = null
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  Scroll Debounce → Auto Trigger
    // ────────────────────────────────────────────────────────

    /**
     * Cancel the previous debounce runnable and post a new one.
     * Only runs when the ball is in a non-scanning terminal state (IDLE / result shown).
     */
    private fun rescheduleScrollDebounce() {
        cancelScrollDebounce()
        scrollDebounceRunnable = Runnable {
            val s = ballState.value
            // Do not auto-trigger if somehow SCANNING already (race guard)
            if (s != BallState.SCANNING) {
                triggerManualAnalysis()
            }
        }
        mainHandler.postDelayed(scrollDebounceRunnable!!, SCROLL_DEBOUNCE_MS)
    }

    private fun cancelScrollDebounce() {
        scrollDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        scrollDebounceRunnable = null
    }

    /**
     * User scrolled away mid-analysis → cancel everything, go IDLE immediately.
     * The next scroll-stop will re-trigger analysis for the new post.
     */
    private fun abortAndGoIdle() {
        cancelScrollDebounce()
        analysisJob?.cancel()
        analysisJob = null
        scrollEventsDuringLoad = 0
        mainHandler.post { ballState.value = BallState.IDLE }
        Log.d(TAG, "Analysis aborted — user scrolled away")
    }

    // ────────────────────────────────────────────────────────
    //  Analysis Pipeline
    // ────────────────────────────────────────────────────────

    fun triggerManualAnalysis() {
        // Ignore if already scanning
        if (ballState.value == BallState.SCANNING) return

        // Cancel any pending debounce so we don't double-trigger
        cancelScrollDebounce()

        ballState.value = BallState.SCANNING
        scrollEventsDuringLoad = 0

        analysisJob = serviceScope.launch {
            try {
                // 1. Scrape tweet text from accessibility tree
                val tweetText = withContext(Dispatchers.Default) { scrapeTweetText() }

                // 2. Take screenshot (API 30+)
                val screenshot = withContext(Dispatchers.IO) { captureScreenshot() }

                // 3. Abort early if the user scrolled away while we were collecting data
                if (!isActive || ballState.value != BallState.SCANNING) return@launch

                // 4. Call Groq (text-first; vision if image-only)
                val result = FactCheckRepository.analyze(tweetText, screenshot)

                // 5. Update UI only if still scanning (not aborted by scroll)
                if (isActive && ballState.value == BallState.SCANNING) {
                    factResult.value = result
                    ballState.value = when (result.status) {
                        FactStatus.GREEN  -> BallState.GREEN
                        FactStatus.YELLOW -> BallState.YELLOW
                        FactStatus.RED    -> BallState.RED
                    }
                }
            } catch (e: CancellationException) {
                // Normal — user scrolled away; abortAndGoIdle already set IDLE
                Log.d(TAG, "Analysis job cancelled (user scrolled away)")
            } catch (e: Exception) {
                Log.e(TAG, "Analysis pipeline exception: ${e.message}", e)
                if (isActive && ballState.value == BallState.SCANNING) {
                    // Show a YELLOW result instead of a bare "Analysis failed" toast
                    factResult.value = FactCheckResult(
                        status       = FactStatus.YELLOW,
                        confidence   = 0,
                        shortVerdict = "Could not complete analysis. Tap to retry.",
                        sources      = emptyList()
                    )
                    ballState.value = BallState.YELLOW
                }
            } finally {
                scrollEventsDuringLoad = 0
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  AccessibilityNodeInfo Text Scraper
    // ────────────────────────────────────────────────────────

    /**
     * Scrapes the SINGLE best tweet text from the accessibility tree.
     *
     * Strategy: collect all text nodes, then return the LONGEST single item
     * that looks like tweet content (40–600 chars). Joining multiple nodes
     * was causing previous-post text to contaminate current-post analysis
     * because the accessibility tree contains ALL visible posts simultaneously.
     */
    private fun scrapeTweetText(): String {
        val root = rootInActiveWindow ?: return ""
        val raw  = mutableListOf<String>()
        collectTweetText(root, raw)

        // Pick the single longest text block in the "tweet body" length range.
        // UI labels (Reply, Retweet, Like, usernames) are short; pane descriptions are very long.
        // The main tweet body is almost always the longest item in 40–600 chars range.
        val best = raw
            .filter { it.length in 40..600 }
            .distinctBy { it.trim().lowercase() }
            .maxByOrNull { it.length }          // longest = most likely to be tweet body

        if (best != null) {
            Log.d(TAG, "scrapeTweetText: best=${best.take(80)}")
            return best.take(1000)
        }

        // Fallback: if nothing in ideal range, take anything >= 15 chars, longest first
        return raw
            .filter { it.length >= 15 }
            .distinctBy { it.trim().lowercase() }
            .maxByOrNull { it.length }
            ?.take(1000) ?: ""
    }

    /**
     * Recursively walks the accessibility tree and collects meaningful text.
     * Stops early if we have enough raw candidates for speed.
     */
    private fun collectTweetText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (out.size >= 12) return // enough candidates — stop traversing

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) out.add(text)

        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc != text) out.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTweetText(child, out)
            child.recycle()  // recycle child nodes to avoid memory leaks
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
                                hardwareBitmap?.recycle()
                                hardwareBuffer.close()
                                cont.resume(softBitmap)
                            } catch (e: Exception) {
                                hardwareBuffer.close()
                                Log.w(TAG, "Bitmap conversion failed: ${e.message}")
                                cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed, errorCode=$errorCode — text-only mode")
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

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
