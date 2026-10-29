package cz.antonin.rovergnss.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Poloha umístěného bodu pro výpočet objektu (0.11.0). Rovina S-JTSK: [y], [x] (kladné, geodetické);
 * výpočet běží v e = −Y (východ), n = −X (sever). [h] výška (elipsoid), [sigma] přesnost vodorovně [m],
 * [suspect] podezřelý / bez fixu (malá váha).
 */
data class ObjObs(val name: String, val y: Double, val x: Double, val h: Double?, val sigma: Double, val suspect: Boolean)

/** Zdroj polohy vrcholu / délky stěny. */
object ObjSource {
    const val GNSS = 0
    const val AXES = 1
    const val TAPE = 2
    const val SKETCH = 3
    val NAMES = listOf("GNSS", "průsečík os", "pásmo", "náčrt")
}

/** Vrchol výsledku: e, n [m] (EPSG:5514), [sigma] odhad přesnosti vodorovně, [angle] vnitřní úhel [°]. */
data class ObjVertexRes(
    val index: Int, val id: Int, val name: String, val e: Double, val n: Double, val sigma: Double?,
    val source: Int, val angle: Double?, val h: Double?, val dh: Double?,
    // úhel mezi stěnami (0.11.7): [wallAngle] vypočtený, [angleFix] 0 bez vazby, 1 předpoklad 90°, 2 pásmem (trojúhelník);
    // [angleObs] předepsaný / změřený, [angleBad] výsledek se od něj liší víc, než odpovídá přesnosti
    val wallAngle: Double? = null, val angleFix: Int = 0, val angleObs: Double? = null, val angleBad: Boolean = false,
    val pointName: String? = null,   // změřený bod na rohu (B…)
    val angleExcluded: Boolean = false,   // úhel (pásmem / předpoklad 90°) jako hrubá chyba vynechán
    val angleDoubt: Boolean = false,      // jedna z možných příčin nejednoznačného rozporu
) {
    /** „V3“ nebo „V3 (B5)“. */
    val label: String get() = name + (pointName?.let { " ($it)" } ?: "")
    val y get() = -e
    val x get() = -n
}

/** Stěna výsledku (indexy vrcholů), délka, přesnost, zdroj, zadaná kóta pásmem (je-li). */
data class ObjEdgeRes(val a: Int, val b: Int, val len: Double, val sigma: Double?, val source: Int, val tape: Double?)

/** Umístěný bod: poloha antény a opravená (posun krabičky), odchylka od výsledku, chybný. */
data class ObjPlaceRes(
    val placeId: Int, val name: String, val type: Int, val e: Double, val n: Double, val antE: Double, val antN: Double,
    val residual: Double?, val sigma: Double, val bad: Boolean, val suspect: Boolean, val h: Double?, val dh: Double?,
    val excluded: Boolean = false,   // hrubá chyba – ve výpočtu vynechán
    val doubt: Boolean = false,      // jedna z možných příčin nejednoznačného rozporu
)

/** Kóta pásmem: zadaná a vypočtená délka (null = nelze), rozdíl zadaná − vypočtená. */
data class ObjTapeRes(
    val id: Int, val len: Double, val calc: Double?, val diff: Double?, val bad: Boolean, val excluded: Boolean = false,
    val doubt: Boolean = false,    // jedna z možných příčin nejednoznačného rozporu (oranžově)
    val approx: Boolean = false,   // přibližná kóta (z mapy)
)

/** Vazba stěn: [dev] odchylka od kolmosti / souběžnosti [°], rozpor, vynechána, podezřelá. */
data class ObjRelRes(val id: Int, val kind: Int, val dev: Double?, val bad: Boolean, val excluded: Boolean = false, val doubt: Boolean = false)

/** Měření vynechaná z výpočtu (hrubá chyba): kóty, umístěné body (id), úhly pásmem a předpoklady 90° (id vrcholů). */
data class ObjExcl(
    val tapes: Set<Int> = emptySet(), val places: Set<Int> = emptySet(), val angles: Set<Int> = emptySet(), val rights: Set<Int> = emptySet(),
    val rels: Set<Int> = emptySet(),
) {
    val empty get() = tapes.isEmpty() && places.isEmpty() && angles.isEmpty() && rights.isEmpty() && rels.isEmpty()
}

/**
 * Výsledek výpočtu objektu. [ok] = aspoň 2 změřené body (převod náčrtu); jinak jen náčrt.
 * [toWorld] / [toSketch] převod souřadnic náčrtu (y dolů) ↔ e, n.
 */
class ObjResult(
    val ok: Boolean,
    val message: String,
    val vertices: List<ObjVertexRes> = emptyList(),
    val edges: List<ObjEdgeRes> = emptyList(),
    val places: List<ObjPlaceRes> = emptyList(),
    val tapes: List<ObjTapeRes> = emptyList(),
    val perimeter: Double? = null,
    val area: Double? = null,
    val axisAz: Double? = null,
    val scale: Double? = null,       // metrů na jednotku náčrtu
    val sigma0: Double? = null,      // jednotková směrodatná odchylka vyrovnání (≈ 1 = měření odpovídají přesnostem)
    val redundancy: Int = 0,
    private val sim: DoubleArray? = null,   // [a, b, te, tn]: e + i n = (a + i b)(sx − i sy) + (te + i tn)
    private val cov: Array<DoubleArray>? = null,
    val local: Boolean = false,      // místní souřadnice (bez 2 změřených bodů) – bez exportu
    val scaleKnown: Boolean = true,  // měřítko z kót (místní)
    val conflict: Boolean = false,   // rozpor: chybné měření nebo úhel mimo předpoklad (bez vynechaných)
    val excludedText: String? = null,   // „kóta V1–V4 25,50 m“ – vynechaná hrubá chyba
    val fixCount: Int = 0,           // změřené body ve výpočtu: 0 místní, 1 jen posun (natočení neověřené), 2+ GNSS
    val rels: List<ObjRelRes> = emptyList(),
    val headline: String? = null,    // krátká hláška rozporu (jeden řádek), podrobnosti v [message]
) {
    /** Stejný výsledek s jinou zprávou, popisem vynechané hrubé chyby, krátkou hláškou a označením podezřelých. */
    fun copyMsg(
        msg: String, excluded: String?, head: String? = headline,
        vertices: List<ObjVertexRes> = this.vertices, places: List<ObjPlaceRes> = this.places,
        tapes: List<ObjTapeRes> = this.tapes, rels: List<ObjRelRes> = this.rels,
    ) = ObjResult(
        ok, msg, vertices, edges, places, tapes, perimeter, area, axisAz, scale, sigma0, redundancy, sim, cov, local, scaleKnown,
        conflict, excluded, fixCount, rels, head,
    )

    fun toWorld(sx: Double, sy: Double): DoubleArray? {
        val s = sim ?: return null
        val px = sx
        val py = -sy
        return doubleArrayOf(s[0] * px - s[1] * py + s[2], s[1] * px + s[0] * py + s[3])
    }

    fun toSketch(e: Double, n: Double): DoubleArray? {
        val s = sim ?: return null
        val d = s[0] * s[0] + s[1] * s[1]
        if (d == 0.0) return null
        val qe = e - s[2]
        val qn = n - s[3]
        // (qe + i qn) / (a + i b)
        val px = (qe * s[0] + qn * s[1]) / d
        val py = (qn * s[0] - qe * s[1]) / d
        return doubleArrayOf(px, -py)
    }

    /** Kovariance 2×2 vrcholů i, j (pro přesnost vzdálenosti). */
    fun cov(i: Int, j: Int): DoubleArray? {
        val c = cov ?: return null
        return doubleArrayOf(c[2 * i][2 * j], c[2 * i][2 * j + 1], c[2 * i + 1][2 * j], c[2 * i + 1][2 * j + 1])
    }
}

