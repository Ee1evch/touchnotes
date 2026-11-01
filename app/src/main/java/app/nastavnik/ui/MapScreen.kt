package app.nastavnik.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.campus.Place
import app.nastavnik.campus.Navigation
import app.nastavnik.study.LiveNotifications
import app.nastavnik.campus.PositionTracker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.campus.Room
import app.nastavnik.campus.RoomSearch
import app.nastavnik.campus.Route
import app.nastavnik.campus.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Откуда строим маршрут. */
private sealed interface From {
    val label: String
    data class FromRoom(val site: Site, val room: Room) : From { override val label get() = room.title }
    data class Here(val floor: Int, val x: Double, val y: Double) : From { override val label get() = "Я здесь" }
}

/**
 * Карта кампуса: поиск аудитории, этажи, «как дойти» и «где я».
 * [initialRoom] — аудитория из расписания или уведомления «скоро пара».
 */
@Composable
fun MapScreen(initialRoom: String?, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var sites by remember { mutableStateOf<List<Site>>(emptyList()) }
    var site by remember { mutableStateOf<Site?>(null) }
    var floor by remember { mutableIntStateOf(1) }
    var selected by remember { mutableStateOf<Room?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    // Пошаговая навигация: шаги маршрута, текущий шаг, сколько до следующего, сколько осталось.
    var navigating by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    var toNext by remember { mutableStateOf<Double?>(null) }
    var left by remember { mutableStateOf<Double?>(null) }
    var offCount by remember { mutableIntStateOf(0) }
    val steps = remember(route, selected) { route?.let { r -> site?.let { s -> Navigation.maneuvers(s, r, selected) } }.orEmpty() }
    var routing by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<From?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var pickingFrom by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<CampusMapView?>(null) }
    val dark = Brand.dark
    val accent = Brand.Accent.toArgb()

    LaunchedEffect(Unit) {
        val list = runCatching { app.campus.sites() }.getOrDefault(emptyList())
        if (site == null) {
            site = list.firstOrNull()
            floor = site?.floors?.firstOrNull { it.n == 1 }?.n ?: site?.floors?.firstOrNull()?.n ?: 1
        }
        sites = list
    }
    // Аудитория из расписания или уведомления — и при первом показе, и когда карта уже открыта.
    LaunchedEffect(sites, initialRoom) {
        if (sites.isEmpty() || initialRoom == null) return@LaunchedEffect
        val start = RoomSearch.forClassroom(sites, initialRoom)
        if (start != null) {
            route = null
            map?.setRoute(null, false)
            site = start.first
            selected = start.second
            if (floor != start.second.floor) floor = start.second.floor else map?.select(start.second, focus = true)
        } else {
            message = "«$initialRoom» на карте не нашлась — поищи вручную"
        }
        // Откуда идти по умолчанию — из аудитории предыдущей пары, если она сегодня была.
        val prev = runCatching {
            val now = java.time.LocalTime.now()
            app.schedule.day(java.time.LocalDate.now()).lastOrNull { l -> l.endTime?.isBefore(now) == true && l.classroom != initialRoom }
        }.getOrNull()
        prev?.classroom?.let { RoomSearch.forClassroom(sites, it) }?.let { (s, r) -> from = From.FromRoom(s, r) }
    }

    // Этаж: чертёж сразу, сетка — следом в фоне (для касаний и маршрутов).
    LaunchedEffect(site, floor, map) {
        val s = site ?: return@LaunchedEffect
        val v = map ?: return@LaunchedEffect
        val geo = runCatching { app.campus.floorGeo(s, floor) }.getOrNull() ?: return@LaunchedEffect
        v.show(s, floor, geo, keepCamera = true)
        v.select(selected, focus = selected?.floor == floor)
        v.setGrid(null)
        val g = withContext(Dispatchers.Default) { runCatching { app.campus.grid(s, floor) }.getOrNull() }
        if (v.floor == floor) v.setGrid(g)
    }
    LaunchedEffect(map, dark, accent) { map?.setTheme(dark); map?.accent = accent; map?.invalidate() }

    fun selectRoom(s: Site, r: Room) {
        route = null
        map?.setRoute(null, false)
        selected = r
        if (site?.id != s.id) site = s
        if (floor != r.floor) floor = r.floor else map?.select(r, focus = true)
        searching = false
        query = ""
        focus.clearFocus()
    }

    fun buildRoute() {
        val s = site ?: return
        val to = selected ?: return
        val start = from ?: run { pickingFrom = true; return }
        routing = true
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val router = app.campus.router(s)
                    val a: Place = when (start) {
                        is From.FromRoom -> router.placeOf(start.room)
                        is From.Here -> router.placeAt(start.floor, start.x, start.y)
                    } ?: return@runCatching null
                    val b = router.placeOf(to) ?: return@runCatching null
                    router.route(a, b)
                }.getOrNull()
            }
            routing = false
            if (r == null) {
                message = "Не получилось проложить путь — попробуй другую точку отправления"
                return@launch
            }
            route = r
            step = 0; toNext = null; left = null; offCount = 0
            val first = r.legs.first().floor
            if (floor != first) floor = first
            map?.setRoute(r, focus = true)
        }
    }

    fun showStep(i: Int) {
        val m = steps.getOrNull(i) ?: return
        step = i
        if (floor != m.floor) floor = m.floor
        map?.post { map?.animateTo(m.x, m.y, 7.0 * context.resources.displayMetrics.density) }
    }

    // «Где я»: живое слежение — Wi-Fi, шаги, компас, барометр, GPS (см. Positioning).
    val tracker = remember { PositionTracker(context.applicationContext, app.campus) }
    var tracking by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var correcting by remember { mutableStateOf(false) }
    val here by tracker.state.collectAsStateWithLifecycle()
    val trackStatus by tracker.status.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { tracker.stop() } }
    fun locate() {
        if (sites.isEmpty()) return
        follow = true
        if (!tracking) {
            tracking = true
            locating = true
            scope.launch { tracker.start(sites) }
        } else {
            here?.let { h -> if (floor != h.fix.floor) floor = h.fix.floor; map?.setMe(h.fix.floor, h.fix.x, h.fix.y, focus = true, radius = h.fix.radius, heading = h.fix.heading) }
        }
    }
    LaunchedEffect(here) {
        val h = here ?: return@LaunchedEffect
        locating = false
        if (site?.id != h.site.id) { site = h.site; selected = null; route = null; map?.setRoute(null, false) }
        if (follow && floor != h.fix.floor) floor = h.fix.floor
        map?.setMe(h.fix.floor, h.fix.x, h.fix.y, focus = follow, radius = h.fix.radius, heading = h.fix.heading)
        if (!navigating) from = From.Here(h.fix.floor, h.fix.x, h.fix.y)
        // Навигация: какой шаг, сколько до поворота; ушёл с маршрута — перестроить от текущего места.
        val r = route
        if (navigating && r != null && h.site.id == site?.id) {
            val p = Navigation.progress(r, steps, h.fix.floor, h.fix.x, h.fix.y, from = step)
            if (p != null) {
                if (p.step != step) step = p.step
                toNext = p.toNext; left = p.left
                offCount = if (p.offRoute > 10 + h.fix.radius / 2) offCount + 1 else 0
                if (offCount >= 3) {
                    offCount = 0
                    from = From.Here(h.fix.floor, h.fix.x, h.fix.y)
                    message = "Сошёл с маршрута — перестраиваю"
                    buildRoute()
                }
            }
        }
    }
    LaunchedEffect(trackStatus) { if (trackStatus.isNotEmpty() && here == null && tracking) message = trackStatus }
    // Касание в режиме «я здесь» — поправить место и запомнить этот скан.
    LaunchedEffect(correcting, map) {
        val v = map ?: return@LaunchedEffect
        v.onPointTap = if (!correcting) null else { x, y ->
            val s = site
            if (s != null) {
                tracker.correct(s, floor, x, y)
                map?.setMe(floor, x, y, focus = false)
                message = "Запомнил: в следующий раз узнаю это место точнее"
            }
            correcting = false
        }
        v.onUserMove = { follow = false }
    }
    // Навигация в шторке: текущий шаг, пока идёшь (и когда экран погас).
    LaunchedEffect(navigating, step, toNext?.let { (it / 5).toInt() }) {
        if (!navigating || steps.isEmpty()) { LiveNotifications.hideNavigation(context); return@LaunchedEffect }
        val next = steps.getOrNull(step + 1) ?: steps[step]
        val dist = toNext
        val title = if (step < steps.lastIndex && dist != null) "Через ${Navigation.meters(dist)} — ${next.title.replaceFirstChar { it.lowercase() }}" else next.title
        LiveNotifications.showNavigation(context, title, next.detail.ifBlank { selected?.title.orEmpty() }, step, steps.lastIndex)
    }
    DisposableEffect(Unit) { onDispose { LiveNotifications.hideNavigation(context) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) locate()
        else message = "Без доступа к местоположению Android не показывает сети Wi-Fi"
    }
    fun askAndLocate() {
        val need = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (need.isEmpty() || (need.size == 1 && need[0] == Manifest.permission.ACTIVITY_RECOGNITION && tracking)) locate()
        else permission.launch(need.toTypedArray())
    }

    Box(Modifier.fillMaxSize().background(if (dark) Brand.Paper else androidx.compose.ui.graphics.Color(0xFFF4F2EE))) {
        AndroidView(
            factory = { ctx -> CampusMapView(ctx).also { v ->
                v.contentDescription = "Карта этажа"
                v.onRoomTap = { r ->
                    val s = site
                    if (r != null && s != null) {
                        if (pickingFrom) { from = From.FromRoom(s, r); pickingFrom = false; buildRoute() } else selectRoom(s, r)
                    }
                }
                map = v
            } },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Карта этажа" },
        )

        // Навигация: крупная плашка с манёвром сверху, как в обычных картах.
        AnimatedVisibility(
            navigating && steps.isNotEmpty(),
            enter = fadeIn(Motion.enter(260)) + slideInVertically(Motion.enter(360)) { -it / 2 },
            exit = fadeOut(Motion.exit(140)) + slideOutVertically(Motion.exit(160)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            NavigationBanner(steps, step, toNext, onPrev = { showStep(step - 1) }, onNext = { showStep(step + 1) })
        }
        // Сверху: назад, поиск, здания.
        if (!navigating) Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
                SearchField(query, onChange = { query = it; searching = it.isNotBlank() }, modifier = Modifier.weight(1f))
            }
            AnimatedVisibility(searching, enter = fadeIn() + slideInVertically { -it / 4 }, exit = fadeOut()) {
                val results = remember(query, sites) { RoomSearch.search(sites, query) }
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 12.dp, strong = true)) {
                    if (results.isEmpty()) {
                        Text("Ничего не нашлось", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft, modifier = Modifier.padding(18.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(results, key = { "${it.first.id}/${it.second.floor}/${it.second.id}" }) { (s, r) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selectRoom(s, r) }.padding(horizontal = 16.dp, vertical = 11.dp).animateItem(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                IconBadge(R.drawable.ic_location, 36)
                                Column(Modifier.weight(1f)) {
                                    Text(RoomSearch.label(r, query), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(s.floor(r.floor)?.short, shortSite(s).takeIf { sites.size > 1 }, r.subtitle.takeIf { it.isNotBlank() }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (sites.size > 1 && !searching) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sites.forEach { s ->
                        ChipButton(shortSite(s), selected = s.id == site?.id) {
                            if (site?.id != s.id) {
                                site = s; selected = null; route = null; map?.setRoute(null, false)
                                floor = s.floors.firstOrNull { it.n == 1 }?.n ?: s.floors.first().n
                            }
                        }
                    }
                }
            }
        }

        // Справа: этажи и «где я».
        site?.let { s ->
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloorPicker(s, floor, route) { floor = it }
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .then(if (tracking && follow) Modifier.background(Brand.Accent) else Modifier.glass(CircleShape, 8.dp, strong = true))
                        .clickable { askAndLocate() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (locating) CircularProgressIndicator(Modifier.size(22.dp), color = if (tracking && follow) Brand.OnAccent else Brand.Accent, strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_location), contentDescription = "Где я", tint = if (tracking && follow) Brand.OnAccent else Brand.Accent, modifier = Modifier.size(22.dp))
                }
            }
        }

        // Снизу: выбранная аудитория или маршрут.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(here != null || correcting, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                HereChip(here, correcting, onFix = { correcting = !correcting }, onStop = { tracker.stop(); tracking = false; correcting = false; map?.clearMe() })
            }
            AnimatedVisibility(message != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Row(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 10.dp).glass(RoundedCornerShape(18.dp), 8.dp, strong = true)
                        .clickable { message = null }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Скрыть", tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
                }
            }
            AnimatedContent(
                targetState = Triple(selected, route?.let { it to navigating }, pickingFrom),
                transitionSpec = { (fadeIn(Motion.enter(240)) + slideInVertically(Motion.enter(320)) { it / 6 }) togetherWith fadeOut(Motion.exit(100)) },
                label = "mapSheet",
            ) { (room, rn, picking) ->
                val r = rn?.first
                when {
                    room == null -> Spacer(Modifier.size(1.dp))
                    picking -> FromPicker(
                        from = from,
                        onHere = { pickingFrom = false; askAndLocate() },
                        onCancel = { pickingFrom = false },
                    )
                    r != null && rn.second -> NavigationBottom(r, steps, step, left, onStep = { showStep(it) }, onFinish = { navigating = false; route = null; map?.setRoute(null, false) })
                    r != null -> RouteCard(
                        site!!, room, r, from, currentFloor = floor,
                        onFloor = { floor = it; map?.post { map?.focusRoute() } }, onChangeFrom = { pickingFrom = true },
                        onClose = { route = null; map?.setRoute(null, false) },
                        onGo = {
                            navigating = true
                            showStep(0)
                            // С «где я» шаги переключаются сами, по мере того как идёшь.
                            if (!tracking) askAndLocate()
                        },
                    )
                    else -> RoomCard(site!!, room, from, routing, onRoute = ::buildRoute, onChangeFrom = { pickingFrom = true }, onClose = { selected = null; map?.select(null, false) })
                }
            }
        }
    }
}

