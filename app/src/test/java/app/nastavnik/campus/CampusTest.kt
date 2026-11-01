package app.nastavnik.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class CampusTest {
    companion object {
        /** Юнит-тесты Android идут из папки модуля, проверочная сборка — из своей: ищем assets от обеих. */
        val root: File = listOf("src/main/assets/campus", "app/src/main/assets/campus", "/home/user/touchnotes/app/src/main/assets/campus")
            .map(::File).first { it.isDirectory }

        val gmc: Site by lazy { Site.parse(File(root, "gmc/site.json").readText()) }
        private val grids = HashMap<Int, NavGrid>()
        fun grid(n: Int) = grids.getOrPut(n) { NavGrid.parse(File(root, "gmc/f$n.nav").readBytes()) }
    }

    @Test fun parsesSiteAndFloors() {
        assertEquals(listOf(1, 2, 3), gmc.floors.map { it.n })
        assertEquals("1 этаж", gmc.floor(1)!!.short)
        assertTrue(gmc.rooms.size > 1000)
        val geo = FloorGeo.parse(File(root, "gmc/f1.geo").readBytes())
        val rooms = geo.layer(GeoLayer.ROOMS)!!
        assertTrue(rooms.triangles && rooms.colors != null && rooms.indices!!.size % 3 == 0)
        assertTrue(rooms.indices!!.all { it < rooms.verts.size / 2 })
        val walls = geo.layer(GeoLayer.WALLS)!!
        assertTrue(walls.indices!!.size % 3 == 0)
        val b = geo.bounds
        assertTrue(b[2] - b[0] > 100 && b[3] - b[1] > 100)
    }

    @Test fun findsRoomsFromSchedule() {
        val sites = listOf(gmc)
        assertEquals("Альфа 5.8", RoomSearch.forClassroom(sites, "Альфа 5.8Альфа 5.8 (Основной)")!!.second.title)
        assertEquals("Бета 3.3", RoomSearch.forClassroom(sites, "1-07-18 Бета 3.3 (комп)")!!.second.title)
        assertTrue(RoomSearch.forClassroom(sites, "К_У. К4 (комп)")!!.second.nums.contains("К4"))
        // «Альфа 5.1» не должна найти «Альфа 5.10».
        assertEquals("Альфа 5.1", RoomSearch.forClassroom(sites, "Альфа 5.1")!!.second.names.first().substringBefore(" ·"))
        assertNull(RoomSearch.forClassroom(sites, "Дистанционно"))
        val found = RoomSearch.search(sites, "альфа 5")
        assertTrue(found.size >= 5 && found.all { it.second.title.startsWith("Альфа 5") })
        val buran = RoomSearch.search(sites, "буран")
        assertTrue(buran.isNotEmpty())
        // Музей входит в участок «территория вокруг здания» — в результатах показываем совпавшее имя.
        assertTrue(RoomSearch.label(buran.first().second, "буран").contains("Буран"))
        assertEquals("Альфа 5.8", RoomSearch.label(RoomSearch.search(sites, "альфа 5.8").first().second, "альфа 5.8"))
    }

    @Test fun routesOnOneFloorAndAcrossFloors() {
        val router = Router(gmc, ::grid)
        val a = RoomSearch.forClassroom(listOf(gmc), "Альфа 5.8")!!.second
        val b = RoomSearch.forClassroom(listOf(gmc), "Бета 3.3")!!.second
        val r = router.route(router.placeOf(a)!!, router.placeOf(b)!!)
        assertNotNull(r)
        assertTrue(r!!.legs.single().meters > 20)
        // Спрямлённая ломаная — десятки точек, а не тысячи клеток.
        assertTrue(r.legs.single().points.size < 80)

        val up = gmc.rooms.first { it.floor == 3 && !it.isGeneric && it.reachable }
        val r2 = router.route(router.placeOf(a)!!, router.placeOf(up)!!)
        assertNotNull(r2)
        assertEquals(listOf(1, 3), r2!!.legs.map { it.floor })
        assertEquals(1, r2.transfers.size)
        assertTrue(r2.minutes in 1..30)
    }

    @Test fun wifiFingerprintsLocateTheirOwnPoints() {
        val loc = WifiLocator.parse(File(root, "gmc/wifi.bin").readBytes())
        assertEquals(0x18c3f4b609d0L, WifiLocator.macKey("18:c3:f4:b6:09:d0"))
        assertNull(loc.locate(mapOf("00:11:22:33:44:55" to -50)))
    }
}