/**
 * Výpočet půdorysu objektu: náčrt (tvar a topologie) + změřené body + kóty pásmem → souřadnice vrcholů.
 *
 * 1. Podobnostní transformace náčrtu na změřené body (aspoň 2) – počáteční poloha a slabá vazba nezměřených vrcholů.
 * 2. Vyrovnání metodou nejmenších čtverců (Gauss–Newton), neznámé = e, n všech vrcholů. Měření a přesnosti:
 *    - roh změřený GNSS (opravený o posun krabičky 4,5 cm podle směrů stěn): σ z měření (podezřelý 0,25 m),
 *    - bod v ose stěny / na stěně: vzdálenost od přímky stěny = 0 (σ 3 cm / 2 cm + σ měření),
 *    - kóta pásmem: vzdálenost = délka (σ 1 cm + 0,5 mm/m),
 *    - náčrt: vektor každé stěny podle transformovaného náčrtu (σ 5 cm + 8 % délky – místní tvar je v náčrtu
 *      přesnější než celkové rozměry) a slabě i poloha vrcholu (σ 5 m).
 *    Počáteční hodnoty: nejdřív jen lineární vazby (náčrt + rohy), pak vše – správná větev u průsečíku
 *    přímky a kóty (vrchol leží tam, kam ho nakreslil náčrt vůči sousedům).
 * 3. Výsledky: vrcholy s přesností (kovariance), délky stěn, úhly, obvod, plocha, azimut osy, kontrola (odchylky).
 */
object ObjCalc {
    const val SKETCH_SIGMA = 5.0
    // předpoklad pravého úhlu ±0,3° (zděná stavba); při ±1° se rozpor kót (např. 50 cm na 20 m) tiše schoval do úhlů
    const val RIGHT_SIGMA_DEG = 0.3
    const val RIGHT_TOL_DEG = 0.5        // odchylka „pravého“ úhlu od 90° nad 0,5° = rozpor (kóta, nebo roh není pravý)

    /** Přesnost úhlu z trojúhelníku pásmem [°]: ~1 cm na kratší straně, aspoň 0,2°. */
    fun angleSigma(a: ObjAngle): Double = max(0.2, Math.toDegrees(0.01 / max(min(a.a, a.b), 0.1)))

    /** Úhel mezi stěnami ve vrcholu i (k sousedům p, q) [°]. */
    fun wallAngleDeg(x: DoubleArray, i: Int, p: Int, q: Int): Double {
        val e1 = x[2 * p] - x[2 * i]
        val n1 = x[2 * p + 1] - x[2 * i + 1]
        val e2 = x[2 * q] - x[2 * i]
        val n2 = x[2 * q + 1] - x[2 * i + 1]
        val l = hypot(e1, n1) * hypot(e2, n2)
        if (l < 1e-12) return 0.0
        return Math.toDegrees(Math.acos(((e1 * e2 + n1 * n2) / l).coerceIn(-1.0, 1.0)))
    }
    fun edgeSigma(len: Double) = 0.05 + 0.08 * len
    // nekótovaná stěna není měření (0.11.14): délka z náčrtu jen volné vodítko, směr o něco pevněji
    fun edgeLenSigma(len: Double) = max(1.0, 0.5 * len)
    fun edgeDirSigma(len: Double) = 0.1 + 0.15 * len
    /** Přesnost kóty: pásmem 1 cm + 0,5 mm/m, přibližná (z mapy) 30 cm + 1 %. */
    fun tapeSigmaOf(t: ObjTape) = if (t.approx) 0.30 + 0.01 * t.len else tapeSigma(t.len)

    /** Odchylka vazby stěn [°]: kolmé = asin(cos úhlu), souběžné = asin(sin úhlu) – 0 při splnění. */
    fun relDev(x: DoubleArray, a0: Int, a1: Int, b0: Int, b1: Int, kind: Int): Double {
        val ue = x[2 * a1] - x[2 * a0]
        val un = x[2 * a1 + 1] - x[2 * a0 + 1]
        val ve = x[2 * b1] - x[2 * b0]
        val vn = x[2 * b1 + 1] - x[2 * b0 + 1]
        val l = hypot(ue, un) * hypot(ve, vn)
        if (l < 1e-12) return 0.0
        val v = if (kind == ObjRel.PERP) (ue * ve + un * vn) / l else (ue * vn - un * ve) / l
        return Math.toDegrees(Math.asin(v.coerceIn(-1.0, 1.0)))
    }

    /** „1 chybné měření“, „2 chybná měření“, „5 chybných měření“. */
    fun badWord(n: Int) = "$n " + when {
        n == 1 -> "chybné měření"
        n in 2..4 -> "chybná měření"
        else -> "chybných měření"
    }
    const val AXIS_SIGMA = 0.03
    const val WALL_SIGMA = 0.02
    const val SUSPECT_SIGMA = 0.25
    fun tapeSigma(len: Double) = 0.01 + 0.0005 * len

    private class Eq(val w: Double, val f: (DoubleArray) -> Double, val obs: Double, val vars: IntArray, val sketch: Boolean = false)

