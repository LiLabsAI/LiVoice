package com.aliahad.wovoice.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliahad.wovoice.R
import com.aliahad.wovoice.account.AccountResult
import com.aliahad.wovoice.account.AccountState
import com.aliahad.wovoice.account.DeviceSession
import com.aliahad.wovoice.account.SessionManager
import com.aliahad.wovoice.background.BackgroundCheckKey
import com.aliahad.wovoice.background.BackgroundReliability
import com.aliahad.wovoice.background.BubbleHealthJobService
import com.aliahad.wovoice.background.HyperOS
import com.aliahad.wovoice.bubble.BubbleAccessibilityService
import com.aliahad.wovoice.bubble.BubbleKeepAliveService
import com.aliahad.wovoice.context.ContextLevel
import com.aliahad.wovoice.context.ContextPolicy
import com.aliahad.wovoice.dashboard.DictionaryAdapter
import com.aliahad.wovoice.dashboard.HistoryAdapter
import com.aliahad.wovoice.dashboard.HistoryRow
import com.aliahad.wovoice.data.AndroidGraph
import com.aliahad.wovoice.data.AnalyticsPeriod
import com.aliahad.wovoice.data.DashboardMetrics
import com.aliahad.wovoice.data.DictationRecord
import com.aliahad.wovoice.data.DictionaryEntry
import com.aliahad.wovoice.data.WoVoiceRepository
import com.aliahad.wovoice.sync.SyncCoordinator
import com.aliahad.wovoice.sync.SyncResult
import com.aliahad.wovoice.ui.dp
import com.aliahad.wovoice.ui.rounded
import com.aliahad.wovoice.ui.setButtonAccessibilityRole
import com.aliahad.wovoice.ui.styleText
import com.aliahad.wovoice.ui.systemAnimationsEnabled
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n")
class SetupActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: SettingsStore
    private lateinit var account: SessionManager
    private lateinit var sync: SyncCoordinator
    private lateinit var repository: WoVoiceRepository
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private val screens = mutableMapOf<Int, View>()
    private var activeTab = TAB_HOME
    private var analyticsPeriod = AnalyticsPeriod.TODAY
    private var historyJob: Job? = null
    private var dictionaryJob: Job? = null

    private val metricViews = mutableMapOf<String, TextView>()
    private var readinessText: TextView? = null
    private var backgroundWarningCard: View? = null
    private var backgroundWarningText: TextView? = null
    private var aiInsightsText: TextView? = null
    private var usageTotalText: TextView? = null
    private var usageBreakdownText: TextView? = null
    private var recentContainer: LinearLayout? = null
    private var periodSelector: LinearLayout? = null

    private var historyAdapter: HistoryAdapter? = null
    private var historyEmpty: TextView? = null
    private var historySearch: EditText? = null
    private var historyRecycler: RecyclerView? = null

    private var dictionaryConfirmed = true
    private var dictionaryAdapter: DictionaryAdapter? = null
    private var dictionaryEmpty: TextView? = null
    private var dictionarySearch: EditText? = null
    private var dictionaryRecycler: RecyclerView? = null
    private var dictionaryTabs: LinearLayout? = null
    private var dictionaryAdapterConfirmed: Boolean? = null

    private var microphoneStatus: TextView? = null
    private var keyboardStatus: TextView? = null
    private var accountStatus: TextView? = null
    private var accountQuota: TextView? = null
    private var accountButton: Button? = null
    private var accountSignInHelp: View? = null
    private var accountRestrictionMessage: TextView? = null
    private var accountSignOutAction: View? = null
    private var accountDeleteAction: View? = null
    private var cloudSyncStatus: TextView? = null
    private var cloudSyncWarning: TextView? = null
    private var cloudSyncActions: List<View> = emptyList()
    private var historySyncSwitch: SwitchMaterial? = null
    private var retentionAction: View? = null
    private var languageAction: View? = null
    private var polishAction: View? = null
    private var contextStatus: TextView? = null
    private var syncing = false
    private var authCallbackInProgress = false
    private var bubbleStatus: TextView? = null
    private var bubbleSwitch: SwitchMaterial? = null
    private var keepAliveSwitch: SwitchMaterial? = null
    private var backgroundAction: View? = null
    private var reliabilityDialog: AlertDialog? = null
    private var reliabilityBody: LinearLayout? = null
    private var pendingBubbleSetup = false
    private val bubbleHealthHandler = Handler(Looper.getMainLooper())
    private var lastBubbleConnected = false
    private val bubbleHealthRefresh = object : Runnable {
        override fun run() {
            updateBubbleStatus()
            val connected = BubbleAccessibilityService.isConnected()
            if (connected != lastBubbleConnected && activeTab == TAB_HOME) refreshHome()
            lastBubbleConnected = connected
            bubbleHealthHandler.postDelayed(this, 2_000)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        updateSetupStatus()
        refreshHome()
        if (granted) {
            if (pendingBubbleSetup) {
                pendingBubbleSetup = false
                openBubbleAccessibilitySettings()
            } else maybeRequestNotificationPermission()
        } else {
            if (pendingBubbleSetup) { pendingBubbleSetup = false; store.floatingBubbleEnabled = false; updateBubbleStatus() }
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) && canShowDialog()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Microphone access")
                    .setMessage("Allow the microphone in Android’s app settings to use voice dictation.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open settings") { _, _ ->
                        launchActivitySafely(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                            "Could not open Android app settings.",
                        )
                    }.show()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateSetupStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        account = SessionManager.get(store, SecretStore(this), { store.workerUrl }, ::androidDeviceName)
        sync = AndroidGraph.sync(this)
        repository = AndroidGraph.repository(this)
        activeTab = savedInstanceState?.getInt(STATE_TAB, TAB_HOME) ?: TAB_HOME
        analyticsPeriod = AnalyticsPeriod.entries.getOrElse(savedInstanceState?.getInt(STATE_PERIOD) ?: 0) {
            AnalyticsPeriod.TODAY
        }
        configureWindow()
        setContentView(buildShell())
        bottomNavigation.selectedItemId = activeTab
        showTab(activeTab, animate = false)
        handleAuthCallback(intent)
        scope.launch(Dispatchers.IO) {
            store.takeLegacyGlossary()?.let { repository.importGlossary(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        BubbleKeepAliveService.syncState(this)
        BubbleHealthJobService.sync(this)
        updateSetupStatus()
        refreshReliabilityDialogIfOpen()
        BubbleAccessibilityService.refreshConnection()
        bubbleHealthHandler.post(bubbleHealthRefresh)
        if (account.signedIn && !authCallbackInProgress) refreshAccount()
        when (activeTab) {
            TAB_HOME -> refreshHome()
            TAB_HISTORY -> refreshHistory()
            TAB_DICTIONARY -> refreshDictionary()
        }
    }

    override fun onPause() {
        bubbleHealthHandler.removeCallbacks(bubbleHealthRefresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, activeTab)
        outState.putInt(STATE_PERIOD, analyticsPeriod.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        reliabilityDialog?.dismiss()
        reliabilityDialog = null
        reliabilityBody = null
        historyJob?.cancel()
        dictionaryJob?.cancel()
        bubbleHealthHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun canShowDialog(): Boolean = !isFinishing && !isDestroyed

    private fun motionAllowed(): Boolean = store.animationsEnabled && systemAnimationsEnabled()

    private fun serviceHost(): String =
        Uri.parse(store.workerUrl).host?.takeIf(String::isNotBlank) ?: "LiVoice cloud"

    private fun isOwnImeSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName

    private fun launchActivitySafely(intent: Intent, fallbackMessage: String = "No app is available to open that screen.") {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(contentHost, fallbackMessage, Snackbar.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Snackbar.make(contentHost, fallbackMessage, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun launchCustomTabSafely(uri: Uri) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            launchActivitySafely(
                Intent(Intent.ACTION_VIEW, uri),
                "No web browser was found on this device.",
            )
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = BACKGROUND
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (::bottomNavigation.isInitialized) {
                bottomNavigation.visibility = if (imeVisible) View.GONE else View.VISIBLE
            }
            insets
        }

        contentHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomNavigation = BottomNavigationView(this).apply {
            setBackgroundColor(NAVIGATION)
            elevation = 0f
            minimumHeight = dp(68)
            itemIconSize = dp(23)
            itemTextAppearanceActive = com.google.android.material.R.style.TextAppearance_Material3_LabelMedium
            itemTextAppearanceInactive = com.google.android.material.R.style.TextAppearance_Material3_LabelMedium
            itemIconTintList = navigationColors()
            itemTextColor = navigationColors()
            itemRippleColor = ColorStateList.valueOf(Color.argb(34, 174, 163, 255))
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            menu.add(0, TAB_HOME, 0, "Home").setIcon(R.drawable.ic_nav_home)
            menu.add(0, TAB_HISTORY, 1, "History").setIcon(R.drawable.ic_nav_history)
            menu.add(0, TAB_DICTIONARY, 2, "Dictionary").setIcon(R.drawable.ic_nav_dictionary)
            menu.add(0, TAB_SETTINGS, 3, "Settings").setIcon(R.drawable.ic_nav_settings)
            setOnItemSelectedListener { item ->
                if (item.itemId != activeTab) showTab(item.itemId, animate = true)
                true
            }
        }
        root.addView(bottomNavigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun showTab(tab: Int, animate: Boolean) {
        val previous = contentHost.getChildAt(0)
        val incoming = screens.getOrPut(tab) {
            when (tab) {
                TAB_HISTORY -> buildHistoryScreen()
                TAB_DICTIONARY -> buildDictionaryScreen()
                TAB_SETTINGS -> buildSettingsScreen()
                else -> buildHomeScreen()
            }
        }
        if (previous === incoming) {
            refreshTab(tab)
            return
        }
        (incoming.parent as? ViewGroup)?.removeView(incoming)
        if (!animate || previous == null || !motionAllowed()) {
            contentHost.removeAllViews()
            contentHost.addView(incoming, FrameLayout.LayoutParams(-1, -1))
        } else {
            incoming.alpha = 0f
            incoming.translationX = dp(16).toFloat()
            contentHost.addView(incoming, FrameLayout.LayoutParams(-1, -1))
            incoming.animate().alpha(1f).translationX(0f).setDuration(190).start()
            previous.animate().alpha(0f).translationX(-dp(12).toFloat()).setDuration(150).withEndAction {
                contentHost.removeView(previous)
                previous.alpha = 1f
                previous.translationX = 0f
            }.start()
        }
        activeTab = tab
        refreshTab(tab)
    }

    private fun refreshTab(tab: Int) {
        when (tab) {
            TAB_HOME -> refreshHome()
            TAB_HISTORY -> refreshHistory()
            TAB_DICTIONARY -> refreshDictionary()
            TAB_SETTINGS -> updateSetupStatus()
        }
    }

    private fun buildHomeScreen(): View {
        val body = pageColumn()
        body.addView(hero("Your voice workspace", "Private analytics and recent dictations"))

        val readiness = TextView(this).apply {
            styleText(14f)
            setLineSpacing(0f, 1.2f)
        }
        readinessText = readiness
        body.addView(card("READY STATUS", readiness))

        val warningContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val warningCopy = TextView(this@SetupActivity).apply {
                styleText(13.5f, Color.WHITE)
                setLineSpacing(dp(2).toFloat(), 1.16f)
            }
            backgroundWarningText = warningCopy
            addView(warningCopy)
            addView(
                actionButton("Fix background setup") { showBubbleBackgroundHelp() },
                linear(match = true).apply { topMargin = dp(10) },
            )
        }
        val warningCard = card("BACKGROUND RELIABILITY", warningContent).apply {
            visibility = View.GONE
        }
        backgroundWarningCard = warningCard
        body.addView(warningCard)

        body.addView(sectionHeader("Overview"))
        val selector = segmented(
            listOf("Today", "7 days", "30 days", "All time"),
            analyticsPeriod.ordinal,
        ) { index ->
            analyticsPeriod = AnalyticsPeriod.entries[index]
            periodSelector?.let { styleSegmented(it, index) }
            refreshHome()
        }
        periodSelector = selector
        body.addView(selector, linear(match = true).apply { bottomMargin = dp(14) })

        body.addView(metricRow(metric("dictations", "Dictations"), metric("duration", "Dictation time")))
        body.addView(metricRow(metric("words", "Words dictated"), metric("wpm", "Speaking pace")))

        val insights = TextView(this).apply {
            styleText(15f)
            setLineSpacing(dp(3).toFloat(), 1.18f)
        }
        aiInsightsText = insights
        body.addView(card("AI INSIGHTS", insights))

        val usageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val total = TextView(this@SetupActivity).apply {
                styleText(26f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            usageTotalText = total
            addView(total)
            addView(TextView(this@SetupActivity).apply {
                text = "Estimated local LiVoice usage (informational only)"
                styleText(12.5f, MUTED)
                setPadding(0, dp(3), 0, dp(12))
            })
            val breakdown = TextView(this@SetupActivity).apply {
                styleText(14f, MUTED)
                setLineSpacing(dp(2).toFloat(), 1.16f)
            }
            usageBreakdownText = breakdown
            addView(breakdown)
        }
        body.addView(card("ESTIMATED AI USAGE", usageContent))

        body.addView(sectionHeader("Recent dictations", "View all") {
            bottomNavigation.selectedItemId = TAB_HISTORY
        })
        val recents = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recentContainer = recents
        body.addView(recents)
        return scroll(body)
    }

    private fun refreshHome() {
        if (readinessText == null) return
        updateReadiness()
        scope.launch {
            val now = ZonedDateTime.now()
            val (snapshot, usageRanges) = withContext(Dispatchers.IO) {
                val selected = repository.dashboard(analyticsPeriod)
                val today = repository.metricsSince(AnalyticsPeriod.TODAY.sinceMs(now))
                val month = repository.metricsSince(now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli())
                selected to (today to month)
            }
            val container = recentContainer ?: return@launch
            val metrics = WoVoiceRepository.metrics(snapshot.aggregates)
            renderMetrics(metrics, usageRanges.first, usageRanges.second)
            container.removeAllViews()
            if (snapshot.recent.isEmpty()) {
                container.addView(emptyState("Your successful dictations will appear here.", compact = true))
            } else {
                snapshot.recent.forEach { record -> container.addView(recentPreview(record)) }
            }
        }
    }

    private fun updateReadiness() {
        val target = readinessText ?: return
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = isOwnImeSelected()
        val online = hasValidatedNetwork()
        val connected = account.cloudServicesAllowed
        val accountUnavailable = if (account.signedIn && account.accountStatus.state.restricted) {
            "Account ${account.accountStatus.state.name.lowercase()} — manual keyboard still works"
        } else {
            "Sign in for voice input"
        }
        target.text = listOf(
            statusLine(mic, "Microphone ready", "Microphone permission needed"),
            statusLine(
                selected || (store.floatingBubbleEnabled && BubbleAccessibilityService.isEnabled(this) && BubbleAccessibilityService.isConnected()),
                if (selected) "LiVoice selected" else "Bubble ready — keep your current keyboard",
                if (enabled) "Choose LiVoice or enable the dictation bubble" else "Enable the keyboard or dictation bubble",
            ),
            statusLine(online, "Network available", "Network unavailable — manual keyboard still works"),
            statusLine(connected, "Account verified", accountUnavailable),
        ).joinToString("\n")
        updateBackgroundWarningBanner()
    }

    private fun updateBackgroundWarningBanner() {
        val cardView = backgroundWarningCard ?: return
        val copyView = backgroundWarningText ?: return
        if (!store.floatingBubbleEnabled) {
            cardView.visibility = View.GONE
            return
        }
        val snapshot = BackgroundReliability.collectSnapshot(this, store)
        val missing = BackgroundReliability.missingCritical(snapshot)
        if (missing.isEmpty() && snapshot.exitDiagnosis.swipeUpForceStops24h == 0) {
            cardView.visibility = View.GONE
            return
        }
        val parts = mutableListOf<String>()
        if (snapshot.exitDiagnosis.swipeUpForceStops24h > 0) {
            parts += BackgroundReliability.watchdogMessage(snapshot)
        }
        if (missing.isNotEmpty()) {
            parts += "Missing critical setup: ${missing.joinToString(", ") { it.title }}."
        }
        copyView.text = parts.joinToString("\n\n")
        cardView.visibility = View.VISIBLE
    }

    private fun renderMetrics(value: DashboardMetrics, today: DashboardMetrics, month: DashboardMetrics) {
        setMetric("dictations", value.dictations.toString(), "successful")
        setMetric("duration", formatDuration(value.audioDurationMs), "recorded speech")
        setMetric("words", value.words.toString(), if (value.words == 1L) "total word" else "total words")
        setMetric("wpm", value.wpm.toString(), "words per minute")
        aiInsightsText?.text = "Median processing   ${formatProcessing(value.medianProcessingMs)}\n" +
            "Polished results     ${value.polishedRate}%\n" +
            "Correction rate      ${value.correctionRate}%"
        val estimatesVisible = store.costEstimatesEnabled
        usageTotalText?.text = if (estimatesVisible) formatCost(value.estimatedCostUsd) else "Hidden"
        usageBreakdownText?.text = if (estimatesVisible) {
            "Today ${formatCost(today.estimatedCostUsd)}  •  This month ${formatCost(month.estimatedCostUsd)}\n" +
                "${formatNeurons(value.totalNeurons)} neurons in selected period\n" +
                "ASR ${formatNeurons(value.asrNeurons)}  •  Polish ${formatNeurons(value.polishNeurons)}"
        } else {
            "Enable cost estimates in Settings to show local usage."
        }
    }

    private fun buildHistoryScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(15), dp(20), 0)
        }
        root.addView(
            screenHeader(
                title = "History",
                subtitle = "Successful dictations kept on this phone",
                action = "Clear",
                secondaryAction = "Export",
                onSecondaryAction = ::exportHistory,
                onAction = ::confirmClearHistory,
            ),
        )
        val search = searchField("Search generated text")
        historySearch = search
        root.addView(search, linear(match = true).apply { bottomMargin = dp(10) })
        search.onTextChanged { refreshHistory() }

        val listFrame = FrameLayout(this)
        val adapter = HistoryAdapter(::showHistoryDetail, ::copyRecord)
        historyAdapter = adapter
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SetupActivity)
            this.adapter = adapter
            itemAnimator?.changeDuration = if (motionAllowed()) 170 else 0
            clipToPadding = false
            setPadding(0, 0, 0, dp(22))
        }
        historyRecycler = recycler
        val empty = emptyState("No dictations yet.\nUse LiVoice in any text field and your successful results will appear here.")
        historyEmpty = empty
        listFrame.addView(recycler, frame(match = true))
        listFrame.addView(empty, frame(match = true))
        root.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (adapter.recordAt(viewHolder.bindingAdapterPosition) == null) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.recordAt(viewHolder.bindingAdapterPosition)?.let(::deleteHistoryWithUndo)
            }
        }).attachToRecyclerView(recycler)
        return root
    }

    private fun refreshHistory() {
        val adapter = historyAdapter ?: return
        val search = historySearch ?: return
        historyJob?.cancel()
        historyJob = scope.launch {
            val records = withContext(Dispatchers.IO) { repository.history(search.text.toString()) }
            val rows = historyRows(records)
            adapter.submitList(rows)
            historyEmpty?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            historyRecycler?.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun exportHistory() {
        val query = historySearch?.text?.toString().orEmpty()
        scope.launch {
            val records = withContext(Dispatchers.IO) { repository.history(query) }
            if (records.isEmpty()) {
                Snackbar.make(contentHost, "No dictations to export.", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val payload = records.joinToString("\n\n---\n\n") { record ->
                "${historyDateTime(record)} (${formatWordCount(record.wordCount)})\n${record.finalText}"
            }
            val shareIntent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "LiVoice dictation history")
                .putExtra(Intent.EXTRA_TEXT, payload)
            launchActivitySafely(
                Intent.createChooser(shareIntent, "Export dictation history"),
                "No app is available to share history.",
            )
        }
    }

    private fun historyRows(records: List<DictationRecord>): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        var lastLabel: String? = null
        records.forEach { record ->
            val label = historyGroup(record)
            if (label != lastLabel) {
                rows += HistoryRow.Header(label, "header-$label")
                lastLabel = label
            }
            rows += HistoryRow.Item(record)
        }
        return rows
    }

    private fun buildDictionaryScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(15), dp(20), 0)
        }
        root.addView(screenHeader("Dictionary", "Names and terms LiVoice should recognize", "+ Add") { showTermDialog() })
        val tabs = segmented(listOf("Confirmed", "Suggestions"), 0) { index ->
            dictionaryConfirmed = index == 0
            dictionaryTabs?.let { styleSegmented(it, index) }
            refreshDictionary()
        }
        dictionaryTabs = tabs
        root.addView(tabs, linear(match = true).apply { bottomMargin = dp(10) })
        val search = searchField("Search dictionary")
        dictionarySearch = search
        root.addView(search, linear(match = true).apply { bottomMargin = dp(10) })
        search.onTextChanged { refreshDictionary() }

        val frame = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SetupActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp(22))
        }
        dictionaryRecycler = recycler
        val empty = emptyState("No terms here yet.")
        dictionaryEmpty = empty
        frame.addView(recycler, frame(match = true))
        frame.addView(empty, frame(match = true))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        installDictionaryAdapter()
        return root
    }

    private fun installDictionaryAdapter() {
        val recycler = dictionaryRecycler ?: return
        val adapter = DictionaryAdapter(
            confirmed = dictionaryConfirmed,
            onPrimary = { entry -> if (dictionaryConfirmed) showTermDialog(entry) else acceptSuggestion(entry) },
            onDelete = { entry -> deleteDictionaryEntry(entry) },
        )
        dictionaryAdapter = adapter
        recycler.adapter = adapter
        dictionaryAdapterConfirmed = dictionaryConfirmed
    }

    private fun refreshDictionary() {
        if (dictionaryRecycler == null) return
        val search = dictionarySearch ?: return
        dictionaryJob?.cancel()
        if (dictionaryAdapter == null || dictionaryAdapterConfirmed != dictionaryConfirmed) installDictionaryAdapter()
        val adapter = dictionaryAdapter ?: return
        dictionaryJob = scope.launch {
            val values = withContext(Dispatchers.IO) {
                repository.dictionary(dictionaryConfirmed, search.text.toString())
            }
            adapter.submitList(values)
            dictionaryEmpty?.text = if (dictionaryConfirmed) {
                "No confirmed terms yet.\nAdd important names or specialist words."
            } else {
                "No suggestions to review.\nLiVoice will suggest unusual words after a correction."
            }
            dictionaryEmpty?.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
            dictionaryRecycler?.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun buildSettingsScreen(): View {
        val body = pageColumn()
        body.addView(hero("Settings", "Setup, preferences, privacy, and data"))

        microphoneStatus = statusText()
        keyboardStatus = statusText()
        body.addView(settingsCard("SETUP", listOf(
            settingBlock("Microphone", microphoneStatus!!, "Allow") { requestMicrophone() },
            settingBlock("Keyboard access", keyboardStatus!!, "Enable") {
                launchActivitySafely(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), "Could not open keyboard settings.")
            },
            settingAction("Choose active keyboard", "Open Android’s keyboard picker", "Choose") {
                runCatching {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }
            },
        )))

        bubbleStatus = statusText()
        bubbleSwitch = settingSwitch(
            "Speak without switching keyboard",
            "Show a movable dictation bubble beside the text box you select",
            store.floatingBubbleEnabled,
        ) { enabled ->
            if (enabled) enableBubble() else {
                store.floatingBubbleEnabled = false
                BubbleKeepAliveService.syncState(this)
                BubbleHealthJobService.sync(this)
                updateBubbleStatus()
            }
        }
        keepAliveSwitch = settingSwitch(
            "Keep running in background",
            "Shows a quiet status notification so closing LiVoice keeps the bubble alive",
            store.keepAliveEnabled,
        ) { enabled ->
            store.keepAliveEnabled = enabled
            BubbleKeepAliveService.syncState(this)
            BubbleHealthJobService.sync(this)
            updateBubbleStatus()
            updateBackgroundWarningBanner()
        }
        backgroundAction = settingAction(
            "Keep the bubble available",
            "Check Autostart, battery, and background protection",
            "Setup",
            ::showBubbleBackgroundHelp,
        )
        body.addView(settingsCard("DICTATION BUBBLE", listOf(
            bubbleSwitch!!,
            bubbleStatus!!,
            keepAliveSwitch!!,
            TextView(this).apply {
                text = "Keep Gboard or any other keyboard. Tap the bubble to speak, then tap again to insert. Drag it to move it. Password fields are blocked; changing text boxes cancels the recording."
                styleText(13f, MUTED)
                setLineSpacing(dp(2).toFloat(), 1.12f)
            },
            settingAction("Accessibility connection", "If disconnected, turn LiVoice off and on once in Accessibility", "Reconnect") {
                if (store.floatingBubbleEnabled) openBubbleAccessibilitySettings() else enableBubble()
            },
            backgroundAction!!,
        )))

        contextStatus = statusText()
        body.addView(settingsCard("AUTOMATIC CONTEXT", listOf(
            contextStatus!!,
            settingAction("Understand the writing situation", "Detect the app and field automatically. No situation picker while dictating.", "Choose", ::chooseContext),
            settingAction("Style preference", "Automatic follows the available clues; unknown audiences keep your natural tone.", "Choose") {
                if (!canShowDialog()) return@settingAction
                val labels = arrayOf("Automatic (recommended)", "Natural", "Polite", "Professional", "Concise")
                MaterialAlertDialogBuilder(this).setTitle("Style preference")
                    .setSingleChoiceItems(labels, ContextPolicy.styles.indexOf(store.contextStyle).coerceAtLeast(0)) { dialog, index ->
                        store.contextStyle = ContextPolicy.styles[index]
                        updateContextStatus(); dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            },
        )))
        updateContextStatus()

        accountStatus = statusText()
        accountQuota = statusText()
        accountButton = actionButton(if (account.signedIn) "Manage signed-in devices" else "Sign in or create account") {
            when {
                !account.signedIn -> startAccountLogin()
                account.accountStatus.state.restricted -> showAccountRestriction()
                else -> showSessions()
            }
        }
        accountSignInHelp = TextView(this).apply {
            text = "Passwordless sign-in opens ${serviceHost()}. Your rotating session credential is protected by Android Keystore."
            styleText(12.5f, MUTED)
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        accountRestrictionMessage = TextView(this).apply {
            styleText(13f, ERROR)
            setLineSpacing(dp(2).toFloat(), 1.12f)
            setPadding(dp(2), dp(7), dp(2), dp(5))
            visibility = View.GONE
        }
        cloudSyncStatus = statusText()
        cloudSyncWarning = TextView(this).apply {
            styleText(12.5f, ERROR)
            setLineSpacing(dp(2).toFloat(), 1.12f)
            setPadding(dp(2), dp(4), dp(2), dp(4))
            visibility = View.GONE
        }
        val syncNowAction = settingAction(
            "Cloud sync",
            "History, dictionary, and analytics follow your account on every device",
            "Sync now",
        ) { runCloudSync(showResult = true) }
        historySyncSwitch = settingSwitch(
            "Sync history",
            "Keep dictated text in your account so your other devices show it",
            store.historySyncEnabled,
        ) { enabled -> onHistorySyncToggled(enabled) }
        retentionAction = settingAction("Auto-delete history", retentionSummary(), "Change") { chooseRetention() }
        cloudSyncActions = listOf(cloudSyncStatus!!, cloudSyncWarning!!, syncNowAction, historySyncSwitch!!, retentionAction!!)
        accountSignOutAction = settingAction(
            "Sign out",
            "Manual keyboard remains available offline",
            "Sign out",
        ) { confirmSignOut() }
        accountDeleteAction = destructiveAction(
            "Delete LiVoice account",
            "Fresh email verification permanently removes cloud account data",
        ) { confirmAccountDeletion() }
        body.addView(settingsCard("ACCOUNT", listOf(
            accountStatus!!,
            accountQuota!!,
            accountButton!!,
            accountSignInHelp!!,
            accountRestrictionMessage!!,
            *cloudSyncActions.toTypedArray(),
            accountSignOutAction!!,
            accountDeleteAction!!,
        )))

        languageAction = settingAction("Recognition language", languageLabel(store.locale), "Choose", ::chooseLanguage)
        polishAction = settingAction("Grammar polish", polishLabel(store.polish), "Choose", ::choosePolish)
        body.addView(settingsCard("VOICE & LANGUAGE", listOf(
            languageAction!!,
            polishAction!!,
            fixedSetting("Punctuation and numerals", "Automatic"),
            fixedSetting("Spoken commands", "New line and new paragraph"),
        )))

        body.addView(settingsCard("KEYBOARD", listOf(
            settingSwitch("Haptic feedback", "Feel a light response when tapping keys", store.hapticsEnabled) { store.hapticsEnabled = it },
            settingSwitch("Smooth animations", "Transitions and processing motion", store.animationsEnabled) { store.animationsEnabled = it },
            settingSwitch("Live waveform", "Show microphone activity while speaking", store.waveformEnabled) { store.waveformEnabled = it },
        )))

        body.addView(settingsCard("HISTORY & ANALYTICS", listOf(
            settingSwitch("Keep local history", "Store successful generated text until you delete it", store.historyEnabled) { store.historyEnabled = it },
            settingSwitch("Show cost estimates", "LiVoice-only estimates in USD", store.costEstimatesEnabled) { store.costEstimatesEnabled = it; refreshHome() },
            destructiveAction("Clear history", "Remove generated text but keep anonymous totals") { confirmClearHistory() },
            destructiveAction("Reset analytics", "Remove dictation and estimated usage totals") { confirmResetAnalytics() },
        )))

        body.addView(settingsCard("DICTIONARY & LEARNING", listOf(
            settingSwitch("Correction suggestions", "Review likely names and unique terms before adding them", store.learningSuggestionsEnabled) {
                store.learningSuggestionsEnabled = it
            },
            settingAction("Manage dictionary", "Confirmed terms and suggestions", "Open") {
                bottomNavigation.selectedItemId = TAB_DICTIONARY
            },
        )))

        body.addView(settingsCard("PRIVACY & DATA", listOf(
            TextView(this).apply {
                text = "Recordings are temporary and deleted after dictation. A failed upload may stay on this phone for up to a minute for Retry, and is removed on cancel or a text-box change. Optional automatic context sends a writing-task category; Nearby text also sends up to 512 characters before and 128 after the cursor for this request only. Context is not saved in history, sync, or logs; source-app identity stays on this phone. Synced history, approved dictionary entries, and analytics are encrypted for storage by LiVoice."
                styleText(14f, MUTED)
                setLineSpacing(dp(3).toFloat(), 1.15f)
                setPadding(0, dp(4), 0, dp(10))
            },
            destructiveAction("Clear all local data", "Remove history, analytics, dictionary, and local sign-in credentials") { confirmClearAll() },
        )))

        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("1.0")
        body.addView(settingsCard("ABOUT", listOf(
            fixedSetting("LiVoice", "Version $versionName"),
            fixedSetting("Service", "${serviceHost()} • Cloudflare Workers AI"),
            fixedSetting("Storage", "Encrypted sync • no backup • no analytics SDK"),
        )))
        return scroll(body)
    }

    private fun updateSetupStatus() {
        updateBubbleStatus()
        updateContextStatus()
        updateVoiceLanguageSummaries()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && !pendingBubbleSetup) maybeRequestNotificationPermission()
        microphoneStatus?.text = if (micGranted) "✓ Allowed and ready" else "Permission required for speech input"
        microphoneStatus?.setTextColor(if (micGranted) SUCCESS else MUTED)
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = isOwnImeSelected()
        keyboardStatus?.text = when {
            selected -> "✓ LiVoice is selected"
            enabled -> "Enabled — choose LiVoice as active keyboard"
            else -> "LiVoice is not enabled yet"
        }
        keyboardStatus?.setTextColor(if (selected) SUCCESS else MUTED)
        val signedIn = account.signedIn
        val status = account.accountStatus
        val restricted = signedIn && status.state.restricted
        accountStatus?.text = when {
            !signedIn -> "Sign in to enable cloud speech"
            status.state == AccountState.SUSPENDED -> "Account suspended • ${account.email ?: "Verified account"}"
            status.state == AccountState.BANNED -> "Account banned • ${account.email ?: "Verified account"}"
            else -> "✓ ${account.email ?: "Verified LiVoice account"}"
        }
        accountStatus?.setTextColor(when {
            restricted -> ERROR
            signedIn -> SUCCESS
            else -> MUTED
        })
        accountButton?.text = when {
            !signedIn -> "Sign in or create account"
            restricted -> "View account status"
            else -> "Manage signed-in devices"
        }
        accountQuota?.text = if (restricted) {
            "Voice input and encrypted sync are paused. Manual typing remains available offline."
        } else if (signedIn) {
            account.currentQuota?.let { quota ->
                val base = "${quota.remainingAudioSeconds.roundToInt()} of ${quota.limitAudioSeconds.roundToInt()} voice seconds remaining today"
                val reset = formatQuotaReset(quota.resetAtMs)
                if (reset != null) "$base • $reset" else base
            } ?: "Checking today’s free quota…"
        } else {
            "Daily free voice allowance included with your LiVoice account"
        }
        accountQuota?.setTextColor(if (restricted) ERROR else MUTED)
        accountRestrictionMessage?.text = if (restricted) restrictionSummary() else ""
        accountRestrictionMessage?.visibility = if (restricted) View.VISIBLE else View.GONE
        accountSignInHelp?.visibility = if (signedIn) View.GONE else View.VISIBLE
        cloudSyncActions.forEach { it.visibility = if (signedIn && !restricted) View.VISIBLE else View.GONE }
        updateCloudSyncStatus()
        accountSignOutAction?.visibility = if (signedIn) View.VISIBLE else View.GONE
        accountDeleteAction?.visibility = if (signedIn) View.VISIBLE else View.GONE
    }

    private fun updateBubbleStatus() {
        if (!::store.isInitialized) return
        bubbleSwitch?.let { switch ->
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = store.floatingBubbleEnabled
            switch.setOnCheckedChangeListener { _, enabled ->
                if (enabled) enableBubble() else {
                    store.floatingBubbleEnabled = false
                    BubbleKeepAliveService.syncState(this)
                    BubbleHealthJobService.sync(this)
                    updateBubbleStatus()
                }
            }
        }
        keepAliveSwitch?.let { switch ->
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = store.keepAliveEnabled
            switch.setOnCheckedChangeListener { _, enabled ->
                store.keepAliveEnabled = enabled
                BubbleKeepAliveService.syncState(this)
                BubbleHealthJobService.sync(this)
                updateBubbleStatus()
                updateBackgroundWarningBanner()
            }
        }
        val restricted = account.signedIn && account.accountStatus.state.restricted
        bubbleStatus?.text = when {
            !store.floatingBubbleEnabled -> "Bubble is off"
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Microphone permission needed"
            !BubbleAccessibilityService.isEnabled(this) -> "Accessibility access is off — tap Reconnect"
            !BubbleAccessibilityService.isConnected() -> "Accessibility disconnected — tap Reconnect"
            !account.signedIn -> "Accessibility ready — sign in below to dictate"
            restricted -> "Account ${account.accountStatus.state.name.lowercase()} — bubble dictation is paused"
            else -> "Ready — select a text box while using another keyboard"
        }
        bubbleStatus?.setTextColor(if (restricted && store.floatingBubbleEnabled) ERROR else MUTED)
        val snapshot = BackgroundReliability.collectSnapshot(this, store)
        updateSettingSummary(backgroundAction, BackgroundReliability.summaryLine(snapshot))
        updateBackgroundWarningBanner()
    }

    private fun showBubbleBackgroundHelp() {
        if (!canShowDialog()) return
        reliabilityDialog?.dismiss()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        reliabilityBody = body
        populateReliabilityChecklist(body)
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Background reliability setup")
            .setView(scroll)
            .setNeutralButton("App info") { _, _ -> HyperOS.appDetails(this) }
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnDismissListener {
            if (reliabilityDialog === dialog) {
                reliabilityDialog = null
                reliabilityBody = null
            }
        }
        reliabilityDialog = dialog
        dialog.show()
    }

    private fun refreshReliabilityDialogIfOpen() {
        val dialog = reliabilityDialog ?: return
        val body = reliabilityBody ?: return
        if (!dialog.isShowing) return
        populateReliabilityChecklist(body)
    }

    private fun populateReliabilityChecklist(container: LinearLayout) {
        container.removeAllViews()
        val snapshot = BackgroundReliability.collectSnapshot(this, store)
        val items = BackgroundReliability.evaluateChecks(snapshot)
        val done = items.count { it.ok == true }

        container.addView(TextView(this).apply {
            text = "$done of ${items.size} ready"
            styleText(18f, Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            val platform = if (snapshot.isXiaomi) "Xiaomi HyperOS" else "Android"
            text = "$platform stops background apps aggressively. Tap Fix on each item below so closing LiVoice keeps your dictation bubble and Accessibility active."
            styleText(13f, MUTED)
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, dp(4), 0, dp(10))
        })

        // 24h watchdog card (powered by ApplicationExitInfo on Android 11+)
        val watchdog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(
                if (snapshot.exitDiagnosis.hadRecentInterruption) Color.argb(46, 255, 114, 132) else CARD,
                dp(12).toFloat(),
            )
            addView(TextView(this@SetupActivity).apply {
                text = if (snapshot.exitDiagnosis.hadRecentInterruption) "24h watchdog — interruption detected" else "24h watchdog — healthy"
                styleText(13.5f, if (snapshot.exitDiagnosis.hadRecentInterruption) ERROR else SUCCESS)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@SetupActivity).apply {
                text = BackgroundReliability.watchdogMessage(snapshot)
                styleText(12.5f, Color.WHITE)
                setLineSpacing(dp(2).toFloat(), 1.15f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        container.addView(watchdog, linear(match = true).apply { bottomMargin = dp(12) })

        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(CARD, dp(12).toFloat())
            }
            val (badgeText, badgeColor) = when (item.ok) {
                true -> "✓" to SUCCESS
                false -> "!" to ERROR
                null -> "?" to Color.rgb(224, 161, 0)
            }
            val badge = TextView(this).apply {
                text = badgeText
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                background = rounded(badgeColor, dp(13).toFloat())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginEnd = dp(10)
                topMargin = dp(2)
            })

            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(this).apply {
                text = if (item.critical) item.title else "${item.title} (recommended)"
                styleText(14f, Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            textCol.addView(TextView(this).apply {
                text = item.summary
                styleText(12.5f, MUTED)
                setLineSpacing(dp(2).toFloat(), 1.14f)
                setPadding(0, dp(3), 0, dp(8))
            })

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (item.key != BackgroundCheckKey.RECENTS_LOCK) {
                val label = if (item.ok == true) "Open" else "Fix"
                val button = smallAction(label, "$label ${item.title}") {
                    runReliabilityFix(item.key)
                }
                actions.addView(button)
            }
            if (item.confirmable) {
                val confirmLabel = if (item.ok == true) "Undo" else "I've done this"
                val confirmBtn = smallAction(confirmLabel, "$confirmLabel ${item.title}") {
                    store.recentsLockConfirmed = item.ok != true
                    populateReliabilityChecklist(container)
                    updateBubbleStatus()
                }
                actions.addView(confirmBtn, linear().apply {
                    if (item.key != BackgroundCheckKey.RECENTS_LOCK) marginStart = dp(8)
                })
            }
            textCol.addView(actions)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row, linear(match = true).apply { bottomMargin = dp(8) })
        }
    }

    private fun runReliabilityFix(key: BackgroundCheckKey) {
        when (key) {
            BackgroundCheckKey.ACCESSIBILITY -> openBubbleAccessibilitySettings()
            BackgroundCheckKey.BATTERY -> {
                if (!HyperOS.battery(this)) {
                    Snackbar.make(contentHost, "Could not open battery settings.", Snackbar.LENGTH_LONG).show()
                }
            }
            BackgroundCheckKey.AUTOSTART -> {
                if (!HyperOS.autostart(this)) {
                    Snackbar.make(contentHost, "Could not open Autostart settings.", Snackbar.LENGTH_LONG).show()
                }
            }
            BackgroundCheckKey.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    HyperOS.notifications(this)
                }
            }
            BackgroundCheckKey.KEEP_ALIVE -> {
                store.floatingBubbleEnabled = true
                store.keepAliveEnabled = true
                BubbleKeepAliveService.syncState(this)
                BubbleHealthJobService.sync(this)
                bubbleHealthHandler.postDelayed({
                    refreshReliabilityDialogIfOpen()
                    updateBubbleStatus()
                }, 350)
            }
            BackgroundCheckKey.RECENTS_LOCK -> Unit
        }
    }

    private fun updateContextStatus() {
        val label = when (store.contextLevel) {
            ContextLevel.OFF -> "Off"
            ContextLevel.APP -> "App and field only"
            ContextLevel.NEARBY -> "App, field, and nearby text"
        }
        contextStatus?.text = "$label • Style: ${store.contextStyle}"
    }

    private fun updateVoiceLanguageSummaries() {
        updateSettingSummary(languageAction, languageLabel(store.locale))
        updateSettingSummary(polishAction, polishLabel(store.polish))
    }

    private fun updateSettingSummary(row: View?, text: String) {
        (((row as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(1) as? TextView)?.text = text
    }

    private fun chooseLanguage() {
        if (!canShowDialog()) return
        val codes = LANGUAGE_OPTIONS.map { it.first }
        val labels = LANGUAGE_OPTIONS.map { it.second }.toTypedArray()
        val selected = codes.indexOf(store.locale).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Recognition language")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                store.locale = codes[index]
                updateVoiceLanguageSummaries()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choosePolish() {
        if (!canShowDialog()) return
        val codes = POLISH_OPTIONS.map { it.first }
        val labels = POLISH_OPTIONS.map { it.second }.toTypedArray()
        val selected = codes.indexOf(store.polish).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Grammar polish")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                store.polish = codes[index]
                updateVoiceLanguageSummaries()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseContext() {
        if (!canShowDialog()) return
        val levels = ContextLevel.entries
        MaterialAlertDialogBuilder(this).setTitle("Automatic context")
            .setSingleChoiceItems(arrayOf("Off", "App and field only", "App, field, and nearby text"), levels.indexOf(store.contextLevel)) { dialog, index ->
                dialog.dismiss()
                val level = levels[index]
                if (level == ContextLevel.OFF) {
                    store.contextLevel = level
                    updateContextStatus()
                } else if (canShowDialog()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (level == ContextLevel.NEARBY) "Allow nearby text?" else "Allow app and field context?")
                        .setMessage(
                            if (level == ContextLevel.NEARBY) {
                                "When you start dictation, LiVoice uses the focused editor and up to 512 characters before and 128 after your selection. These snippets go to LiVoice’s service with that recording to guide grammar and tone. They are not saved in history, sync, or logs. It does not read full conversations or contacts. Password and sensitive fields are excluded. You can switch this off at any time."
                            } else {
                                "When you start dictation, LiVoice detects the app and field locally and sends only a writing-task category, such as chat or email. App names, field labels, and existing text are not sent. Unknown situations keep your natural tone. You can switch this off at any time."
                            },
                        )
                        .setPositiveButton("Allow") { _, _ -> store.contextLevel = level; updateContextStatus() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun enableBubble() {
        if (!canShowDialog()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Speak without switching keyboard")
            .setMessage("LiVoice needs Accessibility access to find and edit the text box you select. Surrounding text is never sent unless you turn on optional Nearby text in LiVoice Settings. Your recording and approved Dictionary terms are sent for transcription.\n\nRecording starts when you tap the bubble. Failed uploads may stay on this phone for up to a minute for Retry. Password fields are blocked. You can turn this off here at any time.")
            .setNegativeButton("Not now") { _, _ -> updateBubbleStatus() }
            .setOnCancelListener { updateBubbleStatus() }
            .setPositiveButton("Continue") { _, _ ->
                store.floatingBubbleEnabled = true
                BubbleKeepAliveService.syncState(this)
                BubbleHealthJobService.sync(this)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    openBubbleAccessibilitySettings()
                } else {
                    pendingBubbleSetup = true
                    requestMicrophone()
                }
                updateBubbleStatus()
            }.show()
    }

    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) && canShowDialog()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow microphone access")
                .setMessage("LiVoice records only while you ask it to listen. Recordings are deleted after dictation; a failed upload can stay on this phone for up to a minute so you can retry.")
                .setNegativeButton("Not now") { _, _ -> pendingBubbleSetup = false; updateBubbleStatus() }
                .setPositiveButton("Continue") { _, _ -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }.show()
        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openBubbleAccessibilitySettings() {
        // The service-details action requires a system permission on newer Android versions.
        // The public Accessibility settings screen works across Android and vendor builds.
        launchActivitySafely(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            "Could not open Android Accessibility settings.",
        )
    }

    private fun startAccountLogin(intent: String = SessionManager.AUTH_LOGIN) {
        val request = account.prepareLogin(intent)
        val uri = Uri.parse("${store.workerUrl}/auth").buildUpon()
            .appendQueryParameter("code_challenge", request.challenge)
            .appendQueryParameter("state", request.state)
            .appendQueryParameter("intent", intent)
            .build()
        launchCustomTabSafely(uri)
    }

    private fun handleAuthCallback(value: Intent?) {
        val uri = value?.data ?: return
        val expectedHost = Uri.parse(store.workerUrl).host
        if (uri.scheme != "https" || uri.host != expectedHost || uri.path != "/app/callback") return
        value.data = null
        if (authCallbackInProgress) return
        val code = uri.getQueryParameter("code").orEmpty()
        val reauthToken = uri.getQueryParameter("reauth_token").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        if ((code.isBlank() && reauthToken.isBlank()) || state.isBlank() || !account.pendingLoginMatches(state)) {
            Snackbar.make(contentHost, "The sign-in response could not be verified. Please try again.", Snackbar.LENGTH_LONG).show()
            return
        }
        authCallbackInProgress = true
        accountStatus?.text = if (reauthToken.isNotBlank()) "Deleting LiVoice account…" else "Completing secure sign-in…"
        accountButton?.isEnabled = false
        accountDeleteAction?.isEnabled = false
        val previousAccountId = store.lastAccountId
        val activeAccountId = account.accountId ?: previousAccountId
        scope.launch {
            try {
                if (reauthToken.isNotBlank()) {
                    completeAccountDeletion(reauthToken, state, activeAccountId)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { account.completeLogin(code, state) }
                when (result) {
                    is AccountResult.Success -> {
                        Snackbar.make(contentHost, "Signed in as ${result.value.email}", Snackbar.LENGTH_LONG).show()
                        handleLocalDataAfterLogin(result.value.id, previousAccountId)
                        refreshAccount()
                        refreshHome()
                    }
                    is AccountResult.Error -> {
                        accountStatus?.text = result.message
                        accountStatus?.setTextColor(ERROR)
                        Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
                    }
                }
            } finally {
                authCallbackInProgress = false
                accountButton?.isEnabled = true
                accountDeleteAction?.isEnabled = true
            }
        }
    }

    private suspend fun completeAccountDeletion(reauthToken: String, state: String, accountId: String?) {
        when (val result = withContext(Dispatchers.IO) { account.completeAccountDeletion(reauthToken, state) }) {
            is AccountResult.Success -> {
                if (!accountId.isNullOrBlank()) withContext(Dispatchers.IO) { repository.deleteAccountLocalData(accountId) }
                Snackbar.make(contentHost, "LiVoice account and its local partition were deleted.", Snackbar.LENGTH_LONG).show()
                updateSetupStatus(); refreshHome(); refreshHistory(); refreshDictionary()
            }
            is AccountResult.Error -> Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handleLocalDataAfterLogin(accountId: String, previousAccountId: String?) {
        if (!previousAccountId.isNullOrBlank() && previousAccountId != accountId && canShowDialog()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Keep previous account data?")
                .setMessage("Local records from the previous account are isolated and will never sync to this account. Keep them for a later sign-in, or delete them from this phone now.")
                .setNegativeButton("Keep") { _, _ -> promptLegacyData(accountId) }
                .setPositiveButton("Delete") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.deleteAccountLocalData(previousAccountId) }
                        promptLegacyData(accountId)
                    }
                }
                .show()
        } else promptLegacyData(accountId)
    }

    private fun promptLegacyData(accountId: String) {
        scope.launch {
            val count = withContext(Dispatchers.IO) { repository.unassignedCount() }
            if (count <= 0 || !canShowDialog()) return@launch
            MaterialAlertDialogBuilder(this@SetupActivity)
                .setTitle("Use existing local data?")
                .setMessage("LiVoice found $count local history, analytics, or dictionary items from before accounts were added. Assign them to this verified account for encrypted sync, delete them, or decide later.")
                .setNeutralButton("Later", null)
                .setNegativeButton("Delete") { _, _ ->
                    scope.launch { withContext(Dispatchers.IO) { repository.deleteUnassigned() } }
                }
                .setPositiveButton("Assign") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.assignUnassignedTo(accountId) }
                        refreshHome(); refreshHistory(); refreshDictionary()
                        runCloudSync(showResult = false)
                    }
                }
                .show()
        }
    }

    private fun refreshAccount() {
        if (!account.signedIn) {
            updateSetupStatus()
            return
        }
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { account.loadProfile() }) {
                is AccountResult.Success -> {
                    updateSetupStatus()
                    showPolicyUpdateNoticeIfNeeded()
                    updateCloudSyncStatus()
                    if (account.cloudServicesAllowed) runCloudSync(showResult = false)
                }
                is AccountResult.Error -> {
                    updateSetupStatus()
                    if (result.code == "AUTH_REQUIRED") {
                        Snackbar.make(contentHost, "Your session ended. Sign in again to use voice input.", Snackbar.LENGTH_LONG).show()
                    } else if (!account.accountStatus.state.restricted) {
                        accountQuota?.text = result.message
                        accountQuota?.setTextColor(ERROR)
                    }
                }
            }
            refreshHome()
        }
    }

    private fun showSessions() {
        if (!account.cloudServicesAllowed) {
            showAccountRestriction()
            return
        }
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { account.listSessions() }) {
                is AccountResult.Success -> showSessionList(result.value)
                is AccountResult.Error -> Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showSessionList(values: List<DeviceSession>) {
        if (!canShowDialog()) return
        if (values.isEmpty()) {
            Snackbar.make(contentHost, "No active devices were found.", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = values.map { session ->
            buildString {
                append(session.deviceName)
                if (session.current) append("  •  This device")
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Signed-in devices")
            .setItems(labels) { _, index -> confirmRevokeSession(values[index]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmRevokeSession(session: DeviceSession) = confirm(
        if (session.current) "Sign out this device?" else "Revoke ${session.deviceName}?",
        if (session.current) {
            "Voice input will stop until you sign in again. Local encrypted data is not deleted."
        } else {
            "That device will have to sign in again. Its local encrypted data is not remotely erased."
        },
        if (session.current) "Sign out" else "Revoke",
    ) {
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { account.revokeSession(session.id) }) {
                is AccountResult.Success -> {
                    if (session.current) account.clearLocalSession()
                    updateSetupStatus(); refreshHome()
                    Snackbar.make(contentHost, "Device session revoked.", Snackbar.LENGTH_SHORT).show()
                }
                is AccountResult.Error -> Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmSignOut() {
        if (!account.signedIn) {
            startAccountLogin()
            return
        }
        confirm(
            "Sign out of LiVoice?",
            "Voice input and encrypted sync will pause. Your local data will remain on this phone.",
            "Sign out",
        ) {
            scope.launch {
                val revoked = withContext(Dispatchers.IO) { account.logout() }
                if (!revoked) {
                    Snackbar.make(
                        contentHost,
                        "Signed out on this phone, but the server session could not be revoked. It will expire on its own.",
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
                updateSetupStatus(); refreshHome()
            }
        }
    }

    private fun confirmAccountDeletion() {
        if (authCallbackInProgress) return
        if (!account.signedIn) {
            Snackbar.make(contentHost, "Sign in before deleting an account.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (!canShowDialog()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Permanently delete account?")
            .setMessage("LiVoice will require a fresh email code. Deletion removes all sessions, quota identity, and encrypted cloud records. This phone’s partition will also be removed. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Verify and delete") { _, _ -> startAccountLogin(SessionManager.AUTH_DELETE) }
            .show()
    }

    /** Uploads local changes and downloads other devices' — no keys, just the signed-in account. */
    private fun runCloudSync(showResult: Boolean) {
        if (!account.cloudServicesAllowed || syncing) return
        syncing = true
        cloudSyncStatus?.text = "Syncing…"
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { sync.syncNow() }
            } finally {
                syncing = false
            }
            when (result) {
                is SyncResult.Success -> {
                    store.syncWarning = result.warning
                    if (showResult) {
                        Snackbar.make(
                            contentHost,
                            result.warning ?: "Synced: ${result.uploaded} sent, ${result.downloaded} received.",
                            Snackbar.LENGTH_LONG,
                        ).show()
                    }
                    updateCloudSyncStatus()
                }
                is SyncResult.Error -> {
                    cloudSyncStatus?.text = result.message
                    if (showResult) Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
                    if (account.accountStatus.state.restricted) updateSetupStatus()
                }
            }
            historySyncSwitch?.let { switch ->
                if (switch.isChecked != store.historySyncEnabled) setSwitchSilently(switch, store.historySyncEnabled)
            }
            updateRetentionSummary()
            refreshHome(); refreshHistory(); refreshDictionary()
        }
    }

    private fun updateCloudSyncStatus() {
        val last = store.lastSyncAtMs
        cloudSyncStatus?.text = when {
            !account.signedIn -> ""
            last <= 0L -> "Not synced yet on this phone"
            else -> "Last synced ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(last))}"
        }
        val warning = store.syncWarning
        cloudSyncWarning?.text = warning.orEmpty()
        cloudSyncWarning?.visibility = if (account.signedIn && !account.accountStatus.state.restricted && !warning.isNullOrBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun onHistorySyncToggled(enabled: Boolean) {
        val switch = historySyncSwitch ?: return
        if (enabled) {
            updateHistorySync(true)
            return
        }
        if (!canShowDialog()) {
            setSwitchSilently(switch, true)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop syncing history?")
            .setMessage("LiVoice will delete the dictated text stored in your account. History already on this phone and your other devices stays there; new dictations stay on the device that made them.")
            .setNegativeButton("Keep syncing") { _, _ -> setSwitchSilently(switch, true) }
            .setOnCancelListener { setSwitchSilently(switch, true) }
            .setPositiveButton("Stop and delete") { _, _ -> updateHistorySync(false) }
            .show()
    }

    private fun updateHistorySync(enabled: Boolean) {
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { sync.updateSettings(historySyncEnabled = enabled) }) {
                is AccountResult.Success -> {
                    Snackbar.make(
                        contentHost,
                        if (enabled) "History syncs to your account again." else "History is no longer stored in your account.",
                        Snackbar.LENGTH_LONG,
                    ).show()
                    if (enabled) runCloudSync(showResult = false)
                }
                is AccountResult.Error -> {
                    historySyncSwitch?.let { setSwitchSilently(it, !enabled) }
                    Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setSwitchSilently(switch: SwitchMaterial, checked: Boolean) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { _, value -> onHistorySyncToggled(value) }
    }

    private fun retentionSummary(): String = when (val days = store.historyRetentionDays) {
        null -> "Keep history until you delete it"
        365 -> "Delete dictations older than 1 year on every device"
        else -> "Delete dictations older than $days days on every device"
    }

    private fun updateRetentionSummary() {
        updateSettingSummary(retentionAction, retentionSummary())
    }

    private fun chooseRetention() {
        if (!canShowDialog()) return
        val options = listOf<Int?>(null, 30, 90, 365)
        val labels = arrayOf("Never", "After 30 days", "After 90 days", "After 1 year")
        val current = options.indexOf(store.historyRetentionDays).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Auto-delete history")
            .setSingleChoiceItems(labels, current) { dialog, index ->
                dialog.dismiss()
                val days = options[index]
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        sync.updateSettings(historyRetentionDays = days, clearRetention = days == null)
                    }
                    when (result) {
                        is AccountResult.Success -> {
                            updateRetentionSummary()
                            refreshHistory(); refreshHome()
                        }
                        is AccountResult.Error -> Snackbar.make(contentHost, result.message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAccountRestriction() {
        if (!canShowDialog()) return
        val status = account.accountStatus
        val title = if (status.state == AccountState.BANNED) "Account banned" else "Account suspended"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(restrictionSummary())
            .setNegativeButton("Close", null)
            .setPositiveButton("Contact support") { _, _ ->
                launchActivitySafely(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${status.supportEmail}")),
                    "No email app was found. Contact ${status.supportEmail}.",
                )
            }
            .show()
    }

    private fun restrictionSummary(): String {
        val status = account.accountStatus
        val until = status.suspendedUntilMs?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
        }
        return buildString {
            append(status.publicMessage ?: "This account cannot use LiVoice cloud services right now.")
            if (until != null) append("\n\nSuspended until $until.")
            append("\n\nManual typing and local data remain available. For help, contact ${status.supportEmail}.")
        }
    }

    private fun showPolicyUpdateNoticeIfNeeded() {
        if (store.acknowledgedPolicyVersion == POLICY_VERSION) return
        store.acknowledgedPolicyVersion = POLICY_VERSION
        Snackbar.make(
            contentHost,
            "Sync no longer needs a recovery key: your history and dictionary are stored encrypted in your LiVoice account. Review the updated Privacy Policy.",
            Snackbar.LENGTH_LONG,
        ).setAction("Review") { openServicePage("/privacy") }.show()
    }

    private fun openServicePage(path: String) {
        val url = "${store.workerUrl.trimEnd('/')}${if (path.startsWith('/')) path else "/$path"}"
        launchCustomTabSafely(Uri.parse(url))
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // Android 13+ silently drops the "LiVoice is listening" foreground notification
    // unless POST_NOTIFICATIONS was granted, so ask once the microphone is usable.
    private fun maybeRequestNotificationPermission() {
        if (store.notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        store.notificationPermissionRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showTermDialog(entry: DictionaryEntry? = null) {
        if (!canShowDialog()) return
        val field = input("Name or specialist term", InputType.TYPE_CLASS_TEXT).apply {
            setText(entry?.term.orEmpty())
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (entry == null) "Add dictionary term" else "Edit dictionary term")
            .setView(LinearLayout(this).apply {
                setPadding(dp(22), dp(6), dp(22), 0)
                addView(field, linear(match = true))
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        if (entry == null) repository.addManualTerm(field.text.toString())
                        else repository.renameTerm(entry, field.text.toString())
                    }
                    if (!saved) {
                        Snackbar.make(contentHost, "That term is invalid or already exists.", Snackbar.LENGTH_SHORT).show()
                    } else {
                        runCloudSync(showResult = false)
                    }
                    refreshDictionary()
                }
            }
            .show()
    }

    private fun acceptSuggestion(entry: DictionaryEntry) {
        scope.launch {
            withContext(Dispatchers.IO) { repository.acceptSuggestion(entry) }
            refreshDictionary()
            Snackbar.make(contentHost, "Added “${entry.term}” to your dictionary.", Snackbar.LENGTH_SHORT).show()
            runCloudSync(showResult = false)
        }
    }

    private fun deleteDictionaryEntry(entry: DictionaryEntry) {
        scope.launch {
            withContext(Dispatchers.IO) { repository.deleteDictionary(entry) }
            refreshDictionary()
            if (entry.isConfirmed) runCloudSync(showResult = false)
        }
    }

    private fun showHistoryDetail(record: DictationRecord) {
        if (!canShowDialog()) return
        val detail = "${formatWordCount(record.wordCount)} • ${formatDuration(record.audioDurationMs)} • ${record.asrModel}\n" +
            "Processed in ${formatProcessing(record.totalMs)}${if (record.polished) " • Polished" else ""}"
        MaterialAlertDialogBuilder(this)
            .setTitle(historyDateTime(record))
            .setMessage("${record.finalText}\n\n$detail")
            .setNegativeButton("Delete") { _, _ -> deleteHistoryWithUndo(record) }
            .setNeutralButton("Share") { _, _ ->
                val share = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, record.finalText)
                launchActivitySafely(Intent.createChooser(share, "Share dictation"), "No app is available to share.")
            }
            .setPositiveButton("Copy") { _, _ -> copyRecord(record) }
            .show()
    }

    private fun copyRecord(record: DictationRecord) {
        setClipboard("LiVoice dictation", record.finalText)
        Snackbar.make(contentHost, "Copied to clipboard.", Snackbar.LENGTH_SHORT).show()
    }

    // Dictated text and recovery keys are private; flag them so clipboard viewers
    // and synced clipboards on API 33+ exclude the content from previews.
    private fun setClipboard(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun deleteHistoryWithUndo(record: DictationRecord) {
        scope.launch {
            withContext(Dispatchers.IO) { repository.deleteHistory(record, holdWindowMs = UNDO_HOLD_MS) }
            refreshHistory()
            refreshHome()
            Snackbar.make(contentHost, "Dictation deleted.", Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.restoreHistory(record) }
                        refreshHistory()
                        refreshHome()
                        runCloudSync(showResult = false)
                    }
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION) {
                            repository.releaseHistoryDeleteHold(record)
                            runCloudSync(showResult = false)
                        }
                    }
                })
                .show()
        }
    }

    // Deletions sync with the account: the old wording promised "this phone"
    // only while the text also vanished from every other device.
    private fun syncedDeletionScope(historyOnly: Boolean): String {
        val synced = account.signedIn && (!historyOnly || store.historySyncEnabled)
        return if (synced) "this phone, your account, and your other devices" else "this phone"
    }

    private fun confirmClearHistory() = confirm(
        "Clear history?",
        "Generated text will be removed from ${syncedDeletionScope(historyOnly = true)}. Anonymous analytics totals will remain.",
        "Clear",
    ) {
        scope.launch {
            withContext(Dispatchers.IO) { repository.clearHistory() }
            refreshHistory(); refreshHome()
            runCloudSync(showResult = false)
        }
    }

    private fun confirmResetAnalytics() = confirm(
        "Reset analytics?",
        "Dictation totals, timing, WPM, and estimated usage will be removed from ${syncedDeletionScope(historyOnly = false)}. History text will remain.",
        "Reset",
    ) {
        scope.launch {
            withContext(Dispatchers.IO) { repository.resetAnalytics() }
            refreshHome()
            runCloudSync(showResult = false)
        }
    }

    private fun confirmClearAll() = confirm(
        "Clear all local data?",
        "This removes history, analytics, dictionary, and local sign-in credentials from this phone and signs it out. Copies already synced stay in your account.",
        "Clear all",
    ) {
        scope.launch {
            val wasSignedIn = account.signedIn
            val revoked = withContext(Dispatchers.IO) {
                // Revoke the server session first: once the refresh token is wiped
                // nothing on this phone could revoke it, and it stays valid for 30 days.
                val ok = if (wasSignedIn) account.logout() else true
                repository.clearEveryAccountLocalData()
                store.clearAll()
                ok
            }
            account.clearLocalSession()
            BubbleKeepAliveService.syncState(this@SetupActivity)
            BubbleHealthJobService.sync(this@SetupActivity)
            clearDetachedScreenReferences()
            screens.clear()
            contentHost.removeAllViews()
            activeTab = TAB_HOME
            showTab(TAB_HOME, animate = false)
            bottomNavigation.selectedItemId = TAB_HOME
            if (wasSignedIn && !revoked) {
                Snackbar.make(
                    contentHost,
                    "Local data cleared, but the server session could not be revoked. It will expire on its own.",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun clearDetachedScreenReferences() {
        historyJob?.cancel()
        historyJob = null
        dictionaryJob?.cancel()
        dictionaryJob = null
        metricViews.clear()
        readinessText = null
        backgroundWarningCard = null
        backgroundWarningText = null
        aiInsightsText = null
        usageTotalText = null
        usageBreakdownText = null
        recentContainer = null
        periodSelector = null
        historyAdapter = null
        historyEmpty = null
        historySearch = null
        historyRecycler = null
        dictionaryAdapter = null
        dictionaryEmpty = null
        dictionarySearch = null
        dictionaryRecycler = null
        dictionaryTabs = null
        dictionaryAdapterConfirmed = null
        microphoneStatus = null
        keyboardStatus = null
        accountStatus = null
        accountQuota = null
        accountButton = null
        accountSignInHelp = null
        accountRestrictionMessage = null
        accountSignOutAction = null
        accountDeleteAction = null
        cloudSyncStatus = null
        cloudSyncWarning = null
        cloudSyncActions = emptyList()
        historySyncSwitch = null
        retentionAction = null
        languageAction = null
        polishAction = null
        contextStatus = null
        bubbleStatus = null
        bubbleSwitch = null
        keepAliveSwitch = null
        backgroundAction = null
    }

    private fun confirm(title: String, message: String, action: String, block: () -> Unit) {
        if (!canShowDialog()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ -> block() }
            .show()
    }

    private fun metric(key: String, label: String): MaterialCardView {
        val value = TextView(this).apply {
            styleText(26f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            text = "—"
            minHeight = dp(38)
        }
        val subtitle = TextView(this).apply { styleText(12.5f, MUTED) }
        metricViews[key] = value
        metricViews["$key-subtitle"] = subtitle
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARD); strokeWidth = dp(1); setStrokeColor(BORDER)
            minimumHeight = dp(116)
            addView(LinearLayout(this@SetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(12), dp(14))
                addView(TextView(this@SetupActivity).apply {
                    text = label.uppercase(Locale.getDefault()); styleText(11.5f, MUTED)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(value, linear(match = true).apply { topMargin = dp(5); bottomMargin = dp(2) })
                addView(subtitle)
            })
        }
    }

    private fun metricRow(first: View, second: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        layoutParams = linear(match = true).apply { bottomMargin = dp(12) }
    }

    private fun setMetric(key: String, value: String, subtitle: String) {
        metricViews[key]?.let { view ->
            if (motionAllowed() && view.text != value) {
                view.animate().cancel(); view.alpha = 0.2f; view.translationY = dp(4).toFloat()
                view.text = value
                view.animate().alpha(1f).translationY(0f).setDuration(180).start()
            } else view.text = value
        }
        metricViews["$key-subtitle"]?.text = subtitle
    }

    private fun hero(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(0, dp(4), 0, dp(10))
        addView(ImageView(this@SetupActivity).apply {
            setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, linear(height = dp(52)).apply { width = dp(52) })
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SetupActivity).apply {
                text = title; styleText(24f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            addView(TextView(this@SetupActivity).apply {
                text = subtitle; styleText(13.5f, MUTED); maxLines = 3
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(13) })
    }

    private fun screenHeader(
        title: String,
        subtitle: String,
        action: String,
        secondaryAction: String? = null,
        onSecondaryAction: (() -> Unit)? = null,
        onAction: () -> Unit,
    ) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(0, dp(2), 0, dp(10))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SetupActivity).apply {
                text = title; styleText(26f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            addView(TextView(this@SetupActivity).apply {
                text = subtitle; styleText(13f, MUTED); maxLines = 3
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        if (secondaryAction != null && onSecondaryAction != null) {
            addView(headerActionButton(secondaryAction, "$secondaryAction $title", onSecondaryAction), linear().apply { marginEnd = dp(8) })
        }
        addView(headerActionButton(action, "$action $title", onAction), linear())
    }

    private fun headerActionButton(label: String, description: String, onAction: () -> Unit) = TextView(this).apply {
        text = label
        contentDescription = description
        styleText(14f, ACCENT)
        gravity = Gravity.CENTER
        minWidth = dp(68)
        minHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(ACTION, dp(20).toFloat())
        setButtonAccessibilityRole()
        setOnClickListener { onAction() }
    }

    private fun sectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(0, dp(4), 0, dp(4))
        addView(TextView(this@SetupActivity).apply {
            text = title; styleText(19f); typeface = Typeface.create("sans-serif", Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) addView(TextView(this@SetupActivity).apply {
            text = action; contentDescription = "$action $title"; styleText(14f, ACCENT); gravity = Gravity.CENTER
            minWidth = dp(76); minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setButtonAccessibilityRole()
            setOnClickListener { onAction?.invoke() }
        }, linear())
    }

    private fun card(label: String, content: View) = MaterialCardView(this).apply {
        radius = dp(19).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARD); strokeWidth = dp(1); setStrokeColor(BORDER)
        layoutParams = linear(match = true).apply { bottomMargin = dp(12) }
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(15), dp(17), dp(16))
            addView(TextView(this@SetupActivity).apply {
                text = label; styleText(11.5f, MUTED); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(9))
            })
            addView(content)
        })
    }

    private fun settingsCard(label: String, children: List<View>) = MaterialCardView(this).apply {
        radius = dp(19).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARD); strokeWidth = dp(1); setStrokeColor(BORDER)
        layoutParams = linear(match = true).apply { topMargin = dp(12) }
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@SetupActivity).apply {
                text = label; styleText(11.5f, MUTED); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(8))
            })
            children.forEachIndexed { index, child ->
                if (index > 0 && child.layoutParams == null) child.layoutParams = linear(match = true).apply { topMargin = dp(6) }
                addView(child)
            }
        })
    }

    private fun settingBlock(title: String, status: TextView, action: String, onAction: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(62)
        setPadding(0, dp(4), 0, dp(4))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SetupActivity).apply { text = title; styleText(15f) })
            addView(status)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        addView(smallAction(action, "$action $title", onAction), linear())
    }

    private fun settingAction(title: String, summary: String, action: String, onAction: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(62)
        setPadding(0, dp(4), 0, dp(4))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SetupActivity).apply { text = title; styleText(15f) })
            addView(TextView(this@SetupActivity).apply {
                text = summary; styleText(12.5f, MUTED)
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        addView(smallAction(action, "$action $title", onAction), linear())
    }

    private fun fixedSetting(title: String, value: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(0, dp(6), 0, dp(6))
        addView(
            TextView(this@SetupActivity).apply { text = title; styleText(15f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            },
        )
        addView(
            TextView(this@SetupActivity).apply { text = value; styleText(13f, MUTED); gravity = Gravity.CENTER_VERTICAL or Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { gravity = Gravity.CENTER_VERTICAL },
        )
    }

    private fun settingSwitch(title: String, summary: String, checked: Boolean, onChanged: (Boolean) -> Unit) = SwitchMaterial(this).apply {
        text = "$title\n$summary"
        textSize = 14.5f; setTextColor(Color.WHITE); isChecked = checked
        minHeight = dp(64); gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }

    private fun destructiveAction(title: String, summary: String, action: String = "Remove", onAction: () -> Unit) = settingAction(title, summary, action, onAction).apply {
        (getChildAt(1) as? TextView)?.setTextColor(ERROR)
    }

    private fun smallAction(label: String, description: String, action: () -> Unit) = TextView(this).apply {
        text = label
        contentDescription = description
        styleText(13.5f, ACCENT)
        gravity = Gravity.CENTER
        minWidth = dp(76)
        minHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(ACTION, dp(20).toFloat())
        setButtonAccessibilityRole()
        setOnClickListener { action() }
    }

    private fun segmented(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(3), dp(3), dp(3), dp(3)); background = rounded(SEGMENT, dp(16).toFloat())
        minimumHeight = dp(50)
        labels.forEachIndexed { index, label ->
            addView(TextView(this@SetupActivity).apply {
                text = label; gravity = Gravity.CENTER; styleText(if (labels.size > 3) 12.5f else 14f)
                minHeight = dp(48)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setButtonAccessibilityRole()
                setOnClickListener { onSelected(index) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        post { styleSegmented(this, selected) }
    }

    private fun styleSegmented(container: LinearLayout, selected: Int) {
        repeat(container.childCount) { index ->
            (container.getChildAt(index) as TextView).apply {
                isSelected = index == selected
                background = rounded(if (index == selected) SEGMENT_ACTIVE else Color.TRANSPARENT, dp(13).toFloat())
                setTextColor(if (index == selected) Color.WHITE else MUTED)
            }
        }
    }

    private fun recentPreview(record: DictationRecord) = MaterialCardView(this).apply {
        radius = dp(17).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARD); strokeWidth = dp(1); setStrokeColor(BORDER)
        layoutParams = linear(match = true).apply { bottomMargin = dp(9) }
        isClickable = true
        contentDescription = "Dictation from ${historyDateTime(record)}: ${record.finalText.take(60)}. Open details"
        setButtonAccessibilityRole()
        setOnClickListener { showHistoryDetail(record) }
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13))
            addView(TextView(this@SetupActivity).apply {
                text = record.finalText; styleText(15f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@SetupActivity).apply {
                text = "${historyDateTime(record)}  •  ${formatWordCount(record.wordCount)}"
                styleText(12f, MUTED)
                setPadding(0, dp(6), 0, 0)
            })
        })
    }

    private fun pageColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(15), dp(20), dp(34)); setBackgroundColor(BACKGROUND)
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true; setBackgroundColor(BACKGROUND); overScrollMode = View.OVER_SCROLL_NEVER; addView(content)
    }

    private fun searchField(hintText: String) = input(hintText, InputType.TYPE_CLASS_TEXT).apply {
        setSingleLine(true)
        compoundDrawablePadding = dp(8)
        minHeight = dp(52)
    }

    private fun input(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText; inputType = type; textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(MUTED)
        minHeight = dp(50)
        setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(INPUT, dp(14).toFloat()); maxLines = 6
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14.5f; setTextColor(Color.WHITE)
        minHeight = dp(50)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(79, 70, 123)); setOnClickListener { action() }
        layoutParams = linear(match = true).apply { topMargin = dp(7) }
    }

    private fun statusText() = TextView(this).apply { styleText(12.5f, MUTED); setPadding(0, dp(3), 0, dp(3)) }

    private fun emptyState(message: String, compact: Boolean = false) = TextView(this).apply {
        text = message; styleText(if (compact) 14f else 15f, MUTED); gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1.15f)
        setPadding(dp(24), if (compact) dp(24) else dp(70), dp(24), dp(24)); background = if (compact) rounded(CARD, dp(17).toFloat()) else null
    }

    private fun EditText.onTextChanged(action: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    })

    private fun navigationColors() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(ACCENT, MUTED),
    )

    private fun languageLabel(code: String): String =
        LANGUAGE_OPTIONS.firstOrNull { it.first == code }?.second ?: code

    private fun polishLabel(code: String): String =
        POLISH_OPTIONS.firstOrNull { it.first == code }?.second ?: code

    private fun formatWordCount(count: Int): String = if (count == 1) "1 word" else "$count words"

    private fun formatQuotaReset(resetAtMs: Long): String? {
        if (resetAtMs <= 0L) return null
        val remainingMs = resetAtMs - System.currentTimeMillis()
        if (remainingMs <= 0L) return "Resets soon"
        val totalMinutes = (remainingMs + 59_999L) / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "Resets in ${hours}h ${minutes}m"
            hours > 0L -> "Resets in ${hours}h"
            else -> "Resets in ${minutes}m"
        }
    }

    private fun statusLine(ready: Boolean, yes: String, no: String) = if (ready) "✓  $yes" else "○  $no"
    private fun formatDuration(ms: Long): String = when {
        ms < 60_000 -> "${(ms / 1_000.0).roundToInt()} sec"
        else -> "${ms / 60_000}m ${(ms / 1_000) % 60}s"
    }
    private fun formatProcessing(ms: Long): String = if (ms <= 0) "—" else if (ms < 1_000) "${ms} ms" else String.format(Locale.US, "%.1f sec", ms / 1_000.0)
    private fun formatCost(value: Double): String = if (value <= 0.0) "$0.0000" else String.format(Locale.US, "$%.4f", value)
    private fun formatNeurons(value: Double): String = if (value >= 1_000) String.format(Locale.US, "%,.0f", value) else String.format(Locale.US, "%.1f", value)

    private fun historyGroup(record: DictationRecord): String {
        val zone = runCatching { ZoneId.of(record.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val date = Instant.ofEpochMilli(record.createdAtMs).atZone(zone).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }

    private fun historyDateTime(record: DictationRecord): String {
        val zone = runCatching { ZoneId.of(record.zoneId) }.getOrDefault(ZoneId.systemDefault())
        return Instant.ofEpochMilli(record.createdAtMs).atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }

    private fun linear(match: Boolean = false, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, height)

    private fun frame(match: Boolean = false) = FrameLayout.LayoutParams(
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val TAB_HOME = 1_101
        const val TAB_HISTORY = 1_102
        const val TAB_DICTIONARY = 1_103
        const val TAB_SETTINGS = 1_104
        const val STATE_TAB = "dashboard_tab"
        const val STATE_PERIOD = "analytics_period"
        const val POLICY_VERSION = "2026-09-26-cloud-sync"
        const val UNDO_HOLD_MS = 8_000L

        val LANGUAGE_OPTIONS = listOf(
            "auto" to "Auto-detect",
            "en-IN" to "English (India)",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "hi-IN" to "Hindi (India)",
            "bn-IN" to "Bengali (India)",
            "es-ES" to "Spanish",
            "fr-FR" to "French",
            "de-DE" to "German",
            "ja-JP" to "Japanese",
            "zh-CN" to "Chinese (Simplified)",
            "ar-SA" to "Arabic",
            "pt-BR" to "Portuguese (Brazil)",
        )

        val POLISH_OPTIONS = listOf(
            "off" to "Off (raw transcript)",
            "light" to "Light (grammar & punctuation)",
            "strong" to "Strong (cleaner phrasing)",
        )

        val BACKGROUND = Color.rgb(23, 22, 27)
        val NAVIGATION = Color.rgb(29, 28, 34)
        val CARD = Color.rgb(36, 35, 41)
        val INPUT = Color.rgb(47, 46, 54)
        val BORDER = Color.rgb(55, 53, 65)
        val ACTION = Color.rgb(50, 47, 63)
        val SEGMENT = Color.rgb(33, 32, 39)
        val SEGMENT_ACTIVE = Color.rgb(73, 66, 102)
        val ACCENT = Color.rgb(174, 163, 255)
        val MUTED = Color.rgb(177, 175, 187)
        val SUCCESS = Color.rgb(124, 220, 164)
        val ERROR = Color.rgb(255, 145, 153)
    }
}
