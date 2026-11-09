package com.wall.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class WallAppItem(
    val label: String,
    val packageName: String,
    val asset: SprayedIconAsset
)

sealed interface WallGridSlot {
    data class SingleApp(val app: WallAppItem) : WallGridSlot
    data class WheatpasteFolder(val name: String, val apps: List<WallAppItem>) : WallGridSlot
}

class MainActivity : ComponentActivity() {

    private lateinit var repository: WallRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        repository = WallRepository(applicationContext)

        // Intercept Back button on HOME launcher so it closes overlays instead of exiting the wall
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Handled inside Compose overlay states
            }
        })

        setContent {
            WallLauncherSurface(repository = repository)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun WallLauncherSurface(repository: WallRepository) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()

    val settings by repository.settingsFlow.collectAsState(initial = WallSettings())
    val liveNotificationDrips by WallNotificationListener.activeDripPackages.collectAsState()

    // Cached concrete wall & turquoise rat stencil (synthesized once)
    val concreteWallBitmap = remember { WallEngine.getOrGenerateConcreteWall() }
    val ratStencilBitmap = remember { WallEngine.getOrGenerateTurquoiseRatStencil() }

    // Installed apps list
    var installedApps by remember { mutableStateOf<List<WallAppItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    // Passive system telemetry & clock (updated via ACTION_TIME_TICK + lifecycle resume, zero polling!)
    var currentTimeText by remember { mutableStateOf(formatWallClock()) }
    var currentDateText by remember { mutableStateOf(formatWallDate()) }
    var isNightLampMode by remember { mutableStateOf(WallEngine.isNightSodiumLampHour()) }
    var telemetry by remember {
        mutableStateOf(
            WallTelemetry(
                batteryPercent = 100,
                isCharging = false,
                freeRamMb = 4096,
                totalRamMb = 8192,
                termuxInstalled = false,
                termuxStatusText = "SCANNING...",
                launcherHeapMb = 24
            )
        )
    }

    // Active UI Overlays (All rendered directly as physical layers on the wall)
    var searchQuery by remember { mutableStateOf("") }
    var selectedMenuApp by remember { mutableStateOf<WallAppItem?>(null) }
    var activeWheatpasteFolder by remember { mutableStateOf<String?>(null) }
    var showSettingsStencil by remember { mutableStateOf(false) }
    var showTermuxSurvivalGuide by remember { mutableStateOf(false) }
    var folderAssignApp by remember { mutableStateOf<WallAppItem?>(null) }
    var scrubbingPackage by remember { mutableStateOf<String?>(null) }
    val scrubProgress = remember { Animatable(0f) }

    // Stencil Peel Drag State
    var peeledApp by remember { mutableStateOf<WallAppItem?>(null) }
    var peeledOriginSlotKey by remember { mutableStateOf<String?>(null) }
    var dragPointerOffset by remember { mutableStateOf(Offset.Zero) }
    val slotBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    var dockBoundsRect by remember { mutableStateOf<Rect?>(null) }

    // Load apps & pre-render sprayed stencil icons on background thread
    val reloadApps: () -> Unit = {
        scope.launch {
            val loaded = withContext(Dispatchers.Default) {
                val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val activities = launcherApps.getActivityList(null, Process.myUserHandle())
                val pm = context.packageManager

                activities
                    .asSequence()
                    .filter { it.applicationInfo.packageName != context.packageName }
                    .distinctBy { it.applicationInfo.packageName }
                    .map { info ->
                        val pkg = info.applicationInfo.packageName
                        val label = info.label?.toString() ?: pkg
                        val drawable = info.getIcon(0) ?: pm.defaultActivityIcon
                        val sprayedAsset = WallEngine.getOrRenderSprayedIcon(pkg, drawable)
                        WallAppItem(
                            label = label,
                            packageName = pkg,
                            asset = sprayedAsset
                        )
                    }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
                    .toList()
            }
            installedApps = loaded
            isLoadingApps = false

            // Auto-pin Termux to the Curb Dock if installed and dock is uninitialized
            val hasTermux = loaded.any { it.packageName == TermuxCare.TERMUX_PACKAGE }
            if (settings.dockPackages.isEmpty() && loaded.isNotEmpty()) {
                val defaultDock = mutableListOf<String>()
                if (hasTermux) defaultDock.add(TermuxCare.TERMUX_PACKAGE)
                loaded.take(6).forEach { app ->
                    if (defaultDock.size < 5 && app.packageName !in defaultDock) {
                        defaultDock.add(app.packageName)
                    }
                }
                repository.updateDockPackages(defaultDock)
            } else if (hasTermux && TermuxCare.TERMUX_PACKAGE !in settings.dockPackages && settings.dockPackages.size < 6) {
                repository.updateDockPackages(listOf(TermuxCare.TERMUX_PACKAGE) + settings.dockPackages)
            }

            telemetry = TermuxCare.readPassiveTelemetry(context, hasTermux)
        }
    }

    LaunchedEffect(Unit) {
        reloadApps()
    }

    // Listen to system clock ticks & package installs/uninstalls passively (Zero background polling)
    DisposableEffect(context, installedApps) {
        val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                currentTimeText = formatWallClock()
                currentDateText = formatWallDate()
                isNightLampMode = WallEngine.isNightSodiumLampHour()
                val hasTermux = installedApps.any { it.packageName == TermuxCare.TERMUX_PACKAGE }
                telemetry = TermuxCare.readPassiveTelemetry(context, hasTermux)
            }
        }
        val pkgReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                reloadApps()
            }
        }

        context.registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        context.registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })

        onDispose {
            try {
                context.unregisterReceiver(timeReceiver)
                context.unregisterReceiver(pkgReceiver)
            } catch (_: Exception) {
            }
        }
    }

    // Filter visible apps, folders, and curb dock items
    val appByPackage = remember(installedApps) {
        installedApps.associateBy { it.packageName }
    }

    val folderMemberPackages = remember(settings.folders) {
        settings.folders.values.flatten().toSet()
    }

    val visibleGridSlots = remember(installedApps, settings, searchQuery, folderMemberPackages) {
        val slots = mutableListOf<WallGridSlot>()

        // 1. Add Wheatpaste Folders first (if matching search)
        settings.folders.forEach { (folderName, pkgList) ->
            val memberApps = pkgList.mapNotNull { appByPackage[it] }
                .filter { it.packageName !in settings.hiddenPackages }
            if (memberApps.isNotEmpty()) {
                val matchesSearch = searchQuery.isBlank() ||
                        folderName.contains(searchQuery, ignoreCase = true) ||
                        memberApps.any { it.label.contains(searchQuery, ignoreCase = true) }
                if (matchesSearch) {
                    slots.add(WallGridSlot.WheatpasteFolder(folderName, memberApps))
                }
            }
        }

        // 2. Add individual stencil apps
        installedApps.forEach { app ->
            val isHidden = app.packageName in settings.hiddenPackages
            val isInFolder = app.packageName in folderMemberPackages && searchQuery.isBlank()
            val matchesSearch = searchQuery.isBlank() ||
                    app.label.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            if (!isHidden && !isInFolder && matchesSearch) {
                slots.add(WallGridSlot.SingleApp(app))
            }
        }

        // Add blank wall space so the user can always experience an empty concrete bay & Easter egg
        slots
    }

    val dockApps = remember(installedApps, settings.dockPackages, settings.hiddenPackages) {
        settings.dockPackages
            .mapNotNull { appByPackage[it] }
            .filter { it.packageName !in settings.hiddenPackages }
    }

    // Calculate responsive grid geometry for Mi Pad 5 Pro (Landscape vs Portrait)
    val columns = if (isLandscape) settings.columnCount else max(4, settings.columnCount - 2)
    val rows = if (isLandscape) 3 else 5
    val itemsPerPage = columns * rows
    val populatedPages = max(1, ceil(visibleGridSlots.size.toFloat() / itemsPerPage).toInt())
    // Always include 1 intentionally empty concrete wall section at the end ("Empty page = empty wall")
    val totalWallSections = populatedPages + 1

    val pagerState = rememberPagerState(pageCount = { totalWallSections })

    // Launches an app or handles Termux care tracking
    val launchApp: (WallAppItem) -> Unit = { app ->
        if (app.packageName == TermuxCare.TERMUX_PACKAGE) {
            TermuxCare.launchTermuxLatestSession(context)
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (intent != null) {
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
        val hasTermux = installedApps.any { it.packageName == TermuxCare.TERMUX_PACKAGE }
        telemetry = TermuxCare.readPassiveTelemetry(context, hasTermux)
    }

    // Triggers the "Painted Over / Scrubbed Out" uninstall animation before invoking system delete
    val triggerSprayOverUninstall: (WallAppItem) -> Unit = { app ->
        selectedMenuApp = null
        scope.launch {
            scrubbingPackage = app.packageName
            scrubProgress.snapTo(0f)
            if (settings.reduceMotion) {
                scrubProgress.snapTo(1f)
            } else {
                scrubProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 520, easing = LinearEasing)
                )
            }
            delay(120)
            scrubbingPackage = null
            try {
                val deleteIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:${app.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(deleteIntent)
            } catch (_: Exception) {
            }
        }
    }

    // Physical Keyboard handler for Mi Pad 5 Pro Magnetic Keyboard
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> {
                            when {
                                selectedMenuApp != null -> selectedMenuApp = null
                                activeWheatpasteFolder != null -> activeWheatpasteFolder = null
                                showSettingsStencil -> showSettingsStencil = false
                                showTermuxSurvivalGuide -> showTermuxSurvivalGuide = false
                                folderAssignApp != null -> folderAssignApp = null
                                searchQuery.isNotEmpty() -> searchQuery = ""
                            }
                            true
                        }
                        Key.Slash -> {
                            // Quick '/' shortcut on physical keyboard opens Termux immediately
                            if (searchQuery.isEmpty()) {
                                TermuxCare.launchTermuxLatestSession(context)
                                true
                            } else false
                        }
                        Key.Enter -> {
                            val firstSlot = visibleGridSlots.firstOrNull()
                            if (firstSlot is WallGridSlot.SingleApp) {
                                launchApp(firstSlot.app)
                                true
                            } else false
                        }
                        Key.Backspace -> {
                            if (searchQuery.isNotEmpty()) {
                                searchQuery = searchQuery.dropLast(1)
                                true
                            } else false
                        }
                        else -> {
                            val cp = event.utf16CodePoint
                            if (cp != 0 && (Character.isLetterOrDigit(cp) || cp == ' '.code)) {
                                searchQuery += cp.toChar()
                                true
                            } else false
                        }
                    }
                } else false
            }
    ) {
        // =====================================================================
        // LAYER 1: CONTINUOUS SUN-BLEACHED CONCRETE WALL + TIME-OF-DAY FILTER
        // =====================================================================
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Cheap GPU color filter for Day (Sun-bleached) vs Evening/Night (Sodium Streetlight Amber)
            val wallColorFilter = if (isNightLampMode) {
                ColorFilter.lighting(
                    multiply = Color(0xFFF3D5B0),
                    add = Color(0xFF140900)
                )
            } else {
                null
            }

            drawImage(
                image = concreteWallBitmap,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
                colorFilter = wallColorFilter
            )

            // Fixed directional sunlight/shadow vignette from Top-Left corner
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0x18FFFDF7),
                        Color.Transparent,
                        Color(0x382B2620)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                size = size
            )
        }

        // =====================================================================
        // LAYER 2: MAIN ARCHITECTURAL WALL SECTIONS + CURB DOCK
        // =====================================================================
        Column(modifier = Modifier.fillMaxSize()) {

            // TOP WALL HEADER ZONE (Stencil Clock + Indigo Telemetry + Masking Tape Search)
            WallTopHeaderSection(
                isLandscape = isLandscape,
                timeText = currentTimeText,
                dateText = currentDateText,
                isNightMode = isNightLampMode,
                telemetry = telemetry,
                showStatusStencil = settings.showStatusStencil,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onClearSearch = { searchQuery = "" },
                onOpenSettings = { showSettingsStencil = true },
                onOpenTermuxGuide = { showTermuxSurvivalGuide = true }
            )

            // WALK-ALONG-THE-WALL PAGER (No floating cards; separated by concrete pillar shadows)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = 1
                ) { pageIndex ->
                    val pageStart = pageIndex * itemsPerPage
                    val pageSlots = if (pageStart < visibleGridSlots.size) {
                        visibleGridSlots.subList(
                            pageStart,
                            minOf(pageStart + itemsPerPage, visibleGridSlots.size)
                        )
                    } else {
                        emptyList()
                    }

                    WallConcreteBayPage(
                        pageIndex = pageIndex,
                        totalPages = totalWallSections,
                        slots = pageSlots,
                        columns = columns,
                        rows = rows,
                        showLabels = settings.showAppLabels,
                        activeDripPackages = liveNotificationDrips + settings.demoRedDrips,
                        ghostRemnants = settings.ghostRemnants,
                        appByPackage = appByPackage,
                        peeledApp = peeledApp,
                        scrubbingPackage = scrubbingPackage,
                        scrubProgress = scrubProgress.value,
                        ratStencilBitmap = ratStencilBitmap,
                        onLaunchApp = launchApp,
                        onOpenFolder = { folderName -> activeWheatpasteFolder = folderName },
                        onLongPressApp = { app -> selectedMenuApp = app },
                        onRegisterSlotBounds = { slotKey, rect -> slotBoundsMap[slotKey] = rect },
                        onStartStencilPeel = { app, slotKey, startPos ->
                            peeledApp = app
                            peeledOriginSlotKey = slotKey
                            dragPointerOffset = startPos
                            selectedMenuApp = app
                        },
                        onUpdateStencilPeel = { delta ->
                            dragPointerOffset += delta
                        },
                        onEndStencilPeel = {
                            val droppedApp = peeledApp
                            val originKey = peeledOriginSlotKey
                            val dropPoint = dragPointerOffset
                            if (droppedApp != null && originKey != null) {
                                // Record ghost stencil stain at origin ("The Wall Remembers")
                                scope.launch {
                                    repository.recordGhostRemnant(originKey, droppedApp.packageName)
                                }

                                // Check if dropped onto the Curb Dock
                                if (dockBoundsRect?.contains(dropPoint) == true) {
                                    scope.launch {
                                        repository.updateDockPackages(settings.dockPackages + droppedApp.packageName)
                                    }
                                    selectedMenuApp = null
                                } else {
                                    // Check if dropped onto another icon/folder to create/merge a Wheatpaste Folder
                                    val targetEntry = slotBoundsMap.entries.firstOrNull { (key, rect) ->
                                        key != originKey && rect.contains(dropPoint)
                                    }
                                    if (targetEntry != null) {
                                        val targetKey = targetEntry.key
                                        if (targetKey.startsWith("folder:")) {
                                            val folderName = targetKey.removePrefix("folder:")
                                            val updated = settings.folders.toMutableMap()
                                            val currentList = updated[folderName].orEmpty()
                                            updated[folderName] = (currentList + droppedApp.packageName).distinct()
                                            scope.launch { repository.saveFolders(updated) }
                                            selectedMenuApp = null
                                        } else if (targetKey.startsWith("app:")) {
                                            val targetPkg = targetKey.removePrefix("app:")
                                            if (targetPkg != droppedApp.packageName) {
                                                val updated = settings.folders.toMutableMap()
                                                val folderName = "PASTE #${updated.size + 1}"
                                                updated[folderName] = listOf(targetPkg, droppedApp.packageName)
                                                scope.launch { repository.saveFolders(updated) }
                                                selectedMenuApp = null
                                            }
                                        }
                                    }
                                }
                            }
                            peeledApp = null
                            peeledOriginSlotKey = null
                        },
                        onTapTurquoiseRat = {
                            TermuxCare.launchTermuxLatestSession(context)
                        }
                    )
                }
            }

            // BOTTOM STREET CURB DOCK (Sidewalk edge below the wall)
            WallStreetCurbDock(
                dockApps = dockApps,
                activeDripPackages = liveNotificationDrips + settings.demoRedDrips,
                showLabels = settings.showAppLabels,
                currentPage = pagerState.currentPage,
                totalPages = totalWallSections,
                onLaunchApp = launchApp,
                onLongPressApp = { app -> selectedMenuApp = app },
                onDockPositioned = { rect -> dockBoundsRect = rect }
            )
        }

        // =====================================================================
        // LAYER 3: LIFTED STENCIL PEEL OVERLAY DURING DRAG
        // =====================================================================
        peeledApp?.let { lifted ->
            val density = LocalDensity.current
            val iconPx = with(density) { 84.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragPointerOffset.x - iconPx / 2f).roundToInt(),
                            (dragPointerOffset.y - iconPx / 2f).roundToInt()
                        )
                    }
                    .size(92.dp)
                    .scale(1.16f)
            ) {
                // Separated, softer cast shadow on the wall below the peeled stencil
                Image(
                    bitmap = lifted.asset.normalBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = 10.dp, y = 14.dp),
                    alpha = 0.32f,
                    colorFilter = ColorFilter.tint(WallPalette.ConcreteDeepShadow, BlendMode.SrcIn)
                )
                // Magenta stencil registration frame
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.5.dp, WallPalette.Magenta)
                        .padding(4.dp)
                ) {
                    Image(
                        bitmap = lifted.asset.normalBitmap,
                        contentDescription = lifted.label,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // =====================================================================
        // LAYER 4: WHEATPASTE KRAFT PAPER FOLDER OVERLAY
        // =====================================================================
        activeWheatpasteFolder?.let { folderName ->
            val memberPkgs = settings.folders[folderName].orEmpty()
            val memberApps = memberPkgs.mapNotNull { appByPackage[it] }
            WheatpasteFolderSheet(
                folderName = folderName,
                apps = memberApps,
                onDismiss = { activeWheatpasteFolder = null },
                onLaunchApp = { app ->
                    activeWheatpasteFolder = null
                    launchApp(app)
                },
                onPeelOutApp = { app ->
                    scope.launch {
                        val updated = settings.folders.toMutableMap()
                        val remaining = updated[folderName].orEmpty().filter { it != app.packageName }
                        if (remaining.isEmpty()) {
                            updated.remove(folderName)
                            activeWheatpasteFolder = null
                        } else {
                            updated[folderName] = remaining
                        }
                        repository.saveFolders(updated)
                    }
                }
            )
        }

        // =====================================================================
        // LAYER 5: LONG-PRESS STENCIL TEMPLATE SHORTCUT MENU
        // =====================================================================
        if (selectedMenuApp != null && peeledApp == null) {
            val targetApp = selectedMenuApp!!
            val isPinned = targetApp.packageName in settings.dockPackages
            val hasRedDrip = targetApp.packageName in (liveNotificationDrips + settings.demoRedDrips)

            StencilTemplateActionOverlay(
                app = targetApp,
                isPinnedToCurb = isPinned,
                hasRedDrip = hasRedDrip,
                onDismiss = { selectedMenuApp = null },
                onToggleCurbPin = {
                    scope.launch {
                        val updated = if (isPinned) {
                            settings.dockPackages.filter { it != targetApp.packageName }
                        } else {
                            (settings.dockPackages + targetApp.packageName).distinct().take(7)
                        }
                        repository.updateDockPackages(updated)
                    }
                    selectedMenuApp = null
                },
                onAssignWheatpasteFolder = {
                    folderAssignApp = targetApp
                    selectedMenuApp = null
                },
                onToggleRedDrip = {
                    scope.launch { repository.toggleRedDrip(targetApp.packageName) }
                    selectedMenuApp = null
                },
                onHideStencil = {
                    scope.launch { repository.toggleHiddenPackage(targetApp.packageName) }
                    selectedMenuApp = null
                },
                onScrubUninstall = {
                    triggerSprayOverUninstall(targetApp)
                },
                onTermuxLatestSession = {
                    selectedMenuApp = null
                    TermuxCare.launchTermuxLatestSession(context)
                },
                onTermuxNewWindow = {
                    selectedMenuApp = null
                    TermuxCare.launchTermuxNewWindow(context, newTaskWindow = true)
                },
                onOpenSystemAppInfo = {
                    selectedMenuApp = null
                    TermuxCare.openAppDetailsSettings(context, targetApp.packageName)
                }
            )
        }

        // =====================================================================
        // LAYER 6: WHEATPASTE FOLDER ASSIGNMENT OVERLAY
        // =====================================================================
        folderAssignApp?.let { appToPaste ->
            WheatpasteAssignDialog(
                app = appToPaste,
                existingFolders = settings.folders.keys.toList(),
                onDismiss = { folderAssignApp = null },
                onSelectFolder = { chosenName ->
                    scope.launch {
                        val updated = settings.folders.toMutableMap()
                        val list = updated[chosenName].orEmpty()
                        updated[chosenName] = (list + appToPaste.packageName).distinct()
                        repository.saveFolders(updated)
                    }
                    folderAssignApp = null
                }
            )
        }

        // =====================================================================
        // LAYER 7: STRICT 5-ITEM STENCIL SETTINGS SHEET
        // =====================================================================
        if (showSettingsStencil) {
            WallSettingsStencilSheet(
                settings = settings,
                installedApps = installedApps,
                onDismiss = { showSettingsStencil = false },
                onSetReduceMotion = { scope.launch { repository.setReduceMotion(it) } },
                onSetColumns = { scope.launch { repository.setColumnCount(it) } },
                onSetShowStatus = { scope.launch { repository.setShowStatusStencil(it) } },
                onSetShowLabels = { scope.launch { repository.setShowAppLabels(it) } },
                onToggleHiddenApp = { pkg -> scope.launch { repository.toggleHiddenPackage(pkg) } },
                onClearAllHidden = { scope.launch { repository.clearHiddenPackages() } },
                onSetDefaultLauncher = { TermuxCare.openDefaultLauncherSettings(context) },
                onEnableNotificationDrips = { TermuxCare.openNotificationListenerSettings(context) },
                onOpenTermuxCareGuide = {
                    showSettingsStencil = false
                    showTermuxSurvivalGuide = true
                }
            )
        }

        // =====================================================================
        // LAYER 8: TERMUX & MI PAD 5 PRO SURVIVAL GUIDE (1-PAGE HUMAN GUIDE)
        // =====================================================================
        if (showTermuxSurvivalGuide) {
            TermuxSurvivalGuideSheet(
                telemetry = telemetry,
                onDismiss = { showTermuxSurvivalGuide = false },
                onOpenBatterySettings = { TermuxCare.openBatteryOptimizationSettings(context) },
                onOpenTermuxAppInfo = {
                    TermuxCare.openAppDetailsSettings(context, TermuxCare.TERMUX_PACKAGE)
                },
                onOpenMiuiHomeSettings = { TermuxCare.openDefaultLauncherSettings(context) }
            )
        }
    }
}