private fun shortSite(s: Site) = when {
    s.title.contains("ГМЦ") -> "Главный корпус"
    s.title.contains("Пансион") -> "Пансион, Рекордов 15"
    else -> s.title.substringBefore(",")
}

private suspend fun awaitScan(context: Context) = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            runCatching { context.unregisterReceiver(this) }
            if (cont.isActive) cont.resumeWith(Result.success(Unit))
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.glass(RoundedCornerShape(24.dp), 8.dp, strong = true).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Аудитория, кабинет, столовая…", style = MaterialTheme.typography.bodyLarge, color = Brand.InkSoft)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Brand.Accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Поиск на карте" },
            )
        }
        AnimatedVisibility(value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Очистить", tint = Brand.InkSoft, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

/** Этажи сверху вниз; выбранный подсвечен скользящей «таблеткой», этажи маршрута отмечены точкой. */
@Composable
private fun FloorPicker(site: Site, floor: Int, route: Route?, onPick: (Int) -> Unit) {
    val floors = site.floors.sortedByDescending { it.n }
    val idx = floors.indexOfFirst { it.n == floor }.coerceAtLeast(0)
    val y by animateDpAsState((idx * 46).dp, Motion.spatial(), label = "floorY")
    val onRoute = route?.legs?.map { it.floor }?.toSet().orEmpty()
    Box(Modifier.width(48.dp).glass(RoundedCornerShape(24.dp), 8.dp, strong = true)) {
        Box(Modifier.padding(top = 1.dp).offset(y = y).size(48.dp, 46.dp).padding(4.dp).clip(RoundedCornerShape(19.dp)).background(Brand.Accent))
        Column {
            floors.forEach { f ->
                Box(
                    Modifier.size(48.dp, 46.dp).clickable { onPick(f.n) }.semantics { contentDescription = f.short },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (f.n) { 0 -> "П"; 9 -> "Ч"; else -> f.n.toString() },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (f.n == floor) Brand.OnAccent else MaterialTheme.colorScheme.onSurface,
                    )
                    if (f.n in onRoute && f.n != floor) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(5.dp).clip(CircleShape).background(Brand.Accent))
                }
            }
        }
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().glass(RoundedCornerShape(26.dp), 14.dp, strong = true).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun RoomCard(site: Site, room: Room, from: From?, routing: Boolean, onRoute: () -> Unit, onChangeFrom: () -> Unit, onClose: () -> Unit) {
    SheetCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(room.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(site.floor(room.floor)?.short, room.subtitle.takeIf { it.isNotBlank() }, "по пропуску".takeIf { room.restricted }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
                )
            }
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрыть", tint = Brand.InkSoft, modifier = Modifier.size(22.dp).clickable(onClick = onClose))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onChangeFrom).padding(vertical = 4.dp)) {
                Text("Откуда", style = MaterialTheme.typography.labelSmall, color = Brand.InkSoft)
                Text(from?.label ?: "Выбрать…", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (routing) CircularProgressIndicator(Modifier.size(28.dp), color = Brand.Accent, strokeWidth = 2.5.dp)
            else InkButton("Как дойти", icon = R.drawable.ic_route, onClick = onRoute)
        }
    }
}