    /**
     * Výpočet s vyhledáním hrubé chyby: při rozporu se zkusmo vynechá po jednom každá kóta, umístěný bod, úhel pásmem
     * a předpoklad 90°. Odstraní-li rozpor právě jedno vynechání, je to hrubá chyba – výsledek bez ní, měření
     * označené „vynecháno“. Odstraní-li ho víc možností (např. protější kóty obdélníku), nejde určit, které – jen výpis.
     */
    fun compute(sk: ObjSketch, obs: Map<Int, ObjObs>, controlH: Double? = null): ObjResult {
        val r0 = core(sk, obs, controlH, ObjExcl())
        if (!r0.ok || !r0.conflict) return r0
        val cands = ArrayList<Pair<ObjExcl, String>>()
        sk.tapes.forEach { t -> cands += ObjExcl(tapes = setOf(t.id)) to "kóta ${refLabel(sk, obs, t.a)}–${refLabel(sk, obs, t.b)} ${fmt2(t.len)} m" }
        sk.places.filter { obs.containsKey(it.id) }.forEach { p -> cands += ObjExcl(places = setOf(p.id)) to "bod ${obs[p.id]!!.name}" }
        sk.vertices.forEachIndexed { i, v ->
            val ta = sk.angles.lastOrNull { it.vertex == v.id && it.wallAngle != null }
            if (ta != null) cands += ObjExcl(angles = setOf(v.id)) to "úhel pásmem V${i + 1}"
            else if (sk.isRight(i)) cands += ObjExcl(rights = setOf(v.id)) to "pravý úhel V${i + 1}"
        }
        sk.rels.forEachIndexed { k, r -> cands += ObjExcl(rels = setOf(r.id)) to "vazba ${ObjRel.SYMBOL[r.kind]}${k + 1}" }
        val fixes = cands.mapNotNull { (ex, nm) -> core(sk, obs, controlH, ex).takeIf { it.ok && !it.conflict }?.let { Triple(ex, nm, it) } }
        return when (fixes.size) {
            0 -> r0
            1 -> {
                val (_, nm, r) = fixes[0]
                r.copyMsg("Hrubá chyba: $nm – ve výpočtu vynechána. " + r.message, nm, "⚠ Hrubá chyba: $nm – vynechána")
            }
            else -> {
                // nejednoznačné: všechny možné příčiny oranžově („podezřelé“), žádná červeně
                val ex = fixes.map { it.first }
                val tIds = ex.flatMap { it.tapes }.toSet()
                val pIds = ex.flatMap { it.places }.toSet()
                val vIds = ex.flatMap { it.angles + it.rights }.toSet()
                val rIds = ex.flatMap { it.rels }.toSet()
                r0.copyMsg(
                    r0.message + " – nelze určit, které (jedno z nich): " + fixes.joinToString(" / ") { it.second }, null,
                    "⚠ Rozpor – ${fixes.size} možných příčin (oranžově)",
                    // i důsledky (úhly, odchylky mimo kandidáty) jen oranžově – nic není určitě chybné
                    vertices = r0.vertices.map { if (it.id in vIds || it.angleBad) it.copy(angleDoubt = true, angleBad = false) else it },
                    places = r0.places.map { if (it.placeId in pIds || it.bad) it.copy(doubt = true, bad = false) else it },
                    tapes = r0.tapes.map { if (it.id in tIds || it.bad) it.copy(doubt = true, bad = false) else it },
                    rels = r0.rels.map { if (it.id in rIds || it.bad) it.copy(doubt = true, bad = false) else it },
                )
            }
        }
    }

    private fun fmt2(v: Double) = String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')

    /** Název prvku kóty: V3, nebo název změřeného bodu. */
    private fun refLabel(sk: ObjSketch, obs: Map<Int, ObjObs>, ref: String): String {
        val vi = refVertex(sk, ref)
        if (vi >= 0) return "V${vi + 1}"
        val id = ref.substring(2).toIntOrNull()
        val p = sk.places.firstOrNull { it.id == id }
        return p?.let { obs[it.id]?.name ?: it.label.ifEmpty { null } } ?: "?"
    }