/**
 * Top section of the concrete wall:
 * - Zone Color Rule strictly respected:
 *   1 Primary Accent: Mustard Yellow (#D9A227) Stencil Clock
 *   1 Secondary Accent: Indigo (#2B4C8C) Telemetry Stencil on concrete ridge
 * - Plus physical Cream Masking Tape search strip with torn edges & marker handwriting.
 */
@Composable
fun WallTopHeaderSection(
    isLandscape: Boolean,
    timeText: String,
    dateText: String,
    isNightMode: Boolean,
    telemetry: WallTelemetry,
    showStatusStencil: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTermuxGuide: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isLandscape) 20.dp else 36.dp,
                start = 24.dp,
                end = 24.dp,
                bottom = 8.dp
            )
    ) {
        if (isLandscape) {
            // Wide Tablet Architectural Header for Mi Pad 5 Pro (2560x1600 Landscape)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Mustard Stencil Clock + Date
                StencilClockSign(
                    timeText = timeText,
                    dateText = dateText,
                    isNightMode = isNightMode
                )

                // Masking Tape Search Bar in the center-right of the wall
                MaskingTapeSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    onClear = onClearSearch,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 28.dp)
                        .widthIn(max = 520.dp)
                )

                // Indigo Telemetry Stencil & Stencil Wall Controls
                Column(horizontalAlignment = Alignment.End) {
                    if (showStatusStencil) {
                        IndigoTelemetryStencilStrip(
                            telemetry = telemetry,
                            onClickTermuxHelp = onOpenTermuxGuide
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StencilActionButton(
                            text = "[ TERMUX CARE ]",
                            tint = WallPalette.Indigo,
                            onClick = onOpenTermuxGuide
                        )
                        StencilActionButton(
                            text = "[ WALL CONFIG ]",
                            tint = WallPalette.MarkerInk,
                            onClick = onOpenSettings
                        )
                    }
                }
            }
        } else {
            // Portrait Tablet Header (1600x2560 Portrait)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                StencilClockSign(
                    timeText = timeText,
                    dateText = dateText,
                    isNightMode = isNightMode
                )
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StencilActionButton(
                            text = "[ TERMUX CARE ]",
                            tint = WallPalette.Indigo,
                            onClick = onOpenTermuxGuide
                        )
                        StencilActionButton(
                            text = "[ WALL CONFIG ]",
                            tint = WallPalette.MarkerInk,
                            onClick = onOpenSettings
                        )
                    }
                    if (showStatusStencil) {
                        Spacer(modifier = Modifier.height(8.dp))
                        IndigoTelemetryStencilStrip(
                            telemetry = telemetry,
                            onClickTermuxHelp = onOpenTermuxGuide
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            MaskingTapeSearchBar(
                query = searchQuery,
                onQueryChange = onSearchChange,
                onClear = onClearSearch,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Mustard Yellow (#D9A227) Stencil Clock sprayed directly onto the plaster
 * with physical stencil cutout bridges across the numerals.
 */
@Composable
fun StencilClockSign(
    timeText: String,
    dateText: String,
    isNightMode: Boolean
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.semantics {
            contentDescription = "Wall Stencil Clock $timeText, $dateText"
        }
    ) {
        Box {
            // Directional concrete shadow under the stencil numerals
            Text(
                text = timeText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 46.sp,
                    letterSpacing = 3.sp,
                    color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.45f)
                ),
                modifier = Modifier.offset(x = 2.5.dp, y = 3.dp)
            )
            // Mustard Yellow Stencil Spray
            Text(
                text = timeText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 46.sp,
                    letterSpacing = 3.sp,
                    color = WallPalette.MustardYellow.copy(alpha = 0.92f)
                )
            )
            // Physical stencil bridge cutouts across the digits
            Canvas(modifier = Modifier.matchParentSize()) {
                val bridgeY1 = size.height * 0.36f
                val bridgeY2 = size.height * 0.68f
                drawLine(
                    color = WallPalette.ConcreteBase.copy(alpha = 0.72f),
                    start = Offset(0f, bridgeY1),
                    end = Offset(size.width, bridgeY1),
                    strokeWidth = 2.4f
                )
                drawLine(
                    color = WallPalette.ConcreteBase.copy(alpha = 0.55f),
                    start = Offset(0f, bridgeY2),
                    end = Offset(size.width, bridgeY2),
                    strokeWidth = 1.8f
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.padding(bottom = 7.dp)) {
            Text(
                text = dateText.uppercase(Locale.US),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 1.5.sp,
                    color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.85f)
                )
            )
            Text(
                text = if (isNightMode) "SODIUM LAMP // NIGHT" else "SUN-BLEACHED // DAY",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    color = if (isNightMode) WallPalette.MustardYellow else WallPalette.ConcreteCrevice
                )
            )
        }
    }
}