@Composable
private fun FromPicker(from: From?, onHere: () -> Unit, onCancel: () -> Unit) {
    SheetCard {
        Text("Откуда идёшь?", style = MaterialTheme.typography.titleLarge)
        Text("Коснись помещения на карте или найди его поиском сверху.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Я здесь (по Wi-Fi)", icon = R.drawable.ic_location, onClick = onHere)
            SoftButton("Отмена", onClick = onCancel)
        }
        if (from != null) Text("Сейчас: ${from.label}", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    }
}

/** Маршрут: время, расстояние и шаги; шаг с другим этажом переключает карту на него. */
@Composable
private fun RouteCard(site: Site, room: Room, r: Route, from: From?, currentFloor: Int, onFloor: (Int) -> Unit, onChangeFrom: () -> Unit, onClose: () -> Unit, onGo: () -> Unit) {
    SheetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.minutes} мин", style = MaterialTheme.typography.headlineSmall, color = Brand.Accent)
                Text("${r.meters.roundToInt()} м до «${room.title}»", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            }
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрыть маршрут", tint = Brand.InkSoft, modifier = Modifier.size(22.dp).clickable(onClick = onClose))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val steps = buildList {
            add(Triple(r.legs.first().floor, "От «${from?.label ?: "старта"}»", "${site.floor(r.legs.first().floor)?.short.orEmpty()} · ${r.legs.first().meters.roundToInt()} м"))
            r.transfers.forEachIndexed { i, c ->
                val to = r.legs[i + 1].floor
                val verb = if (to > r.legs[i].floor) "Поднимись" else "Спустись"
                add(Triple(to, "$verb на ${site.floor(to)?.short?.lowercase() ?: "этаж $to"}", "${c.label.replaceFirstChar { it.uppercase() }}: ${c.name}"))
            }
            add(Triple(r.legs.last().floor, "До «${room.title}»", "${site.floor(r.legs.last().floor)?.short.orEmpty()} · ${r.legs.last().meters.roundToInt()} м"))
        }
        steps.forEachIndexed { i, (f, title, sub) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onFloor(f) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (f == currentFloor) Brand.Accent else Brand.Line),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (f == currentFloor) Brand.OnAccent else MaterialTheme.colorScheme.onSurface) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkText("Другая точка отправления", modifier = Modifier.weight(1f), onClick = onChangeFrom)
            InkButton("Поехали", icon = R.drawable.ic_route, onClick = onGo)
        }
    }
}

/** «Ты здесь: Альфа 5.8 · 1 этаж ±4 м» и «Не здесь?» — поправить касанием. */
@Composable
private fun HereChip(here: PositionTracker.State?, correcting: Boolean, onFix: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 10.dp).glass(RoundedCornerShape(18.dp), 8.dp, strong = true)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2F80FF)))
        Column(Modifier.weight(1f)) {
            if (correcting) {
                Text("Коснись места, где ты сейчас", style = MaterialTheme.typography.titleSmall)
                Text("Запомню сети Wi-Fi здесь — потом узнаю точнее", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            } else if (here != null) {
                val room = here.site.byId[here.fix.floor to here.fix.roomId]?.takeIf { !it.isGeneric }
                Text(
                    "Ты здесь: " + (room?.title ?: "коридор"),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${here.site.floor(here.fix.floor)?.short.orEmpty()} · ±${here.fix.radius.roundToInt()} м",
                    style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                )
            }
        }
        LinkText(if (correcting) "Отмена" else "Не здесь?", onClick = onFix)
        Icon(painterResource(R.drawable.ic_close), contentDescription = "Выключить «где я»", tint = Brand.InkSoft, modifier = Modifier.size(20.dp).clickable(onClick = onStop))
    }
}
