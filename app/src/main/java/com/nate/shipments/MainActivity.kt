package com.nate.shipments

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var openId by mutableStateOf<String?>(null)
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        Tracker.createChannel(this)
        Tracker.schedule(this)
        setContent {
            ShipmentsTheme {
                App(
                    openId = openId, onOpened = { openId = null },
                    sharedText = sharedText, onSharedHandled = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_PARCEL)?.let { openId = it }
        if (intent.action == Intent.ACTION_SEND) sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    companion object {
        const val EXTRA_PARCEL = "parcel_id"
    }
}

/** Shared actions the screens call; keeps networking and error handling in one place. */
class Actions(
    private val ctx: android.content.Context,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    private val undoable: (String, () -> Unit) -> Unit,
    private val setRefreshing: (Boolean) -> Unit,
) {
    /** Archive or restore, with an Undo on the snackbar. Sets (never toggles), so repeats are harmless. */
    fun setArchived(p: Parcel, archived: Boolean) {
        edit(p.id) { it.copy(archived = archived) }
        undoable(if (archived) "Archived ${p.title}" else "Moved ${p.title} back to home") {
            edit(p.id) { it.copy(archived = !archived) }
        }
    }

    fun refresh(onlyIds: Set<String>? = null, quiet: Boolean = false, minAgeMs: Long = 0) {
        scope.launch {
            setRefreshing(true)
            try {
                Tracker.refresh(ctx, onlyIds, minAgeMs)
            } catch (e: MissingKeyException) {
                if (!quiet) toast(e.message ?: "Add your API key in Settings.")
            } catch (e: Exception) {
                if (!quiet) toast("Couldn't refresh: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setRefreshing(false)
            }
        }
    }

    fun add(number: String, name: String, courier: String?, carrierName: String?): Boolean {
        val clean = Carriers.clean(number)
        if (clean.length < 5) { toast("That doesn't look like a tracking number."); return false }
        if (Store.parcels.value.any { it.number == clean }) { toast("You're already tracking that number."); return false }
        val p = Tracker.newParcel(clean, name, courier, carrierName)
        scope.launch {
            Store.update(ctx) { listOf(p) + it }
            refresh(setOf(p.id))
        }
        return true
    }

    fun edit(id: String, change: (Parcel) -> Parcel) = scope.launch { Store.edit(ctx, id, change) }

    fun delete(p: Parcel) = scope.launch {
        Store.update(ctx) { list -> list.filterNot { it.id == p.id } }
    }

    fun changeCarrier(p: Parcel, c: Carrier?) = scope.launch {
        val code = c?.code
        if (code == p.courier) return@launch
        Store.edit(ctx, p.id) {
            it.copy(courier = code, carrierName = c?.name, events = emptyList(), status = Status.PENDING, error = null)
        }
        refresh(setOf(p.id))
    }
}

@Composable
fun App(openId: String?, onOpened: () -> Unit, sharedText: String?, onSharedHandled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val parcels by Store.parcels.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    val actions = remember {
        Actions(
            ctx.applicationContext, scope,
            toast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            undoable = { msg, undo ->
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(msg, actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) undo()
                }
            },
        ) { refreshing = it }
    }

    // Simple back stack, saved across rotation: "list", "detail:<id>", "map:<id>", "overview", "archive", "settings".
    var stackText by rememberSaveable { mutableStateOf("list") }
    val stack = stackText.split('|')
    val top = stack.last()
    fun push(s: String) { stackText = "$stackText|$s" }
    fun pop() { if (stack.size > 1) stackText = stack.dropLast(1).joinToString("|") }

    var addPrefill by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Store.load(ctx.applicationContext) }
        // On open, only re-check packages that haven't been checked in the last 30 minutes.
        if (Settings.apiKey(ctx) != null) actions.refresh(quiet = true, minAgeMs = 30 * 60_000L)
    }
    LaunchedEffect(openId) {
        if (openId != null) { stackText = "list|detail:$openId"; onOpened() }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            addPrefill = Carriers.extract(sharedText) ?: sharedText.trim().take(40)
            stackText = "list"
            onSharedHandled()
        }
    }

    // Ask for notification permission once (Android 13+).
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Settings.askedNotifPermission(ctx)) {
            Settings.setAskedNotifPermission(ctx)
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    val snackHost = @Composable { SnackbarHost(snackbar) }
    when {
        top == "settings" -> SettingsScreen(onBack = ::pop, toast = { scope.launch { snackbar.showSnackbar(it) } },
            snackHost = snackHost)
        top.startsWith("detail:") -> {
            val p = parcels.firstOrNull { it.id == top.removePrefix("detail:") }
            if (p == null) LaunchedEffect(top) { pop() }
            else DetailScreen(p, actions, refreshing, onBack = ::pop, onMap = { push("map:${p.id}") }, snackHost = snackHost)
        }
        top == "overview" -> {
            val active = parcels.filter { !it.archived && it.status != Status.DELIVERED }
            OverviewScreen(active, onBack = ::pop, onOpen = { id -> push("detail:$id") })
        }
        top == "archive" -> ArchiveScreen(parcels, actions, onOpen = { push("detail:${it.id}") }, onBack = ::pop,
            snackHost = snackHost)
        top.startsWith("map:") -> {
            val p = parcels.firstOrNull { it.id == top.removePrefix("map:") }
            if (p == null) LaunchedEffect(top) { pop() } else MapScreen(p, onBack = ::pop)
        }
        else -> ListScreen(
            parcels = parcels,
            actions = actions,
            refreshing = refreshing,
            hasKey = Settings.apiKey(ctx) != null,
            addPrefill = addPrefill,
            onPrefillUsed = { addPrefill = null },
            onOpen = { push("detail:${it.id}") },
            onSettings = { push("settings") },
            onOverview = { push("overview") },
            onArchive = { push("archive") },
            snackHost = snackHost,
        )
    }
}