class PositioningTest {
    @Test fun walkAlongFingerprintsKeepsFloorAndPlace() {
        val site = CampusTest.gmc
        val loc = WifiLocator.parse(java.io.File(CampusTest.root, "gmc/wifi.bin").readBytes())
        val macOf = loc.bssids.entries.associate { (mac, i) -> i to (0 until 6).joinToString(":") { b -> "%02x".format((mac shr (8 * (5 - b))) and 0xff) } }
        // Отрезок обхода на 2 этаже: подряд идущие отпечатки на расстоянии 1–4 м.
        val pts = loc.points
        var walk: List<WifiLocator.Fingerprint> = emptyList()
        var i = 0
        while (i < pts.size - 30 && walk.isEmpty()) {
            val seg = pts.subList(i, i + 20)
            val ok = seg.all { it.floor == 2 } && seg.zipWithNext().all { (a, b) -> hypot(a.x - b.x, a.y - b.y) <= 6.0 } &&
                hypot(seg.first().x - seg.last().x, seg.first().y - seg.last().y) > 15
            
            if (ok) walk = seg
            i++
        }
        assertTrue("нет подходящего отрезка обхода", walk.isNotEmpty())
        val p = Positioning(site, loc, CampusTest::grid)
        val inv = site.enToPlan.let { m -> val det = m[0] * m[3] - m[1] * m[2]; doubleArrayOf(m[3] / det, -m[1] / det, -m[2] / det, m[0] / det) }
        var t = 0L
        walk.forEachIndexed { k, fp ->
            if (k > 0) {
                val prev = walk[k - 1]
                val dx = fp.x - prev.x; val dy = fp.y - prev.y
                // Обратно в «восток, север», азимут — как у телефона.
                val de = inv[0] * dx + inv[1] * dy; val dn = inv[2] * dx + inv[3] * dy
                val steps = (hypot(dx, dy) / 0.75).toInt()
                repeat(steps) { t += 500; p.onStep(kotlin.math.atan2(de, dn), t) }
            }
            t += 500
            p.onWifi(fp.rssi.entries.associate { (ap, lv) -> macOf.getValue(ap) to lv }, t)
        }
        val fix = p.estimate()!!
        assertEquals(2, fix.floor)
        val end = walk.last()
        assertTrue("далеко от конца пути: ${hypot(fix.x - end.x, fix.y - end.y)} м", hypot(fix.x - end.x, fix.y - end.y) < 8)
        assertTrue(fix.floorShare > 0.8)
        // «Я здесь» сразу переносит оценку.
        p.reset(1, end.x, end.y)
        assertEquals(1, p.estimate()!!.floor)
    }

    @Test fun geoReferenceRoundTrips() {
        val site = CampusTest.gmc
        val (x, y) = site.planOf(43.4142558, 39.9497525)!!
        // Центр ГМЦ из sites.json — где-то посреди плана.
        assertTrue(x in -150.0..550.0 && y in -260.0..480.0)
    }
}

class NavigationTest {
    @Test fun turnsAreClassified() {
        assertEquals(Maneuver.Kind.LEFT, Navigation.kindOf(Navigation.turnDeg(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0)))
        assertEquals(Maneuver.Kind.RIGHT, Navigation.kindOf(Navigation.turnDeg(0.0 to 0.0, 10.0 to 0.0, 10.0 to -10.0)))
        assertEquals(Maneuver.Kind.STRAIGHT, Navigation.kindOf(Navigation.turnDeg(0.0 to 0.0, 10.0 to 0.0, 20.0 to 2.0)))
        assertEquals(Maneuver.Kind.SLIGHT_RIGHT, Navigation.kindOf(-40.0))
    }

    @Test fun stepsAlongRealRoute() {
        val site = CampusTest.gmc
        val router = Router(site, CampusTest::grid)
        val a = RoomSearch.forClassroom(listOf(site), "Бета 3.3")!!.second
        val b = RoomSearch.forClassroom(listOf(site), "Альфа 5.8")!!.second
        val r = router.route(router.placeOf(a)!!, router.placeOf(b)!!)!!
        val steps = Navigation.maneuvers(site, r, b)
        steps.forEach { println("STEP ${it.kind} ${it.title} | ${it.detail}") }
        assertEquals(Maneuver.Kind.START, steps.first().kind)
        assertEquals(Maneuver.Kind.ARRIVE, steps.last().kind)
        assertTrue("слишком много шагов: ${steps.size}", steps.size in 3..14)
        // Сумма расстояний шагов ≈ длина маршрута.
        assertEquals(r.meters, steps.sumOf { it.distance }, r.meters * 0.15)
        // Прогресс: у начала — шаг 0, у конца — последний.
        val p0 = Navigation.progress(r, steps, a.floor, r.legs[0].points.first().first, r.legs[0].points.first().second)!!
        assertEquals(0, p0.step)
        val end = r.legs.last().points.last()
        val p1 = Navigation.progress(r, steps, b.floor, end.first, end.second)!!
        assertEquals(steps.lastIndex, p1.step)
        assertTrue(p1.left < 3)

        val up = site.rooms.first { it.floor == 3 && !it.isGeneric && it.reachable }
        val r2 = router.route(router.placeOf(a)!!, router.placeOf(up)!!)!!
        val s2 = Navigation.maneuvers(site, r2, up)
        s2.forEach { println("STEP2 ${it.kind} ${it.title} | ${it.detail}") }
        assertTrue(s2.any { it.kind == Maneuver.Kind.UP || it.kind == Maneuver.Kind.LIFT })
    }
}