/**
 * Indigo (#2B4C8C) System Status Stencil:
 * - Spray-can paint level icon for Battery
 * - Free RAM in MB on the concrete ridge
 * - Continuous Termux uptime / status
 * - Launcher's own ultra-low RAM footprint
 */
@Composable
fun IndigoTelemetryStencilStrip(
    telemetry: WallTelemetry,
    onClickTermuxHelp: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .clickable { onClickTermuxHelp() }
            .semantics {
                contentDescription =
                    "Battery ${telemetry.batteryPercent} percent, Free RAM ${telemetry.freeRamMb} megabytes, Termux ${telemetry.termuxStatusText}"
            }
    ) {
        // Spray Can Paint Level Indicator for Battery
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(width = 18.dp, height = 26.dp)) {
                val stroke = 2.2f
                // Spray nozzle
                drawRect(
                    color = WallPalette.Indigo,
                    topLeft = Offset(size.width * 0.32f, 0f),
                    size = Size(size.width * 0.36f, size.height * 0.14f)
                )
                // Can dome & cylinder outline
                drawRect(
                    color = WallPalette.Indigo,
                    topLeft = Offset(stroke, size.height * 0.16f),
                    size = Size(size.width - stroke * 2, size.height * 0.80f),
                    style = Stroke(width = stroke)
                )
                // Paint level inside the spray can
                val fillFraction = (telemetry.batteryPercent / 100f).coerceIn(0.05f, 1f)
                val maxFillHeight = size.height * 0.72f
                val fillHeight = maxFillHeight * fillFraction
                drawRect(
                    color = WallPalette.MustardYellow.copy(alpha = 0.88f),
                    topLeft = Offset(stroke * 2, size.height * 0.92f - fillHeight),
                    size = Size(size.width - stroke * 4, fillHeight)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${telemetry.batteryPercent}%${if (telemetry.isCharging) "+" else ""}",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    color = WallPalette.Indigo
                )
            )
        }

        // Free RAM Stencil in Indigo
        Text(
            text = "RAM FREE:${telemetry.freeRamMb}MB (WALL:${telemetry.launcherHeapMb}MB)",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = WallPalette.Indigo
            )
        )

        // Termux Uptime / Status Stencil
        Text(
            text = "TERMUX:${telemetry.termuxStatusText}",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = WallPalette.Indigo
            )
        )
    }
}

