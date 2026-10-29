package cz.antonin.rovergnss.export

import cz.antonin.rovergnss.data.ObjCalc
import cz.antonin.rovergnss.data.ObjMount
import cz.antonin.rovergnss.data.ObjPointType
import cz.antonin.rovergnss.data.ObjResult
import cz.antonin.rovergnss.data.ObjSketch
import cz.antonin.rovergnss.data.ObjSource
import cz.antonin.rovergnss.geo.Sjtsk
import cz.antonin.rovergnss.geo.Utm
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Export objektu (0.11.0): GeoPackage pro QGIS (EPSG:5514 nebo 25833) s vrstvami body, vrcholy, steny, pudorys, koty
 * a CSV vrcholů. Jedna souřadnice na bod (výsledek), ne epochy.
 */
object ObjExport {
    private fun cm(m: Double?) = m?.let { Math.round(it * 1000) / 10.0 }
    private fun r3(m: Double?) = m?.let { Math.round(it * 1000) / 1000.0 }

    /** Převod e, n (EPSG:5514) do zvoleného systému. */
    private fun conv(srs: Int): (Double, Double) -> DoubleArray = when (srs) {
        25833 -> { e, n ->
            val g = Sjtsk.toEtrs(-e, -n, null).first
            Utm.fromEtrs(g.lat, g.lon)
        }
        else -> { e, n -> doubleArrayOf(e, n) }
    }

    /** Odkaz kóty → název prvku. */
    private fun refName(sk: ObjSketch, r: ObjResult, ref: String): String {
        val vi = ObjCalc.refVertex(sk, ref)
        if (vi >= 0) return r.vertices.getOrNull(vi)?.name ?: "V${vi + 1}"
        val id = ref.substring(2).toIntOrNull()
        return r.places.firstOrNull { it.placeId == id }?.name ?: ref
    }

    fun gpkg(file: File, srs: Gpkg.Srs, name: String, sk: ObjSketch, r: ObjResult, fixPct: Map<Int, Double?>) {
        val c = conv(srs.id)
        val T = Gpkg.Column("nazev", "TEXT")
        val layers = ArrayList<Gpkg.Layer>()
        // body (umístěné, opravené o posun krabičky)
        layers += Gpkg.Layer(
            "body", "POINT",
            listOf(T, Gpkg.Column("typ", "TEXT"), Gpkg.Column("prilozeni", "TEXT"), Gpkg.Column("zdroj", "TEXT"),
                Gpkg.Column("presnost_cm", "REAL"), Gpkg.Column("odchylka_cm", "REAL"), Gpkg.Column("chybny", "INTEGER"),
                Gpkg.Column("podezrely", "INTEGER"), Gpkg.Column("fix_pct", "REAL"), Gpkg.Column("dh_m", "REAL"),
                Gpkg.Column("Y", "REAL"), Gpkg.Column("X", "REAL")),
            r.places.map { p ->
                val pl = sk.places.firstOrNull { it.id == p.placeId }
                val xy = c(p.e, p.n)
                Gpkg.point(xy[0], xy[1]).with(listOf(p.name, ObjPointType.NAMES.getOrElse(p.type) { "" },
                    pl?.let { ObjMount.NAMES.getOrElse(it.mount) { "" } }, "GNSS", cm(p.sigma), cm(p.residual), p.bad, p.suspect,
                    fixPct[p.placeId], r3(p.dh), r3(-p.e), r3(-p.n)))
            },
            "umístěné body (poloha opravená o posun krabičky)",
        )
        layers += Gpkg.Layer(
            "vrcholy", "POINT",
            listOf(T, Gpkg.Column("zdroj", "TEXT"), Gpkg.Column("presnost_cm", "REAL"), Gpkg.Column("uhel_deg", "REAL"),
                Gpkg.Column("dh_m", "REAL"), Gpkg.Column("Y", "REAL"), Gpkg.Column("X", "REAL")),
            r.vertices.map { v ->
                val xy = c(v.e, v.n)
                Gpkg.point(xy[0], xy[1]).with(listOf(v.label, ObjSource.NAMES[v.source], cm(v.sigma), v.angle?.let { Math.round(it * 100) / 100.0 },
                    r3(v.dh), r3(v.y), r3(v.x)))
            },
            "vrcholy půdorysu",
        )
        layers += Gpkg.Layer(
            "steny", "LINESTRING",
            listOf(Gpkg.Column("od", "TEXT"), Gpkg.Column("do", "TEXT"), Gpkg.Column("delka_m", "REAL"), Gpkg.Column("presnost_cm", "REAL"),
                Gpkg.Column("zdroj", "TEXT"), Gpkg.Column("pasmo_m", "REAL")),
            r.edges.map { e ->
                val a = r.vertices[e.a]
                val b = r.vertices[e.b]
                val pa = c(a.e, a.n)
                val pb = c(b.e, b.n)
                Gpkg.line(doubleArrayOf(pa[0], pb[0]), doubleArrayOf(pa[1], pb[1]))
                    .with(listOf(a.name, b.name, r3(e.len), cm(e.sigma), ObjSource.NAMES[e.source], e.tape))
            },
            "stěny",
        )
        if (sk.closed && r.vertices.size >= 3) {
            val pts = r.vertices.map { c(it.e, it.n) }
            layers += Gpkg.Layer(
                "pudorys", "POLYGON",
                listOf(T, Gpkg.Column("obvod_m", "REAL"), Gpkg.Column("plocha_m2", "REAL"), Gpkg.Column("azimut_osy", "REAL")),
                listOf(Gpkg.polygon(pts.map { it[0] }.toDoubleArray(), pts.map { it[1] }.toDoubleArray())
                    .with(listOf(name, r3(r.perimeter), r.area?.let { Math.round(it * 100) / 100.0 }, r.axisAz?.let { Math.round(it * 100) / 100.0 }))),
                "půdorys",
            )
        }
        val tapeFeatures = sk.tapes.mapNotNull { t ->
            val tr = r.tapes.firstOrNull { it.id == t.id }
            val pa = refPos(sk, r, t.a) ?: return@mapNotNull null
            val pb = refPos(sk, r, t.b) ?: return@mapNotNull null
            val a = c(pa[0], pa[1])
            val b = c(pb[0], pb[1])
            Gpkg.line(doubleArrayOf(a[0], b[0]), doubleArrayOf(a[1], b[1]))
                .with(listOf(refName(sk, r, t.a), refName(sk, r, t.b), t.len, r3(tr?.calc), cm(tr?.diff), tr?.bad == true))
        }
        layers += Gpkg.Layer(
            "koty", "LINESTRING",
            listOf(Gpkg.Column("od", "TEXT"), Gpkg.Column("do", "TEXT"), Gpkg.Column("zadana_m", "REAL"), Gpkg.Column("vypoctena_m", "REAL"),
                Gpkg.Column("rozdil_cm", "REAL"), Gpkg.Column("chybna", "INTEGER")),
            tapeFeatures, "kóty pásmem",
        )
        Gpkg.write(file, srs, layers)
    }

