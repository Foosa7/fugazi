package com.fugazi.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fugazi.app.ai.AiSettings
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import com.fugazi.app.sync.DriveSync
import com.fugazi.app.sync.SyncWorker
import com.google.android.gms.auth.api.identity.Identity
import com.fugazi.app.ai.Reflector
import com.fugazi.app.journal.Event
import com.fugazi.app.journal.EventType
import com.fugazi.app.journal.Habit
import com.fugazi.app.journal.Heartbeat
import com.fugazi.app.journal.JournalStore
import com.fugazi.app.journal.Kind
import com.fugazi.app.journal.evalDay
import com.fugazi.app.journal.nowStamp
import com.fugazi.app.journal.pendingTriggers
import com.fugazi.app.journal.reduce
import com.fugazi.app.data.UsageRepository
import com.fugazi.app.sense.AutoMarker
import com.fugazi.app.sense.MediaLog
import com.fugazi.app.sense.Radar
import com.fugazi.app.sense.SignalStore
import com.fugazi.app.journal.ThoughtStore
import com.fugazi.app.journal.lastStatus
import com.fugazi.app.journal.runChecks
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import com.fugazi.app.sense.SenseSettings
import com.fugazi.app.sense.Sleep
import com.fugazi.app.ui.theme.FugaziTheme
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Returning from the Health Connect dialog resumes the activity, which re-reads access.
    private val requestHealth =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    // Google's "let fugazi use Drive" screen.
    private val requestDrive =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val r = runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data) }.getOrNull()
            if (r != null) lifecycleScope.launch { DriveSync.onAuthorized(this@MainActivity, r) }
        }

    fun connectDrive() {
        lifecycleScope.launch {
            runCatching { DriveSync.connect(this@MainActivity) }
                .onSuccess { pi -> pi?.let { requestDrive.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
                .onFailure { DriveSync.refreshStatus(this@MainActivity) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Whatever you just wrote goes to Drive within a minute of leaving.
        SyncWorker.soon(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FugaziTheme {
                App(
                    startTab = if (intent.getBooleanExtra(Reflector.EXTRA_OPEN_REFLECT, false)) 2 else 0,
                    onAskNotifications = ::askNotifications,
                    onAskHealth = { requestHealth.launch(Sleep.wantedPermissions(this)) },
                    onConnectDrive = ::connectDrive,
                )
            }
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }
}

/** Which screen is up. No navigation library — there are three screens. */
private sealed interface Screen {
    data object Home : Screen
    data class Edit(val habitId: String?) : Screen
    data object Settings : Screen
}

@Composable
private fun App(startTab: Int, onAskNotifications: () -> Unit, onAskHealth: () -> Unit, onConnectDrive: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val habits by JournalStore.habits.collectAsState()
    val events by JournalStore.events.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val resumes = rememberResumeCount()
    val driveStatus by DriveSync.status.collectAsState()
    LaunchedEffect(resumes) { DriveSync.refreshStatus(context) }

    // Opening the app runs the same check the background worker does, so a check-in is
    // never only as fresh as the last time Android let the worker run. It deliberately
    // doesn't touch the heartbeat — that has to show whether the *background* is alive.
    var autoStatus by remember { mutableStateOf<AutoMarker.Status?>(null) }
    var sleepSource by remember { mutableStateOf(SenseSettings.source(context)) }
    var granted by remember { mutableStateOf(emptySet<String>()) }
    var radarMessage by remember { mutableStateOf("") }
    LaunchedEffect(resumes, sleepSource) {
        granted = Sleep.granted(context)
        radarMessage = Radar.message(context)
        runChecks(context)
        autoStatus = lastStatus
    }

    val today = LocalDate.now()
    val active = habits.filter { !it.archived }
    val states = active.map { reduce(it, events, today) }
    val byId = habits.associateBy { it.id } + (Radar.ID to Radar.habit(radarMessage))
    val pending = pendingTriggers(events, today).mapNotNull { e -> byId[e.habit]?.takeIf { !it.archived }?.let { it to e } }
    val health = remember(resumes, events.size) {
        Health(
            lastCheckMs = Heartbeat.last(context),
            batteryRestricted = !context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            notificationsOff = !NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }

    fun log(e: Event) = scope.launch { JournalStore.append(e) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (screen == Screen.Home) {
                NavigationBar {
                    listOf("Habits" to "✓", "Thoughts" to "✎", "Reflect" to "✦").forEachIndexed { i, (label, glyph) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(glyph) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Consumed so the Thoughts page, which pads for the keyboard, doesn't also count the nav bar.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (val s = screen) {
                Screen.Home -> if (tab == 1) ThoughtsScreen()
                else if (tab == 2) ReflectScreen(onSettings = { screen = Screen.Settings })
                else HomeScreen(
                    states = states,
                    pending = pending,
                    today = today,
                    health = health,
                    onToggle = { h, d ->
                        val st = states.first { it.habit.id == h.id }
                        val type = when (h.kind) {
                            Kind.DO, Kind.STATE -> if (d in st.done) EventType.UNDONE else EventType.DONE
                            Kind.AVOID -> if (d in st.slips) EventType.UNSLIP else EventType.SLIP
                        }
                        log(Event(t = nowStamp(), habit = h.id, type = type, date = d.toString()))
                        // The small reward for doing it: your own reason, said back to you.
                        if (type == EventType.DONE && h.why.isNotBlank()) {
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("${h.name} ✓  ${h.why}")
                            }
                        }
                    },
                    onFelt = { h, d, tags, note ->
                        log(Event(t = nowStamp(), habit = h.id, type = EventType.DONE, date = d.toString(), text = note.ifBlank { null }, tags = tags))
                    },
                    onSkip = { h, d, reason ->
                        log(Event(t = nowStamp(), habit = h.id, type = EventType.SKIP, date = d.toString(), text = reason))
                    },
                    onAnswer = { e, text ->
                        log(Event(t = nowStamp(), habit = e.habit, type = EventType.ANSWER, date = today.toString(), rung = e.rung, text = text))
                    },
                    onDismiss = { e ->
                        log(Event(t = nowStamp(), habit = e.habit, type = EventType.DISMISS, date = today.toString(), rung = e.rung))
                    },
                    onOpen = { h -> screen = Screen.Edit(h?.id) },
                    onSettings = { screen = Screen.Settings },
                    onExport = {
                        share(
                            context,
                            JournalStore.exportText() +
                                "## signals.jsonl\n" + SignalStore.exportText() +
                                "## thoughts\n" + ThoughtStore.exportText(),
                        )
                    },
                    onFixBattery = { requestUnrestricted(context) },
                    onFixNotifications = onAskNotifications,
                )

                Screen.Settings -> {
                    BackHandler { screen = Screen.Home }
                    SettingsScreen(
                        source = sleepSource,
                        sleepAccess = Sleep.READ in granted,
                        backgroundAccess = if (Sleep.backgroundSupported(context)) Sleep.READ_BACKGROUND in granted else null,
                        usageAccess = UsageRepository(context).hasUsageAccess(),
                        musicAccess = MediaLog.hasAccess(context),
                        onOpenMusicAccess = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        status = autoStatus,
                        onSource = { SenseSettings.setSource(context, it); sleepSource = it },
                        onConnect = onAskHealth,
                        onOpenHealthConnect = { openHealthConnect(context) },
                        onOpenUsageAccess = {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        aiKey = AiSettings.key(context),
                        aiDaily = AiSettings.daily(context),
                        onAiKey = { AiSettings.setKey(context, it) },
                        onAiDaily = { AiSettings.setDaily(context, it) },
                        aiModel = AiSettings.model(context),
                        aiEffort = AiSettings.effort(context),
                        onAiModel = { AiSettings.setModel(context, it) },
                        onAiEffort = { AiSettings.setEffort(context, it) },
                        drive = driveStatus,
                        onDriveConnect = onConnectDrive,
                        onDriveSyncNow = { scope.launch { DriveSync.sync(context) } },
                        onDriveKeepPhone = { scope.launch { DriveSync.overwriteBackup(context) } },
                        onDriveRestore = {
                            scope.launch {
                                DriveSync.restore(context).onSuccess { restartApp(context) }
                            }
                        },
                        onDriveDisconnect = { DriveSync.disconnect(context) },
                        onBack = { screen = Screen.Home },
                    )
                }

                is Screen.Edit -> {
                    BackHandler { screen = Screen.Home }
                    val existing = s.habitId?.let(byId::get)
                    HabitEditor(
                        existing = existing,
                        history = events.filter {
                            it.habit == s.habitId && !it.text.isNullOrBlank() &&
                                (it.type == EventType.ANSWER || it.type == EventType.SKIP)
                        }.reversed(),
                        newId = JournalStore::newId,
                        onSave = { h ->
                            scope.launch { JournalStore.saveHabit(h) }
                            screen = Screen.Home
                        },
                        onArchive = { h ->
                            scope.launch { JournalStore.saveHabit(h.copy(archived = true)) }
                            screen = Screen.Home
                        },
                        onBack = { screen = Screen.Home },
                    )
                }
            }
        }
    }
}

/** Bumps every time the activity resumes, so permission/heartbeat state is re-read. */
@Composable
private fun rememberResumeCount(): Int {
    var n by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) n++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return n
}

/**
 * After a restore the journal on disk is new, but the stores already loaded the old one.
 * Starting over is the simplest way to be sure nothing shows a stale copy.
 */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

private fun openHealthConnect(context: Context) {
    val action = if (Build.VERSION.SDK_INT >= 34) "android.health.connect.action.HEALTH_HOME_SETTINGS"
    else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Export journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@SuppressLint("BatteryLife") // Personal app, not on Play; staying alive is the whole job.
private fun requestUnrestricted(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