/**
 * Search Box = Physical Cream Masking Tape stuck to the concrete wall with torn edges
 * and felt-tip marker handwriting.
 */
@Composable
fun MaskingTapeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .rotate(-0.45f)
            .semantics { contentDescription = "Masking tape app search bar" },
        contentAlignment = Alignment.CenterStart
    ) {
        // Torn masking tape background with adhesive shadow on concrete
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val tapePath = Path().apply {
                moveTo(6f, 2f)
                lineTo(w - 8f, 0f)
                // Jagged torn right edge
                val steps = 7
                for (i in 1..steps) {
                    val y = (h / steps) * i
                    val jag = if (i % 2 == 0) w - 3f else w - 11f
                    lineTo(jag, y)
                }
                lineTo(8f, h - 1f)
                // Jagged torn left edge
                for (i in steps downTo 1) {
                    val y = (h / steps) * i
                    val jag = if (i % 2 == 0) 2f else 10f
                    lineTo(jag, y)
                }
                close()
            }

            // Directional shadow under tape edge
            drawPath(
                path = tapePath,
                color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.28f),
                style = Stroke(width = 4f)
            )
            // Cream masking tape body
            drawPath(
                path = tapePath,
                color = WallPalette.MaskingTapeCream.copy(alpha = 0.92f)
            )
            // Subtle tape wrinkles & fiber lines
            drawLine(
                color = WallPalette.ConcreteCrevice.copy(alpha = 0.18f),
                start = Offset(w * 0.18f, 3f),
                end = Offset(w * 0.21f, h - 3f),
                strokeWidth = 1.5f
            )
            drawLine(
                color = WallPalette.ChalkWhite.copy(alpha = 0.45f),
                start = Offset(12f, 4f),
                end = Offset(w - 14f, 3f),
                strokeWidth = 1.2f
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAPE // ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    color = WallPalette.ConcreteCrevice
                )
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "WRITE APP NAME OR PRESS '/' FOR TERMUX...",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            color = WallPalette.MarkerInk.copy(alpha = 0.48f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 1.2.sp,
                        color = WallPalette.MarkerInk
                    ),
                    cursorBrush = SolidColor(WallPalette.SprayRed),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Text(
                    text = "[ X ]",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        color = WallPalette.SprayRed
                    ),
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * A single architectural bay of the concrete wall.
 * Scrolling between pages feels like walking past concrete pillars/expansion joints along the wall,
 * NEVER like a floating 3D carousel.
 * Empty pages are left intentionally empty with only raw concrete and the subtle Turquoise Rat stencil.
 */