    private fun core(skAll: ObjSketch, obsAll: Map<Int, ObjObs>, controlH: Double?, ex: ObjExcl): ObjResult {
        // vynechaná měření se do vyrovnání nedostanou; ve výsledcích jsou (odchylka od výsledku, „vynecháno“)
        val sk = if (ex.empty) skAll else skAll.copy(
            tapes = skAll.tapes.filter { it.id !in ex.tapes },
            angles = skAll.angles.filter { it.vertex !in ex.angles },
            right = skAll.right + ex.rights.associateWith { ObjRight.NO },
            rels = skAll.rels.filter { it.id !in ex.rels },
        )
        val obs = if (ex.places.isEmpty()) obsAll else obsAll - ex.places
        val nV = sk.vertices.size
        val edges = sk.edges()
        val placesWithObs = sk.places.filter { obs.containsKey(it.id) }

        // --- 1. podobnostní transformace (komplexně): q = a·p + t, p = sx − i·sy
        val corr = ArrayList<DoubleArray>()   // [px, py, e, n, w]
        // body mimo obrys (v ose, kontrolní) mají v náčrtu jen přibližné místo – do transformace jen, když nestačí rohy a body na stěně
        val onOutline = placesWithObs.filter { it.type == ObjPointType.CORNER || it.type == ObjPointType.WALL }
        val forSim = if (onOutline.size >= 2) onOutline else placesWithObs
        for (p in forSim) {
            val o = obs[p.id]!!
            val (sx, sy) = if (p.type == ObjPointType.CORNER) {
                val v = sk.vertices.getOrNull(sk.vIndex(p.vertex)) ?: continue
                v.x to v.y
            } else p.x to p.y
            val s = if (o.suspect) SUSPECT_SIGMA else max(o.sigma, 0.01)
            corr.add(doubleArrayOf(sx, -sy, -o.y, -o.x, 1 / (s * s)))
        }
        val spread = if (corr.size >= 2) corr.maxOf { a -> corr.maxOf { b -> hypot(a[0] - b[0], a[1] - b[1]) } } else 0.0
        // bez 2 změřených bodů: místní souřadnice (tvar z kót, úhlů a pravých úhlů; měřítko z kót) – 0.11.8
        var local = false
        var scaleKnown = true
        val simLocal: DoubleArray? = if (corr.size < 2 || spread < 1e-9) {
            val hasShape = nV >= 2 && (sk.tapes.isNotEmpty() || sk.angles.isNotEmpty() || sk.vertices.indices.any { sk.isRight(it) })
            if (!hasShape) return ObjResult(
                false,
                if (nV == 0) "Nakresli půdorys (režim Kreslit)."
                else "Pro výpočet zadej kótu pásmem nebo pravé úhly, pro polohu umísti aspoň 2 změřené body (režim Body).",
            )
            local = true
            val ratios = sk.tapes.mapNotNull { t ->
                val pa = sketchPos(sk, t.a) ?: return@mapNotNull null
                val pb = sketchPos(sk, t.b) ?: return@mapNotNull null
                val d = hypot(pa[0] - pb[0], pa[1] - pb[1])
                if (d > 1e-6) t.len / d else null
            }.sorted()
            val sc = ratios.getOrNull(ratios.size / 2) ?: run { scaleKnown = false; 0.05 }
            doubleArrayOf(sc, 0.0, 0.0, 0.0)
        } else null
        // místní výpočet (≤ 1 změřený bod): GNSS souřadnice do vyrovnání nevstupují (0.11.13 – dřív jeden bod
        // v S-JTSK „táhl“ stěnu stovky km daleko); jeden bod náčrt po výpočtu jen posune
        val obsEq: Map<Int, ObjObs> = if (local) emptyMap() else obs
        val sw = corr.sumOf { it[4] }
        val pcx = corr.sumOf { it[0] * it[4] } / sw
        val pcy = corr.sumOf { it[1] * it[4] } / sw
        val qce = corr.sumOf { it[2] * it[4] } / sw
        val qcn = corr.sumOf { it[3] * it[4] } / sw
        var num1 = 0.0
        var num2 = 0.0
        var den = 0.0
        for (c in corr) {
            val px = c[0] - pcx
            val py = c[1] - pcy
            val qe = c[2] - qce
            val qn = c[3] - qcn
            // a = Σ w q conj(p) / Σ w |p|²
            num1 += c[4] * (qe * px + qn * py)
            num2 += c[4] * (qn * px - qe * py)
            den += c[4] * (px * px + py * py)
        }
        val sim = simLocal ?: (num1 / den).let { a0 -> (num2 / den).let { b0 -> doubleArrayOf(a0, b0, qce - (a0 * pcx - b0 * pcy), qcn - (b0 * pcx + a0 * pcy)) } }
        val a = sim[0]
        val b = sim[1]
        fun skW(sx: Double, sy: Double) = doubleArrayOf(a * sx - b * (-sy) + sim[2], b * sx + a * (-sy) + sim[3])
        val scale = hypot(a, b)
        val sketchW = sk.vertices.map { skW(it.x, it.y) }

        if (nV == 0) return ObjResult(true, "Bez půdorysu – jen body.", sim = sim, scale = scale,
            places = placeResults(sk, obs, null, edges, controlH))

        // --- 2. vyrovnání
        val X = DoubleArray(2 * nV)
        sketchW.forEachIndexed { i, w -> X[2 * i] = w[0]; X[2 * i + 1] = w[1] }
        val signedArea = { x: DoubleArray -> polyArea(x, nV) }
        var cov: Array<DoubleArray>? = null
        var s0: Double? = null
        var red = 0
        var eqs: List<Eq> = emptyList()
        for (iter in 0 until 25) {
            eqs = buildEqs(sk, obsEq, X, edges, sketchW, signedArea(X) >= 0, linearOnly = iter == 0)
            val u = 2 * nV
            val nMat = Array(u) { DoubleArray(u) }
            val g = DoubleArray(u)
            for (q in eqs) {
                val f0 = q.f(X)
                val r = q.obs - f0
                val jac = DoubleArray(q.vars.size)
                for ((k, vi) in q.vars.withIndex()) {
                    val h = 1e-5
                    val old = X[vi]
                    X[vi] = old + h
                    val fp = q.f(X)
                    X[vi] = old - h
                    val fm = q.f(X)
                    X[vi] = old
                    jac[k] = (fp - fm) / (2 * h)
                }
                for (k in q.vars.indices) {
                    g[q.vars[k]] += q.w * jac[k] * r
                    for (l in q.vars.indices) nMat[q.vars[k]][q.vars[l]] += q.w * jac[k] * jac[l]
                }
            }
            val inv = invert(nMat) ?: break
            var maxDx = 0.0
            for (i in 0 until u) {
                var dx = 0.0
                for (j in 0 until u) dx += inv[i][j] * g[j]
                X[i] += dx
                maxDx = max(maxDx, abs(dx))
            }
            cov = inv
            if (iter > 0 && maxDx < 1e-6) break
        }
        // jednotková σ0 (bez vazeb náčrtu)
        run {
            var vv = 0.0
            var m = 0
            for (q in eqs) {
                if (q.sketch) continue
                val r = q.obs - q.f(X)
                vv += q.w * r * r
                m++
            }
            val unknownsFixed = 2 * nV - (0 until nV).count { i -> vertexSource(sk, i, obsEq, edges, emptySet()) == ObjSource.SKETCH } * 2
            red = m - unknownsFixed
            s0 = if (red > 0) sqrt(vv / red) else null
        }

        // jeden změřený bod: celý vypočtený tvar se posune, aby bod seděl (tuhý posun, natočení podle náčrtu)
        var simF = sim
        val one = local && placesWithObs.size == 1 && scaleKnown
        if (one) {
            val p = placesWithObs[0]
            val c = corrected(sk, p, obs[p.id]!!, X, edges, signedArea(X) >= 0)
            val lp = localPos(sk, p, X, edges) { x, y -> skW(x, y) }
            if (lp != null) {
                val de = c[0] - lp[0]
                val dn = c[1] - lp[1]
                for (i in 0 until nV) {
                    X[2 * i] += de
                    X[2 * i + 1] += dn
                }
                simF = sim.copyOf().also { it[2] += de; it[3] += dn }
            }
        }
        val fixCount = if (one) 1 else if (local) 0 else placesWithObs.size

        // --- 3. výsledky
        val ccw = signedArea(X) >= 0
        val placeRes = placeResults(skAll, obsAll, X, edges, controlH).map { if (it.placeId in ex.places) it.copy(bad = true, excluded = true) else it }
        val badPlaces = placeRes.filter { it.bad }.map { it.placeId }.toSet()
        val vRes = (0 until nV).map { i ->
            val v = sk.vertices[i]
            val corner = sk.places.firstOrNull { it.type == ObjPointType.CORNER && it.vertex == v.id && obs.containsKey(it.id) }
            val src = vertexSource(sk, i, obsEq, edges, badPlaces)
            val c = cov
            val sg = if (c != null) sqrt(max(0.0, c[2 * i][2 * i] + c[2 * i + 1][2 * i + 1])) else null
            val ang = interiorAngle(X, i, nV, sk.closed, ccw)
            val h = corner?.let { obs[it.id]?.h }
            val wa = sk.neighbors(i)?.let { (p, q) -> wallAngleDeg(X, i, p, q) }
            val tapeAng = skAll.angles.lastOrNull { it.vertex == v.id && it.wallAngle != null }
            val fix = when {
                tapeAng != null -> 2
                skAll.isRight(i) -> 1
                else -> 0
            }
            val angEx = v.id in ex.angles || v.id in ex.rights
            val obsAng = if (fix == 2) tapeAng!!.wallAngle else if (fix == 1) 90.0 else null
            val bad = wa != null && obsAng != null && abs(wa - obsAng) > (if (fix == 2) max(3 * angleSigma(tapeAng!!), 0.5) else RIGHT_TOL_DEG)
            ObjVertexRes(i, v.id, "V${i + 1}", X[2 * i], X[2 * i + 1], sg, src, ang, h,
                if (h != null && controlH != null) h - controlH else null, wa, fix, obsAng, bad || angEx, corner?.let { obs[it.id]?.name }, angEx)
        }
        val tapeOf = { ia: Int, ib: Int ->
            sk.tapes.firstOrNull { t ->
                val ra = refVertex(sk, t.a)
                val rb = refVertex(sk, t.b)
                (ra == ia && rb == ib) || (ra == ib && rb == ia)
            }
        }
        val eRes = edges.map { (ia, ib) ->
            val de = X[2 * ib] - X[2 * ia]
            val dn = X[2 * ib + 1] - X[2 * ia + 1]
            val len = hypot(de, dn)
            val sg = cov?.let { c ->
                // ∂L/∂(ea, na, eb, nb) = (−de, −dn, de, dn)/L
                val jv = doubleArrayOf(-de / len, -dn / len, de / len, dn / len)
                val idx = intArrayOf(2 * ia, 2 * ia + 1, 2 * ib, 2 * ib + 1)
                var s = 0.0
                for (k in 0..3) for (l in 0..3) s += jv[k] * c[idx[k]][idx[l]] * jv[l]
                sqrt(max(0.0, s))
            }
            val tp = tapeOf(ia, ib)
            val sa = vRes[ia].source
            val sb = vRes[ib].source
            val src = when {
                tp != null -> ObjSource.TAPE
                sa != ObjSource.SKETCH && sb != ObjSource.SKETCH -> min(max(sa, sb), ObjSource.TAPE)
                else -> ObjSource.SKETCH
            }
            ObjEdgeRes(ia, ib, len, sg, src, tp?.len)
        }
        val tRes = skAll.tapes.map { t ->
            val pa = refPos(skAll, t.a, X, obsAll, edges, ccw)
            val pb = refPos(skAll, t.b, X, obsAll, edges, ccw)
            val calc = if (pa != null && pb != null) hypot(pa[0] - pb[0], pa[1] - pb[1]) else null
            val diff = calc?.let { t.len - it }
            val exT = t.id in ex.tapes
            ObjTapeRes(t.id, t.len, calc, diff, exT || (!t.approx && diff != null && abs(diff) > max(3 * tapeSigma(t.len), 0.05)), exT,
                approx = t.approx)
        }
        val relRes = skAll.rels.map { r ->
            val ea = edges.getOrNull(skAll.edgeIndex(r.a))
            val eb = edges.getOrNull(skAll.edgeIndex(r.b))
            val dev = if (ea != null && eb != null && ea != eb) relDev(X, ea.first, ea.second, eb.first, eb.second, r.kind) else null
            val exR = r.id in ex.rels
            ObjRelRes(r.id, r.kind, dev, exR || (dev != null && abs(dev) > RIGHT_TOL_DEG), exR)
        }
        var per: Double? = null
        var area: Double? = null
        if (sk.closed && nV >= 3) {
            per = eRes.sumOf { it.len }
            area = abs(polyArea(X, nV))
        } else if (nV >= 2) per = eRes.sumOf { it.len }
        val axis = if (nV >= 2) principalAz(X, nV) else null
        val nGnss = vRes.count { it.source == ObjSource.GNSS }
        val msg = buildString {
            if (one) append("Poloha z 1 bodu (natočení neověřené), měřítko z kót: ")
            else if (local) append(if (scaleKnown) "Místní souřadnice (bez GNSS), měřítko z kót: " else "Bez měřítka (zadej kótu), jen tvar: ")
            append("Vypočteno: ${placesWithObs.size} změřených bodů, ${sk.tapes.size} kót")
            if (nGnss > 0) append(", ${nGnss} vrcholů z GNSS")
            val nb = placeRes.count { it.bad && !it.excluded } + tRes.count { it.bad && !it.excluded }
            if (nb > 0) append(" – " + badWord(nb))
            val rb = relRes.filter { it.bad && !it.excluded }
            if (rb.isNotEmpty()) append(" – rozpor vazeb: " + rb.joinToString(", ") { r ->
                ObjRel.SYMBOL[r.kind] + (skAll.rels.indexOfFirst { it.id == r.id } + 1) + String.format(java.util.Locale.US, " %.1f°", r.dev ?: 0.0).replace('.', ',')
            })
            val ab = vRes.filter { it.angleBad && !it.angleExcluded }
            if (ab.isNotEmpty()) append(" – rozpor úhlů: " + ab.joinToString(", ") { v ->
                String.format(java.util.Locale.US, "%s %.1f°", v.name, v.wallAngle ?: 0.0).replace('.', ',')
            } + " (kóta, nebo roh není pravý)")
        }
        val conflict = placeRes.any { it.bad && !it.excluded } || tRes.any { it.bad && !it.excluded } ||
            vRes.any { it.angleBad && !it.angleExcluded } || relRes.any { it.bad && !it.excluded }
        return ObjResult(true, msg, vRes, eRes, placeRes, tRes, per, area, axis, scale, s0, red, simF, cov, local && !one, scaleKnown, conflict,
            fixCount = fixCount, rels = relRes, headline = if (conflict) "⚠ Rozpor – " + badWord(
                placeRes.count { it.bad && !it.excluded } + tRes.count { it.bad && !it.excluded } +
                    vRes.count { it.angleBad && !it.angleExcluded } + relRes.count { it.bad && !it.excluded }) else null)
    }