    private fun refPos(sk: ObjSketch, r: ObjResult, ref: String): DoubleArray? {
        val vi = ObjCalc.refVertex(sk, ref)
        if (vi >= 0) return r.vertices.getOrNull(vi)?.let { doubleArrayOf(it.e, it.n) }
        val id = ref.substring(2).toIntOrNull()
        return r.places.firstOrNull { it.placeId == id }?.let { doubleArrayOf(it.e, it.n) }
    }

    /** CSV vrcholů: souřadnice, zdroj, přesnost, úhel a stěna k dalšímu vrcholu. Vrací počet řádků. */
    fun verticesCsv(r: ObjResult, out: OutputStream): Int {
        val w = out.bufferedWriter(Charsets.UTF_8)
        fun f(v: Double?, d: Int) = if (v == null) "" else String.format(Locale.US, "%.${d}f", v)
        w.write("vrchol;Y;X;E_5514;N_5514;lat_etrs89;lon_etrs89;dh_m;zdroj;presnost_cm;uhel_deg;stena_k;delka_m;presnost_delky_cm;zdroj_delky;pasmo_m\r\n")
        for (v in r.vertices) {
            val g = Sjtsk.toEtrs(v.y, v.x, null).first
            val e = r.edges.firstOrNull { it.a == v.index }
            w.write(
                listOf(
                    v.label, f(v.y, 3), f(v.x, 3), f(v.e, 3), f(v.n, 3), f(g.lat, 9), f(g.lon, 9), f(v.dh, 3),
                    ObjSource.NAMES[v.source], f(v.sigma?.times(100), 1), f(v.angle, 2),
                    e?.let { r.vertices[it.b].name } ?: "", f(e?.len, 3), f(e?.sigma?.times(100), 1),
                    e?.let { ObjSource.NAMES[it.source] } ?: "", f(e?.tape, 3),
                ).joinToString(";") { it.replace(";", ",") }
            )
            w.write("\r\n")
        }
        w.flush()
        return r.vertices.size
    }
}