@Composable
fun WallConcreteBayPage(
    pageIndex: Int,
    totalPages: Int,
    slots: List<WallGridSlot>,
    columns: Int,
    rows: Int,
    showLabels: Boolean,
    activeDripPackages: Set<String>,
    ghostRemnants: Map<String, String>,
    appByPackage: Map<String, WallAppItem>,
    peeledApp: WallAppItem?,
    scrubbingPackage: String?,
    scrubProgress: Float,
    ratStencilBitmap: androidx.compose.ui.graphics.ImageBitmap,
    onLaunchApp: (WallAppItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onLongPressApp: (WallAppItem) -> Unit,
    onRegisterSlotBounds: (String, Rect) -> Unit,
    onStartStencilPeel: (WallAppItem, String, Offset) -> Unit,
    onUpdateStencilPeel: (Offset) -> Unit,
    onEndStencilPeel: () -> Unit,
    onTapTurquoiseRat: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        // Architectural Concrete Pillar / Expansion Joint Shadows on Left & Right edges of the bay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Left concrete expansion joint shadow
            if (pageIndex > 0) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            WallPalette.ConcreteDeepShadow.copy(alpha = 0.42f),
                            WallPalette.ConcreteCrevice.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = 34f
                    ),
                    size = Size(34f, h)
                )
                drawLine(
                    color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.55f),
                    start = Offset(2f, 0f),
                    end = Offset(2f, h),
                    strokeWidth = 3f
                )
            }

            // Right concrete pillar corner shadow
            if (pageIndex < totalPages - 1) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            WallPalette.ConcreteCrevice.copy(alpha = 0.18f),
                            WallPalette.ConcreteDeepShadow.copy(alpha = 0.46f)
                        ),
                        startX = w - 38f,
                        endX = w
                    ),
                    topLeft = Offset(w - 38f, 0f),
                    size = Size(38f, h)
                )
                drawLine(
                    color = WallPalette.ChalkWhite.copy(alpha = 0.25f),
                    start = Offset(w - 4f, 0f),
                    end = Offset(w - 4f, h),
                    strokeWidth = 2f
                )
            }
        }

        // Stencil Grid directly on the wall surface
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (r in 0 until rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in 0 until columns) {
                        val indexInPage = r * columns + c
                        val slot = slots.getOrNull(indexInPage)
                        val slotCoordinateKey = "p${pageIndex}_r${r}_c${c}"

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            // 1. Render lingering ghost stencil if an icon was previously peeled from here
                            val ghostPkg = ghostRemnants[slotCoordinateKey]
                            val ghostApp = if (ghostPkg != null) appByPackage[ghostPkg] else null
                            if (ghostApp != null && (slot == null || (slot is WallGridSlot.SingleApp && slot.app.packageName != ghostPkg))) {
                                Image(
                                    bitmap = ghostApp.asset.ghostBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp),
                                    alpha = 0.45f
                                )
                            }

                            // 2. Render active stencil icon or wheatpaste folder
                            when (slot) {
                                is WallGridSlot.SingleApp -> {
                                    val app = slot.app
                                    val isBeingPeeled = peeledApp?.packageName == app.packageName
                                    val isBeingScrubbed = scrubbingPackage == app.packageName
                                    val hasDrip = app.packageName in activeDripPackages
                                    val boundKey = "app:${app.packageName}"

                                    WallSprayedAppSlot(
                                        app = app,
                                        showLabel = showLabels,
                                        isGhostPeeled = isBeingPeeled,
                                        hasRedPaintDrip = hasDrip,
                                        isScrubbing = isBeingScrubbed,
                                        scrubProgress = scrubProgress,
                                        onClick = { onLaunchApp(app) },
                                        onLongClick = { onLongPressApp(app) },
                                        onPositioned = { rect ->
                                            onRegisterSlotBounds(boundKey, rect)
                                            onRegisterSlotBounds(slotCoordinateKey, rect)
                                        },
                                        onStartDrag = { startOffset ->
                                            onStartStencilPeel(app, slotCoordinateKey, startOffset)
                                        },
                                        onDrag = onUpdateStencilPeel,
                                        onEndDrag = onEndStencilPeel
                                    )
                                }

                                is WallGridSlot.WheatpasteFolder -> {
                                    val boundKey = "folder:${slot.name}"
                                    WheatpasteFolderSlot(
                                        folderName = slot.name,
                                        apps = slot.apps,
                                        showLabel = showLabels,
                                        onClick = { onOpenFolder(slot.name) },
                                        onPositioned = { rect ->
                                            onRegisterSlotBounds(boundKey, rect)
                                        }
                                    )
                                }

                                null -> {
                                    // Intentionally empty wall space — no filler widgets or cards!
                                    Spacer(modifier = Modifier.size(76.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // EASTER EGG: Turquoise Stencil Rat (#1F7A72) in the bottom-right corner of empty/sparse wall bays.
        // Static overlay, zero looping animation, opens Termux in 1 tap.
        if (slots.size <= (columns * rows) / 2) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 6.dp)
                    .clickable { onTapTurquoiseRat() }
                    .semantics {
                        contentDescription = "Turquoise stencil rat in corner of wall. Tap to launch Termux."
                    }
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "rat://termux",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = WallPalette.Turquoise.copy(alpha = 0.75f)
                        ),
                        modifier = Modifier
                            .padding(bottom = 10.dp, end = 4.dp)
                            .rotate(-2f)
                    )
                    Image(
                        bitmap = ratStencilBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(58.dp)
                    )
                }
            }
        }
    }
}

/**
 * Single spray-painted stencil app icon on the concrete wall.
 * Includes:
 * - Pre-rendered sprayed icon with real app colors + directional wall shadow + ±1.5° tilt
 * - Red Paint Drip (#D8342A) notification stain dripping down beneath the icon
 * - Ghost stencil remnant when peeled off the wall
 * - Scrub-over spray animation when uninstalled
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallSprayedAppSlot(
    app: WallAppItem,
    showLabel: Boolean,
    isGhostPeeled: Boolean,
    hasRedPaintDrip: Boolean,
    isScrubbing: Boolean,
    scrubProgress: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPositioned: (Rect) -> Unit,
    onStartDrag: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEndDrag: () -> Unit
) {
    var slotRootBounds by remember { mutableStateOf(Rect.Zero) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                slotRootBounds = bounds
                onPositioned(bounds)
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .pointerInput(app.packageName) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { localOffset ->
                        onStartDrag(slotRootBounds.topLeft + localOffset)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = { onEndDrag() },
                    onDragCancel = { onEndDrag() }
                )
            }
            .semantics {
                contentDescription = buildString {
                    append("App stencil: ")
                    append(app.label)
                    if (hasRedPaintDrip) append(", has unread notification red paint drip")
                }
            }
    ) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.size(width = 86.dp, height = 92.dp)
        ) {
            // If currently peeled off the wall, leave behind the chalky ghost stencil ("The Wall Remembers")
            Image(
                bitmap = if (isGhostPeeled) app.asset.ghostBitmap else app.asset.normalBitmap,
                contentDescription = app.label,
                modifier = Modifier.size(78.dp)
            )

            // NOTIFICATION BADGE = RED PAINT DRIP (#D8342A)
            // The ONLY place where vivid Spray Red is allowed to shout.
            if (hasRedPaintDrip && !isGhostPeeled) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    val cx = size.width * 0.54f
                    // Upper paint pool at bottom edge of stencil
                    drawCircle(
                        color = WallPalette.SprayRed,
                        radius = 4.5f,
                        center = Offset(cx, 5f)
                    )
                    // Primary gravity drip streak
                    drawLine(
                        color = WallPalette.SprayRed,
                        start = Offset(cx, 5f),
                        end = Offset(cx, size.height - 6f),
                        strokeWidth = 3.8f,
                        cap = StrokeCap.Round
                    )
                    // Teardrop bead at the tip of the drip
                    drawCircle(
                        color = WallPalette.SprayRed,
                        radius = 4.2f,
                        center = Offset(cx, size.height - 5f)
                    )
                    // Secondary shorter drip beside it
                    drawLine(
                        color = WallPalette.SprayRed.copy(alpha = 0.85f),
                        start = Offset(cx - 7f, 6f),
                        end = Offset(cx - 7f, size.height * 0.56f),
                        strokeWidth = 2.4f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // UNINSTALL = PAINTED OVER / SCRUBBED OUT WITH SPRAY PAINT
            if (isScrubbing) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val w = size.width * scrubProgress
                    val h = size.height
                    drawLine(
                        color = WallPalette.ConcreteDeepShadow,
                        start = Offset(6f, h * 0.28f),
                        end = Offset(w, h * 0.35f),
                        strokeWidth = 26f,
                        cap = StrokeCap.Round
                    )
                    if (scrubProgress > 0.3f) {
                        drawLine(
                            color = WallPalette.SprayRed,
                            start = Offset(w, h * 0.50f),
                            end = Offset(8f, h * 0.68f),
                            strokeWidth = 24f,
                            cap = StrokeCap.Round
                        )
                    }
                    if (scrubProgress > 0.6f) {
                        drawLine(
                            color = WallPalette.ConcreteBase,
                            start = Offset(10f, h * 0.75f),
                            end = Offset(w, h * 0.80f),
                            strokeWidth = 22f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        if (showLabel) {
            // High-contrast stencil label stamped into the concrete (WCAG readable + system font scale)
            Text(
                text = app.label.uppercase(Locale.getDefault()),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    color = if (isGhostPeeled) {
                        WallPalette.ConcreteCrevice.copy(alpha = 0.45f)
                    } else {
                        WallPalette.MarkerInk
                    }
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 104.dp)
                    .rotate(app.asset.tiltDegrees * 0.5f)
            )
        }
    }
}

/**
 * Folder = Layered Brown Kraft Paper / Wheatpaste Poster pasted onto the concrete wall
 * with torn paper edges.
 */