    /** Poloha umístěného bodu v (místním) výsledku: roh = vrchol, na stěně / v ose = stejný poměr podél stěny, jinak z náčrtu. */
    private fun localPos(sk: ObjSketch, p: ObjPlace, X: DoubleArray, edges: List<Pair<Int, Int>>, skW: (Double, Double) -> DoubleArray): DoubleArray? {
        when (p.type) {
            ObjPointType.CORNER -> return sk.vIndex(p.vertex).takeIf { it >= 0 }?.let { doubleArrayOf(X[2 * it], X[2 * it + 1]) }
            ObjPointType.WALL, ObjPointType.AXIS -> {
                val (ia, ib) = edges.getOrNull(sk.edgeIndex(p.edge)) ?: return skW(p.x, p.y)
                val a = sk.vertices[ia]
                val b = sk.vertices[ib]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val l2 = dx * dx + dy * dy
                if (l2 < 1e-12) return skW(p.x, p.y)
                val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2
                return doubleArrayOf(X[2 * ia] + t * (X[2 * ib] - X[2 * ia]), X[2 * ia + 1] + t * (X[2 * ib + 1] - X[2 * ia + 1]))
            }
            else -> return skW(p.x, p.y)
        }
    }

    /** Zdroj polohy vrcholu i. */
    private fun vertexSource(sk: ObjSketch, i: Int, obs: Map<Int, ObjObs>, edges: List<Pair<Int, Int>>, bad: Set<Int>): Int {
        val v = sk.vertices[i]
        val gn = sk.places.any {
            it.type == ObjPointType.CORNER && it.vertex == v.id && obs[it.id]?.let { o -> !o.suspect } == true && it.id !in bad
        }
        if (gn) return ObjSource.GNSS
        val adj = edges.withIndex().filter { (_, e) -> e.first == i || e.second == i }
        val lineDefined = { ei: Int ->
            val start = sk.vertices[edges[ei].first].id
            sk.places.count { (it.type == ObjPointType.AXIS || it.type == ObjPointType.WALL) && it.edge == start && obs.containsKey(it.id) } >= 2
        }
        if (adj.size == 2 && adj.all { lineDefined(it.index) }) return ObjSource.AXES
        val tapes = sk.tapes.any { refVertex(sk, it.a) == i || refVertex(sk, it.b) == i }
        if (tapes) return ObjSource.TAPE
        return ObjSource.SKETCH
    }

    /** Poloha prvku kóty v náčrtu (vrchol, nebo místo umístěného bodu). */
    private fun sketchPos(sk: ObjSketch, ref: String): DoubleArray? {
        val vi = refVertex(sk, ref)
        if (vi >= 0) return sk.vertices[vi].let { doubleArrayOf(it.x, it.y) }
        val id = ref.substring(2).toIntOrNull() ?: return null
        return sk.places.firstOrNull { it.id == id }?.let { doubleArrayOf(it.x, it.y) }
    }

    /** Odkaz kóty → index vrcholu (vrchol nebo roh), jinak −1. */
    fun refVertex(sk: ObjSketch, ref: String): Int {
        val id = ref.substring(2).toIntOrNull() ?: return -1
        return when {
            ref.startsWith("v:") -> sk.vIndex(id)
            ref.startsWith("p:") -> sk.places.firstOrNull { it.id == id }?.takeIf { it.type == ObjPointType.CORNER }?.let { sk.vIndex(it.vertex) } ?: -1
            else -> -1
        }
    }

    /** Poloha prvku kóty (vrchol z vyrovnání, umístěný bod opravený o posun). */
    private fun refPos(sk: ObjSketch, ref: String, X: DoubleArray, obs: Map<Int, ObjObs>, edges: List<Pair<Int, Int>>, ccw: Boolean): DoubleArray? {
        val vi = refVertex(sk, ref)
        if (vi >= 0) return doubleArrayOf(X[2 * vi], X[2 * vi + 1])
        val id = ref.substring(2).toIntOrNull() ?: return null
        val p = sk.places.firstOrNull { it.id == id } ?: return null
        val o = obs[p.id] ?: return null
        return corrected(sk, p, o, X, edges, ccw)
    }

    /** Jednotkové směry stěn z vrcholu i k sousedům (podle aktuálních souřadnic). */
    private fun wallDirs(X: DoubleArray, i: Int, edges: List<Pair<Int, Int>>): List<DoubleArray> =
        edges.filter { it.first == i || it.second == i }.map { e ->
            val j = if (e.first == i) e.second else e.first
            val de = X[2 * j] - X[2 * i]
            val dn = X[2 * j + 1] - X[2 * i + 1]
            val l = hypot(de, dn).takeIf { it > 1e-9 } ?: 1.0
            doubleArrayOf(de / l, dn / l)
        }

    /** Vnější normála stěny (a → b) při orientaci obrysu [ccw]. */
    private fun outNormal(X: DoubleArray, ia: Int, ib: Int, ccw: Boolean): DoubleArray {
        val de = X[2 * ib] - X[2 * ia]
        val dn = X[2 * ib + 1] - X[2 * ia + 1]
        val l = hypot(de, dn).takeIf { it > 1e-9 } ?: 1.0
        // proti směru hodinových ručiček je vnitřek vlevo → vnější normála vpravo (dn, −de)
        return if (ccw) doubleArrayOf(dn / l, -de / l) else doubleArrayOf(-dn / l, de / l)
    }

    /**
     * Poloha bodu opravená o posun krabičky: roh = anténa ± d/sin(θ/2) ve směru osy úhlu stěn (vnější +, vnitřní −),
     * na stěně = anténa − d · vnější normála. Bez souřadnic vrcholů (X = null) bez opravy.
     */
    private fun corrected(sk: ObjSketch, p: ObjPlace, o: ObjObs, X: DoubleArray?, edges: List<Pair<Int, Int>>, ccw: Boolean): DoubleArray {
        val e = -o.y
        val n = -o.x
        if (X == null || p.mount == ObjMount.FREE) return doubleArrayOf(e, n)
        val d = ObjMount.OFFSET_M
        when (p.type) {
            ObjPointType.CORNER -> {
                val i = sk.vIndex(p.vertex)
                if (i < 0) return doubleArrayOf(e, n)
                val dirs = wallDirs(X, i, edges)
                if (dirs.isEmpty()) return doubleArrayOf(e, n)
                if (dirs.size == 1) {
                    // konec otevřeného obrysu: jen podél stěny
                    val s = if (p.mount == ObjMount.INNER) -1 else 1
                    return doubleArrayOf(e + s * d * dirs[0][0], n + s * d * dirs[0][1])
                }
                val be = dirs[0][0] + dirs[1][0]
                val bn = dirs[0][1] + dirs[1][1]
                val bl = hypot(be, bn)
                if (bl < 1e-6) return doubleArrayOf(e, n)
                val cosT = (dirs[0][0] * dirs[1][0] + dirs[0][1] * dirs[1][1]).coerceIn(-1.0, 1.0)
                val half = Math.acos(cosT) / 2
                val k = d / max(sin(half), sin(Math.toRadians(15.0)))
                val s = if (p.mount == ObjMount.INNER) -1 else 1
                return doubleArrayOf(e + s * k * be / bl, n + s * k * bn / bl)
            }
            ObjPointType.WALL -> {
                val ei = sk.edgeIndex(p.edge)
                if (ei < 0) return doubleArrayOf(e, n)
                val nn = outNormal(X, edges[ei].first, edges[ei].second, ccw)
                return doubleArrayOf(e - d * nn[0], n - d * nn[1])
            }
            else -> return doubleArrayOf(e, n)
        }
    }