@Composable
fun WheatpasteFolderSlot(
    folderName: String,
    apps: List<WallAppItem>,
    showLabel: Boolean,
    onClick: () -> Unit,
    onPositioned: (Rect) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned { onPositioned(it.boundsInRoot()) }
            .clickable { onClick() }
            .semantics {
                contentDescription = "Wheatpaste Kraft Paper Folder $folderName with ${apps.size} apps"
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(84.dp)
        ) {
            // Layered torn brown kraft paper sheets pasted onto the wall
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Bottom kraft scrap layer (tilted +4°)
                drawRect(
                    color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.30f),
                    topLeft = Offset(12f, 14f),
                    size = Size(w - 18f, h - 18f)
                )
                drawRect(
                    color = WallPalette.KraftPaperDark,
                    topLeft = Offset(8f, 10f),
                    size = Size(w - 18f, h - 18f)
                )

                // Top torn kraft paper layer
                val tornKraft = Path().apply {
                    moveTo(6f, 8f)
                    lineTo(w - 8f, 6f)
                    lineTo(w - 5f, h * 0.45f)
                    lineTo(w - 11f, h - 9f)
                    lineTo(w * 0.5f, h - 6f)
                    lineTo(7f, h - 10f)
                    close()
                }
                drawPath(path = tornKraft, color = WallPalette.KraftPaperBase)

                // Wheatpaste glue brush strokes at corners
                drawLine(
                    color = WallPalette.ChalkWhite.copy(alpha = 0.45f),
                    start = Offset(4f, 12f),
                    end = Offset(22f, 6f),
                    strokeWidth = 4f
                )
                drawLine(
                    color = WallPalette.ChalkWhite.copy(alpha = 0.40f),
                    start = Offset(w - 22f, h - 8f),
                    end = Offset(w - 4f, h - 16f),
                    strokeWidth = 4f
                )
            }

            // Preview up to 4 stencil icons pasted on the kraft paper
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val preview = apps.take(4)
                for (rowIdx in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (colIdx in 0 until 2) {
                            val item = preview.getOrNull(rowIdx * 2 + colIdx)
                            if (item != null) {
                                Image(
                                    bitmap = item.asset.normalBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }

        if (showLabel) {
            Text(
                text = "[ $folderName ]",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    color = WallPalette.MarkerInk
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Opened Wheatpaste Folder Overlay:
 * Renders overlapping torn brown kraft paper sheets pasted onto the concrete wall.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WheatpasteFolderSheet(
    folderName: String,
    apps: List<WallAppItem>,
    onDismiss: () -> Unit,
    onLaunchApp: (WallAppItem) -> Unit,
    onPeelOutApp: (WallAppItem) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallPalette.ConcreteDeepShadow.copy(alpha = 0.58f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 640.dp)
                .padding(24.dp)
                .clickable(enabled = false) {}
        ) {
            // Layered torn kraft paper background
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                // Cast shadow on concrete
                drawRect(
                    color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.55f),
                    topLeft = Offset(10f, 12f),
                    size = Size(w, h)
                )
                // Under-layer kraft sheet
                drawRect(
                    color = WallPalette.KraftPaperDark,
                    topLeft = Offset(-6f, 6f),
                    size = Size(w, h)
                )
                // Main torn kraft poster
                val path = Path().apply {
                    moveTo(0f, 4f)
                    lineTo(w - 4f, 0f)
                    lineTo(w, h - 8f)
                    lineTo(w * 0.65f, h)
                    lineTo(6f, h - 4f)
                    close()
                }
                drawPath(path = path, color = WallPalette.KraftPaperBase)

                // Masking tape strips holding the wheatpaste poster to the wall
                drawRect(
                    color = WallPalette.MaskingTapeCream,
                    topLeft = Offset(w * 0.42f, -10f),
                    size = Size(w * 0.16f, 22f)
                )
            }

            Column(modifier = Modifier.padding(28.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WHEATPASTE // $folderName",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            letterSpacing = 1.5.sp,
                            color = WallPalette.MarkerInk
                        )
                    )
                    Text(
                        text = "[ TEAR CLOSE ]",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            color = WallPalette.MarkerInk
                        ),
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TAP TO OPEN APP • LONG-PRESS TO PEEL OFF KRAFT PAPER",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = WallPalette.ConcreteDeepShadow
                    )
                )
                Spacer(modifier = Modifier.height(18.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    apps.forEachIndexed { idx, app ->
                        // Individual torn kraft paper scrap for each icon inside the folder
                        Box(
                            modifier = Modifier
                                .rotate(if (idx % 2 == 0) -1.4f else 1.6f)
                                .background(WallPalette.MaskingTapeCream.copy(alpha = 0.55f))
                                .border(1.dp, WallPalette.KraftPaperDark)
                                .combinedClickable(
                                    onClick = { onLaunchApp(app) },
                                    onLongClick = { onPeelOutApp(app) }
                                )
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    bitmap = app.asset.normalBitmap,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = app.label.uppercase(Locale.getDefault()),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        color = WallPalette.MarkerInk
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 92.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dock = The Street Curb / Sidewalk Pavement at the base of the concrete wall.
 * Separated by a physical weathered curb stone joint line, NEVER a floating glass or solid bar.
 * Termux is pinned here with a Turquoise stencil marker for instant 1-tap access.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallStreetCurbDock(
    dockApps: List<WallAppItem>,
    activeDripPackages: Set<String>,
    showLabels: Boolean,
    currentPage: Int,
    totalPages: Int,
    onLaunchApp: (WallAppItem) -> Unit,
    onLongPressApp: (WallAppItem) -> Unit,
    onDockPositioned: (Rect) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
            .onGloballyPositioned { onDockPositioned(it.boundsInRoot()) }
            .semantics { contentDescription = "Street Curb Dock below the wall" }
    ) {
        // Curb stone joint & weathered asphalt/sidewalk surface
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Curb shadow where vertical wall meets horizontal sidewalk
            drawLine(
                color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.72f),
                start = Offset(0f, 4f),
                end = Offset(w, 4f),
                strokeWidth = 5f
            )
            // Chipped chalk highlight along top curb edge
            drawLine(
                color = WallPalette.ChalkWhite.copy(alpha = 0.42f),
                start = Offset(0f, 8f),
                end = Offset(w, 8f),
                strokeWidth = 2.5f
            )
            // Darker sidewalk pavement tint
            drawRect(
                color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.16f),
                topLeft = Offset(0f, 8f),
                size = Size(w, h - 8f)
            )
            // Curb stone expansion joints every ~220px
            val jointSpacing = w / 5f
            for (i in 1..4) {
                val jx = jointSpacing * i
                drawLine(
                    color = WallPalette.ConcreteDeepShadow.copy(alpha = 0.35f),
                    start = Offset(jx, 6f),
                    end = Offset(jx - 14f, h),
                    strokeWidth = 2f
                )
            }
        }

        // Chalk wall bay indicator marks stamped on the left of the curb
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CURB // BAY ${currentPage + 1}/$totalPages",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    color = WallPalette.ConcreteDeepShadow
                )
            )
        }

        // Pinned Curb Stencil Icons
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 8.dp, start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            dockApps.forEach { app ->
                val isTermux = app.packageName == TermuxCare.TERMUX_PACKAGE
                val hasDrip = app.packageName in activeDripPackages

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onLaunchApp(app) },
                            onLongClick = { onLongPressApp(app) }
                        )
                        .semantics {
                            contentDescription = "Pinned on curb: ${app.label}"
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Turquoise stencil underline mark exclusively under Termux on the curb
                        if (isTermux) {
                            Canvas(
                                modifier = Modifier
                                    .size(width = 64.dp, height = 10.dp)
                                    .align(Alignment.BottomCenter)
                            ) {
                                drawLine(
                                    color = WallPalette.Turquoise,
                                    start = Offset(4f, size.height - 2f),
                                    end = Offset(size.width - 4f, size.height - 2f),
                                    strokeWidth = 4.5f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        Image(
                            bitmap = app.asset.normalBitmap,
                            contentDescription = app.label,
                            modifier = Modifier.size(66.dp)
                        )

                        if (hasDrip) {
                            Canvas(
                                modifier = Modifier
                                    .size(width = 16.dp, height = 22.dp)
                                    .align(Alignment.BottomCenter)
                            ) {
                                drawLine(
                                    color = WallPalette.SprayRed,
                                    start = Offset(size.width / 2, 0f),
                                    end = Offset(size.width / 2, size.height - 4f),
                                    strokeWidth = 3.5f,
                                    cap = StrokeCap.Round
                                )
                                drawCircle(
                                    color = WallPalette.SprayRed,
                                    radius = 3.5f,
                                    center = Offset(size.width / 2, size.height - 3f)
                                )
                            }
                        }
                    }

                    if (showLabels) {
                        Text(
                            text = if (isTermux) ">_ TERMUX" else app.label.uppercase(Locale.getDefault()),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                color = if (isTermux) WallPalette.Turquoise else WallPalette.MarkerInk
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 92.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Long-Press Menu = Reveals the Magenta (#C0436B) Stencil Cutout Template outline,
 * corner registration marks (+), and stencil typography shortcuts.
 * Includes dedicated Termux shortcuts ("OPEN LATEST SESSION" / "OPEN NEW WINDOW").
 */
@Composable
fun StencilTemplateActionOverlay(
    app: WallAppItem,
    isPinnedToCurb: Boolean,
    hasRedDrip: Boolean,
    onDismiss: () -> Unit,
    onToggleCurbPin: () -> Unit,
    onAssignWheatpasteFolder: () -> Unit,
    onToggleRedDrip: () -> Unit,
    onHideStencil: () -> Unit,
    onScrubUninstall: () -> Unit,
    onTermuxLatestSession: () -> Unit,
    onTermuxNewWindow: () -> Unit,
    onOpenSystemAppInfo: () -> Unit
) {
    val isTermux = app.packageName == TermuxCare.TERMUX_PACKAGE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallPalette.ConcreteDeepShadow.copy(alpha = 0.52f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(min = 320.dp, max = 460.dp)
                .background(WallPalette.ConcreteBase)
                .border(2.dp, WallPalette.Magenta)
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            // Stencil Template Header with Registration Marks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "+ STENCIL TEMPLATE // CUTOUT +",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.4.sp,
                        color = WallPalette.Magenta
                    )
                )
                Text(
                    text = "[ ESC ]",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        color = WallPalette.MarkerInk
                    ),
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Icon surrounded by dashed Magenta Stencil Cutout Outline
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(112.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = WallPalette.Magenta,
                        style = Stroke(
                            width = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                    )
                    // Corner registration crosses
                    val arm = 10f
                    drawLine(WallPalette.Magenta, Offset(0f, -arm), Offset(0f, arm), 2.5f)
                    drawLine(WallPalette.Magenta, Offset(-arm, 0f), Offset(arm, 0f), 2.5f)
                }
                Image(
                    bitmap = app.asset.normalBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(88.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = app.label.uppercase(Locale.getDefault()),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 1.8.sp,
                    color = WallPalette.MarkerInk
                )
            )
            Text(
                text = app.packageName,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = WallPalette.ConcreteCrevice
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Dedicated Termux Care Stencil Shortcuts
            if (isTermux) {
                StencilMenuRow(
                    label = "[ >_ OPEN LATEST SESSION ]",
                    color = WallPalette.Turquoise,
                    onClick = onTermuxLatestSession
                )
                StencilMenuRow(
                    label = "[ + OPEN NEW TERMUX WINDOW ]",
                    color = WallPalette.Turquoise,
                    onClick = onTermuxNewWindow
                )
            }

            StencilMenuRow(
                label = if (isPinnedToCurb) "[ UNPIN FROM STREET CURB ]" else "[ PIN TO STREET CURB DOCK ]",
                color = WallPalette.MarkerInk,
                onClick = onToggleCurbPin
            )
            StencilMenuRow(
                label = "[ PASTE INTO WHEATPASTE FOLDER ]",
                color = WallPalette.MarkerInk,
                onClick = onAssignWheatpasteFolder
            )
            StencilMenuRow(
                label = if (hasRedDrip) "[ WIPE RED NOTIFICATION DRIP ]" else "[ MARK RED PAINT DRIP ]",
                color = WallPalette.SprayRed,
                onClick = onToggleRedDrip
            )
            StencilMenuRow(
                label = "[ HIDE STENCIL FROM WALL ]",
                color = WallPalette.MarkerInk,
                onClick = onHideStencil
            )
            StencilMenuRow(
                label = "[ SYSTEM APP INFO / BATTERY ]",
                color = WallPalette.Indigo,
                onClick = onOpenSystemAppInfo
            )
            StencilMenuRow(
                label = "[ SCRUB OVER / UNINSTALL APP ]",
                color = WallPalette.SprayRed,
                onClick = onScrubUninstall
            )
        }
    }
}

@Composable
private fun StencilMenuRow(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, WallPalette.ConcreteCrevice.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 14.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                letterSpacing = 1.2.sp,
                color = color
            )
        )
    }
}

/**
 * Dialog to paste an app into an existing or new Wheatpaste Kraft Paper Folder.
 */
@Composable
fun WheatpasteAssignDialog(
    app: WallAppItem,
    existingFolders: List<String>,
    onDismiss: () -> Unit,
    onSelectFolder: (String) -> Unit
) {
    var customFolderName by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallPalette.ConcreteDeepShadow.copy(alpha = 0.55f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 440.dp)
                .background(WallPalette.KraftPaperBase)
                .border(2.dp, WallPalette.KraftPaperDark)
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Text(
                text = "WHEATPASTE // ${app.label.uppercase(Locale.getDefault())}",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = WallPalette.MarkerInk
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            val presets = (existingFolders + listOf("TOOLS", "SHELL & DEV", "MEDIA", "DAILY")).distinct()
            presets.forEach { name ->
                StencilMenuRow(
                    label = "[ PASTE ONTO: $name ]",
                    color = WallPalette.MarkerInk,
                    onClick = { onSelectFolder(name) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = customFolderName,
                    onValueChange = { customFolderName = it.uppercase(Locale.getDefault()) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = WallPalette.MarkerInk
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .background(WallPalette.MaskingTapeCream)
                        .padding(10.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StencilActionButton(
                    text = "[ CREATE ]",
                    tint = WallPalette.MarkerInk,
                    onClick = {
                        if (customFolderName.isNotBlank()) {
                            onSelectFolder(customFolderName.trim())
                        }
                    }
                )
            }
        }
    }
}

/**
 * STRICTLY 5 SETTINGS ITEMS (No color theme pickers, no multi-page onboarding):
 * 1. Reduce Motion
 * 2. Number of Columns (4 to 8, tailored for Mi Pad 5 Pro)
 * 3. Hidden Apps Manager
 * 4. Show / Hide Wall Status Stencil
 * 5. Show / Hide App Labels
 */
@Composable
fun WallSettingsStencilSheet(
    settings: WallSettings,
    installedApps: List<WallAppItem>,
    onDismiss: () -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
    onSetColumns: (Int) -> Unit,
    onSetShowStatus: (Boolean) -> Unit,
    onSetShowLabels: (Boolean) -> Unit,
    onToggleHiddenApp: (String) -> Unit,
    onClearAllHidden: () -> Unit,
    onSetDefaultLauncher: () -> Unit,
    onEnableNotificationDrips: () -> Unit,
    onOpenTermuxCareGuide: () -> Unit
) {
    var showHiddenAppsPicker by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallPalette.ConcreteDeepShadow.copy(alpha = 0.62f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .background(WallPalette.ConcreteBase)
                .border(2.dp, WallPalette.MarkerInk)
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WALL // STENCIL CONFIGURATION (5 MAX)",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 1.2.sp,
                        color = WallPalette.MarkerInk
                    )
                )
                Text(
                    text = "[ CLOSE ]",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        color = WallPalette.SprayRed
                    ),
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Reduce Motion
            StencilSettingToggleRow(
                number = "01",
                title = "REDUCE MOTION",
                valueText = if (settings.reduceMotion) "ON (INSTANT)" else "OFF (STANDARD)",
                onClick = { onSetReduceMotion(!settings.reduceMotion) }
            )

            // 2. Column Count (Mi Pad 5 Pro 4..8 columns)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "02. WALL GRID COLUMNS",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        color = WallPalette.MarkerInk
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 5, 6, 7, 8).forEach { col ->
                        val selected = settings.columnCount == col
                        Text(
                            text = "[$col]",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                color = if (selected) WallPalette.MustardYellow else WallPalette.ConcreteCrevice
                            ),
                            modifier = Modifier
                                .background(
                                    if (selected) WallPalette.MarkerInk else Color.Transparent
                                )
                                .clickable { onSetColumns(col) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 3. Hidden Apps
            StencilSettingToggleRow(
                number = "03",
                title = "HIDDEN STENCILS (${settings.hiddenPackages.size} APPS)",
                valueText = if (showHiddenAppsPicker) "[ HIDE LIST ]" else "[ MANAGE ]",
                onClick = { showHiddenAppsPicker = !showHiddenAppsPicker }
            )

            if (showHiddenAppsPicker) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(WallPalette.MaskingTapeCream)
                        .border(1.dp, WallPalette.ConcreteCrevice)
                        .padding(8.dp)
                ) {
                    LazyColumn {
                        item {
                            if (settings.hiddenPackages.isNotEmpty()) {
                                Text(
                                    text = "[ RESTORE ALL HIDDEN APPS ]",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp,
                                        color = WallPalette.Turquoise
                                    ),
                                    modifier = Modifier
                                        .clickable { onClearAllHidden() }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }
                        items(installedApps, key = { it.packageName }) { app ->
                            val isHidden = app.packageName in settings.hiddenPackages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleHiddenApp(app.packageName) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = app.label,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = WallPalette.MarkerInk
                                    )
                                )
                                Text(
                                    text = if (isHidden) "[ HIDDEN ]" else "[ VISIBLE ]",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp,
                                        color = if (isHidden) WallPalette.SprayRed else WallPalette.Turquoise
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 4. Show / Hide Status Stencil
            StencilSettingToggleRow(
                number = "04",
                title = "WALL TELEMETRY STENCIL (BAT/RAM/TERMUX)",
                valueText = if (settings.showStatusStencil) "VISIBLE" else "HIDDEN",
                onClick = { onSetShowStatus(!settings.showStatusStencil) }
            )

            // 5. Show / Hide App Labels
            StencilSettingToggleRow(
                number = "05",
                title = "APP STENCIL LABELS",
                valueText = if (settings.showAppLabels) "VISIBLE" else "HIDDEN",
                onClick = { onSetShowLabels(!settings.showAppLabels) }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // System Launcher Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StencilActionButton(
                    text = "[ SET DEFAULT HOME ]",
                    tint = WallPalette.Indigo,
                    onClick = onSetDefaultLauncher
                )
                StencilActionButton(
                    text = "[ RED DRIP ACCESS ]",
                    tint = WallPalette.SprayRed,
                    onClick = onEnableNotificationDrips
                )
                StencilActionButton(
                    text = "[ TERMUX GUIDE ]",
                    tint = WallPalette.Turquoise,
                    onClick = onOpenTermuxCareGuide
                )
            }
        }
    }
}

@Composable
private fun StencilSettingToggleRow(
    number: String,
    title: String,
    valueText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$number. $title",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                color = WallPalette.MarkerInk
            )
        )
        Text(
            text = valueText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                color = WallPalette.Indigo
            )
        )
    }
}

/**
 * 1-Page Plain-Language Termux & Xiaomi Pad 5 Pro Survival Sheet.
 * Explains clearly why RAM cleaners and MIUI Battery Optimization kill long Termux jobs,
 * and provides 1-tap links to fix them.
 */
@Composable
fun TermuxSurvivalGuideSheet(
    telemetry: WallTelemetry,
    onDismiss: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenTermuxAppInfo: () -> Unit,
    onOpenMiuiHomeSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallPalette.ConcreteDeepShadow.copy(alpha = 0.65f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 660.dp)
                .padding(20.dp)
                .background(WallPalette.MaskingTapeCream)
                .border(2.dp, WallPalette.Indigo)
                .clickable(enabled = false) {}
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TERMUX SURVIVAL MANIFESTO // MI PAD 5 PRO",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = WallPalette.Indigo
                    )
                )
                Text(
                    text = "[ X CLOSE ]",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        color = WallPalette.SprayRed
                    ),
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "CURRENT WALL FOOTPRINT: ${telemetry.launcherHeapMb} MB (BUDGET <= 60 MB) • FREE RAM: ${telemetry.freeRamMb} MB",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    color = WallPalette.Turquoise
                )
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "1. NEVER USE 'RAM CLEANERS' OR 'SPEED BOOSTERS'\n" +
                        "WALL never calls killBackgroundProcesses() and uses zero background polling. " +
                        "Do NOT press the 'X' clear-all button in MIUI Recents or use MIUI Security Boost while running SSH, compilation, or Python/Node servers in Termux — those cleaners forcefully murder child processes.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = WallPalette.MarkerInk
                )
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "2. EXEMPT BOTH 'TERMUX' AND 'WALL' FROM MIUI BATTERY SAVER\n" +
                        "On Xiaomi Pad 5 Pro (MIUI for Pad / HyperOS), tap [ TERMUX APP INFO ] below -> Battery Saver -> choose 'No restrictions', and enable 'Autostart'. Do the same for WALL so the OS never suspends the home surface.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = WallPalette.MarkerInk
                )
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "3. ACQUIRE WAKE LOCK INSIDE TERMUX & DISABLE PHANTOM KILLER\n" +
                        "Run 'termux-wake-lock' inside your Termux shell before long jobs. On Android 12/13/14, disable Android's 32-process cap via ADB or LADB:\n" +
                        "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent; /system/bin/device_config put activity_manager max_phantom_processes 2147483647\"",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = WallPalette.MarkerInk
                )
            )

            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StencilActionButton(
                    text = "[ TERMUX APP INFO ]",
                    tint = WallPalette.Turquoise,
                    onClick = onOpenTermuxAppInfo
                )
                StencilActionButton(
                    text = "[ BATTERY EXEMPTIONS ]",
                    tint = WallPalette.Indigo,
                    onClick = onOpenBatterySettings
                )
                StencilActionButton(
                    text = "[ MIUI DEFAULT HOME ]",
                    tint = WallPalette.MarkerInk,
                    onClick = onOpenMiuiHomeSettings
                )
            }
        }
    }
}

@Composable
private fun StencilActionButton(
    text: String,
    tint: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            color = tint
        ),
        modifier = Modifier
            .border(1.dp, tint.copy(alpha = 0.65f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

private fun formatWallClock(): String {
    return SimpleDateFormat("HH:mm", Locale.US).format(Date())
}

private fun formatWallDate(): String {
    return SimpleDateFormat("EEE dd MMM", Locale.US).format(Date())
}