    private fun buildEqs(
        sk: ObjSketch, obs: Map<Int, ObjObs>, X: DoubleArray, edges: List<Pair<Int, Int>>, sketchW: List<DoubleArray>, ccw: Boolean,
        linearOnly: Boolean = false,
    ): List<Eq> {
        val l = ArrayList<Eq>()
        // náčrt: slabě poloha, silněji vektory stěn (místní tvar)
        val ws = 1 / (SKETCH_SIGMA * SKETCH_SIGMA)
        sketchW.forEachIndexed { i, w ->
            l.add(Eq(ws, { x -> x[2 * i] }, w[0], intArrayOf(2 * i), true))
            l.add(Eq(ws, { x -> x[2 * i + 1] }, w[1], intArrayOf(2 * i + 1), true))
        }
        for ((ia, ib) in edges) {
            // vektor stěny z náčrtu: podél (délka) jen volně, napříč (směr) pevněji – délku určí kóty, úhly a GPS
            val ve = sketchW[ib][0] - sketchW[ia][0]
            val vn = sketchW[ib][1] - sketchW[ia][1]
            val len = hypot(ve, vn)
            if (len < 1e-9) continue
            val ue = ve / len
            val un = vn / len
            val sl = edgeLenSigma(len)
            val sd = edgeDirSigma(len)
            val vars = intArrayOf(2 * ia, 2 * ia + 1, 2 * ib, 2 * ib + 1)
            l.add(Eq(1 / (sl * sl), { x -> (x[2 * ib] - x[2 * ia]) * ue + (x[2 * ib + 1] - x[2 * ia + 1]) * un }, len, vars, true))
            l.add(Eq(1 / (sd * sd), { x -> -(x[2 * ib] - x[2 * ia]) * un + (x[2 * ib + 1] - x[2 * ia + 1]) * ue }, 0.0, vars, true))
        }
        for (p in sk.places) {
            val o = obs[p.id] ?: continue
            val so = if (o.suspect) SUSPECT_SIGMA else max(o.sigma, 0.005)
            when (p.type) {
                ObjPointType.CORNER -> {
                    val i = sk.vIndex(p.vertex)
                    if (i < 0) continue
                    val c = corrected(sk, p, o, X, edges, ccw)
                    val w = 1 / (so * so)
                    l.add(Eq(w, { x -> x[2 * i] }, c[0], intArrayOf(2 * i)))
                    l.add(Eq(w, { x -> x[2 * i + 1] }, c[1], intArrayOf(2 * i + 1)))
                }
                ObjPointType.AXIS, ObjPointType.WALL -> {
                    if (linearOnly) continue
                    val ei = sk.edgeIndex(p.edge)
                    if (ei < 0) continue
                    val (ia, ib) = edges[ei]
                    val c = corrected(sk, p, o, X, edges, ccw)
                    val sl = if (p.type == ObjPointType.AXIS) AXIS_SIGMA else WALL_SIGMA
                    val s = sqrt(so * so + sl * sl)
                    l.add(Eq(1 / (s * s), { x -> lineDist(x, ia, ib, c[0], c[1]) }, 0.0, intArrayOf(2 * ia, 2 * ia + 1, 2 * ib, 2 * ib + 1)))
                }
            }
        }
        // úhly mezi stěnami: pásmem (trojúhelník) a předpoklad pravého úhlu (0.11.7)
        if (!linearOnly) for (i in sk.vertices.indices) {
            val (p, q) = sk.neighbors(i) ?: continue
            val vid = sk.vertices[i].id
            val ta = sk.angles.lastOrNull { it.vertex == vid && it.wallAngle != null }
            val (obsDeg, sd) = when {
                ta != null -> ta.wallAngle!! to angleSigma(ta)
                sk.isRight(i) -> 90.0 to RIGHT_SIGMA_DEG
                else -> continue
            }
            l.add(Eq(1 / (sd * sd), { x -> wallAngleDeg(x, i, p, q) }, obsDeg,
                intArrayOf(2 * i, 2 * i + 1, 2 * p, 2 * p + 1, 2 * q, 2 * q + 1)))
        }
        // vazby stěn (kolmé / souběžné, i nesousední – 0.11.14)
        if (!linearOnly) for (r in sk.rels) {
            val ea = edges.getOrNull(sk.edgeIndex(r.a)) ?: continue
            val eb = edges.getOrNull(sk.edgeIndex(r.b)) ?: continue
            if (ea == eb) continue
            val sd = RIGHT_SIGMA_DEG
            l.add(Eq(1 / (sd * sd), { x -> relDev(x, ea.first, ea.second, eb.first, eb.second, r.kind) }, 0.0,
                intArrayOf(2 * ea.first, 2 * ea.first + 1, 2 * ea.second, 2 * ea.second + 1, 2 * eb.first, 2 * eb.first + 1, 2 * eb.second, 2 * eb.second + 1)))
        }
        for (t in sk.tapes) {
            if (linearOnly) break
            val va = refVertex(sk, t.a)
            val vb = refVertex(sk, t.b)
            val st = tapeSigmaOf(t)
            val w = 1 / (st * st)
            when {
                va >= 0 && vb >= 0 && va != vb -> l.add(Eq(w, { x -> hypot(x[2 * vb] - x[2 * va], x[2 * vb + 1] - x[2 * va + 1]) }, t.len,
                    intArrayOf(2 * va, 2 * va + 1, 2 * vb, 2 * vb + 1)))
                va >= 0 && vb < 0 -> refPos(sk, t.b, X, obs, edges, ccw)?.let { pb ->
                    l.add(Eq(w, { x -> hypot(pb[0] - x[2 * va], pb[1] - x[2 * va + 1]) }, t.len, intArrayOf(2 * va, 2 * va + 1)))
                }
                vb >= 0 && va < 0 -> refPos(sk, t.a, X, obs, edges, ccw)?.let { pa ->
                    l.add(Eq(w, { x -> hypot(pa[0] - x[2 * vb], pa[1] - x[2 * vb + 1]) }, t.len, intArrayOf(2 * vb, 2 * vb + 1)))
                }
            }
        }
        return l
    }

    /** Znaménková vzdálenost bodu (pe, pn) od přímky vrcholů ia → ib. */
    private fun lineDist(x: DoubleArray, ia: Int, ib: Int, pe: Double, pn: Double): Double {
        val de = x[2 * ib] - x[2 * ia]
        val dn = x[2 * ib + 1] - x[2 * ia + 1]
        val l = hypot(de, dn).takeIf { it > 1e-9 } ?: return 0.0
        return (de * (pn - x[2 * ia + 1]) - dn * (pe - x[2 * ia])) / l
    }

    private fun placeResults(sk: ObjSketch, obs: Map<Int, ObjObs>, X: DoubleArray?, edges: List<Pair<Int, Int>>, controlH: Double?): List<ObjPlaceRes> {
        val nV = sk.vertices.size
        val ccw = X != null && polyArea(X, nV) >= 0
        // výška kontrolního bodu: umístěný kontrolní bod, jinak bod lokality označený „kontrolní“
        val ctrl = sk.places.firstOrNull { it.type == ObjPointType.CONTROL && obs[it.id]?.h != null }?.let { obs[it.id]?.h } ?: controlH
        return sk.places.mapNotNull { p ->
            val o = obs[p.id] ?: return@mapNotNull null
            val c = corrected(sk, p, o, X, edges, ccw)
            val so = if (o.suspect) SUSPECT_SIGMA else max(o.sigma, 0.005)
            var res: Double? = null
            var lim = 0.05
            if (X != null) when (p.type) {
                ObjPointType.CORNER -> sk.vIndex(p.vertex).takeIf { it >= 0 }?.let { i ->
                    res = hypot(X[2 * i] - c[0], X[2 * i + 1] - c[1])
                    lim = max(3 * so, 0.05)
                }
                ObjPointType.AXIS, ObjPointType.WALL -> sk.edgeIndex(p.edge).takeIf { it >= 0 }?.let { ei ->
                    res = abs(lineDist(X, edges[ei].first, edges[ei].second, c[0], c[1]))
                    lim = max(3 * sqrt(so * so + AXIS_SIGMA * AXIS_SIGMA), 0.05)
                }
            }
            ObjPlaceRes(
                p.id, o.name, p.type, c[0], c[1], -o.y, -o.x, res, so, res?.let { it > lim } == true, o.suspect,
                o.h, if (o.h != null && ctrl != null && p.type != ObjPointType.CONTROL) o.h - ctrl else null,
            )
        }
    }

    private fun polyArea(x: DoubleArray, n: Int): Double {
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += x[2 * i] * x[2 * j + 1] - x[2 * j] * x[2 * i + 1]
        }
        return s / 2
    }

    /** Vnitřní úhel ve vrcholu i [°] (uzavřený obrys; u otevřeného jen vnitřní vrcholy, úhel mezi stěnami). */
    private fun interiorAngle(x: DoubleArray, i: Int, n: Int, closed: Boolean, ccw: Boolean): Double? {
        if (n < 3) return null
        val prev = if (i == 0) (if (closed) n - 1 else return null) else i - 1
        val next = if (i == n - 1) (if (closed) 0 else return null) else i + 1
        val a1 = atan2(x[2 * prev + 1] - x[2 * i + 1], x[2 * prev] - x[2 * i])
        val a2 = atan2(x[2 * next + 1] - x[2 * i + 1], x[2 * next] - x[2 * i])
        // proti směru hodinových ručiček: vnitřek vlevo → úhel od směru k dalšímu k směru k předchozímu
        var ang = Math.toDegrees(if (ccw) a1 - a2 else a2 - a1)
        while (ang < 0) ang += 360.0
        while (ang >= 360) ang -= 360.0
        return if (closed) ang else min(ang, 360 - ang)
    }

    /** Azimut hlavní osy vrcholů [°] 0–180 (od severu po směru hodinových ručiček). */
    private fun principalAz(x: DoubleArray, n: Int): Double {
        val me = (0 until n).sumOf { x[2 * it] } / n
        val mn = (0 until n).sumOf { x[2 * it + 1] } / n
        var see = 0.0
        var snn = 0.0
        var sen = 0.0
        for (i in 0 until n) {
            val de = x[2 * i] - me
            val dn = x[2 * i + 1] - mn
            see += de * de
            snn += dn * dn
            sen += de * dn
        }
        val th = 0.5 * atan2(2 * sen, see - snn)   // úhel od osy e
        val de = cos(th)
        val dn = sin(th)
        var az = Math.toDegrees(atan2(de, dn))
        while (az < 0) az += 180.0
        while (az >= 180) az -= 180.0
        return az
    }

    /** Inverze symetrické matice (Gauss–Jordan s pivotací); null = singulární. */
    fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = m.size
        val a = Array(n) { i -> DoubleArray(2 * n).also { r -> for (j in 0 until n) r[j] = m[i][j]; r[n + i] = 1.0 } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-14) return null
            if (p != c) {
                val t = a[p]; a[p] = a[c]; a[c] = t
            }
            val d = a[c][c]
            for (j in 0 until 2 * n) a[c][j] /= d
            for (r in 0 until n) if (r != c) {
                val f = a[r][c]
                if (f != 0.0) for (j in 0 until 2 * n) a[r][j] -= f * a[c][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][n + j] } }
    }

    /** Vzdálenost, azimut [°] a přesnost mezi dvěma prvky (odkazy „v:id“ / „p:id“). */
    fun measure(sk: ObjSketch, r: ObjResult, ra: String, rb: String): Triple<Double, Double, Double?>? {
        fun pos(ref: String): Pair<DoubleArray, Int>? {
            val vi = refVertex(sk, ref)
            if (vi >= 0) return r.vertices.getOrNull(vi)?.let { doubleArrayOf(it.e, it.n) to vi }
            val id = ref.substring(2).toIntOrNull() ?: return null
            return r.places.firstOrNull { it.placeId == id }?.let { doubleArrayOf(it.e, it.n) to -1 - it.placeId }
        }
        val (pa, ka) = pos(ra) ?: return null
        val (pb, kb) = pos(rb) ?: return null
        val de = pb[0] - pa[0]
        val dn = pb[1] - pa[1]
        val d = hypot(de, dn)
        var az = Math.toDegrees(atan2(de, dn))
        if (az < 0) az += 360.0
        val sg: Double? = if (d < 1e-9) null else {
            val ue = de / d
            val un = dn / d
            fun varAlong(k: Int, other: Int): Double {
                if (k >= 0) {
                    val c = r.cov(k, k) ?: return 0.0
                    return ue * ue * c[0] + ue * un * (c[1] + c[2]) + un * un * c[3]
                }
                val s = r.places.firstOrNull { -1 - it.placeId == k }?.sigma ?: 0.0
                return s * s / 2
            }
            var v = varAlong(ka, kb) + varAlong(kb, ka)
            if (ka >= 0 && kb >= 0) {
                val c = r.cov(ka, kb)
                if (c != null) v -= 2 * (ue * ue * c[0] + ue * un * (c[1] + c[2]) + un * un * c[3])
            }
            sqrt(max(0.0, v))
        }
        return Triple(d, az, sg)
    }
}
