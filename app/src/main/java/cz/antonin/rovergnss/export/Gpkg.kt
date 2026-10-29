package cz.antonin.rovergnss.export

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimální zápis GeoPackage 1.3 (0.11.0): vektorové vrstvy (bod, linie, polygon) v jednom souřadnicovém systému.
 * Geometrie = hlavička GPKG + WKB (little endian), bez obálky.
 */
object Gpkg {
    /** Sloupec atributů: název a typ SQLite (TEXT, REAL, INTEGER). */
    data class Column(val name: String, val type: String)

    /** Prvek: geometrie (WKB) a hodnoty atributů v pořadí sloupců. */
    class Feature(val wkb: ByteArray, val values: List<Any?>, val xs: DoubleArray, val ys: DoubleArray)

    /** Vrstva: název tabulky, typ geometrie (POINT, LINESTRING, POLYGON), sloupce, prvky. */
    class Layer(val name: String, val geomType: String, val columns: List<Column>, val features: List<Feature>, val description: String = "")

    /** Souřadnicový systém (EPSG). */
    class Srs(val id: Int, val name: String, val wkt: String)

    val SRS_5514 = Srs(
        5514, "S-JTSK / Krovak East North",
        "PROJCS[\"S-JTSK / Krovak East North\",GEOGCS[\"S-JTSK\",DATUM[\"System_Jednotne_Trigonometricke_Site_Katastralni\"," +
            "SPHEROID[\"Bessel 1841\",6377397.155,299.1528128,AUTHORITY[\"EPSG\",\"7004\"]],TOWGS84[589,76,480,0,0,0,0]," +
            "AUTHORITY[\"EPSG\",\"6156\"]],PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]]," +
            "UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4156\"]]," +
            "PROJECTION[\"Krovak\"],PARAMETER[\"latitude_of_center\",49.5],PARAMETER[\"longitude_of_center\",24.83333333333333]," +
            "PARAMETER[\"azimuth\",30.28813972222222],PARAMETER[\"pseudo_standard_parallel_1\",78.5],PARAMETER[\"scale_factor\",0.9999]," +
            "PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]]," +
            "AXIS[\"X\",EAST],AXIS[\"Y\",NORTH],AUTHORITY[\"EPSG\",\"5514\"]]",
    )
    val SRS_25833 = Srs(
        25833, "ETRS89 / UTM zone 33N",
        "PROJCS[\"ETRS89 / UTM zone 33N\",GEOGCS[\"ETRS89\",DATUM[\"European_Terrestrial_Reference_System_1989\"," +
            "SPHEROID[\"GRS 1980\",6378137,298.257222101,AUTHORITY[\"EPSG\",\"7019\"]],AUTHORITY[\"EPSG\",\"6258\"]]," +
            "PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]],UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]]," +
            "AUTHORITY[\"EPSG\",\"4258\"]],PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"latitude_of_origin\",0]," +
            "PARAMETER[\"central_meridian\",15],PARAMETER[\"scale_factor\",0.9996],PARAMETER[\"false_easting\",500000]," +
            "PARAMETER[\"false_northing\",0],UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],AXIS[\"Easting\",EAST]," +
            "AXIS[\"Northing\",NORTH],AUTHORITY[\"EPSG\",\"25833\"]]",
    )
    private const val WKT_4326 = "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563," +
        "AUTHORITY[\"EPSG\",\"7030\"]],AUTHORITY[\"EPSG\",\"6326\"]],PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]]," +
        "UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4326\"]]"

    // ---------------------------------------------------------------- WKB
    private const val B0: Byte = 0
    private const val B1: Byte = 1   // little endian (WKB) / bez obálky, little endian (hlavička GPKG)
    private fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun point(x: Double, y: Double): Feature0 =
        Feature0(buf(21).put(B1).putInt(1).putDouble(x).putDouble(y).array(), doubleArrayOf(x), doubleArrayOf(y))

    fun line(xs: DoubleArray, ys: DoubleArray): Feature0 {
        val b = buf(9 + 16 * xs.size).put(B1).putInt(2).putInt(xs.size)
        for (i in xs.indices) b.putDouble(xs[i]).putDouble(ys[i])
        return Feature0(b.array(), xs, ys)
    }

    /** Polygon s jedním obvodem; uzavře se sám (poslední = první). */
    fun polygon(xs: DoubleArray, ys: DoubleArray): Feature0 {
        val n = xs.size + 1
        val b = buf(13 + 16 * n).put(B1).putInt(3).putInt(1).putInt(n)
        for (i in 0 until n) b.putDouble(xs[i % xs.size]).putDouble(ys[i % ys.size])
        return Feature0(b.array(), xs, ys)
    }

    /** Geometrie bez atributů (pomocná). */
    class Feature0(val wkb: ByteArray, val xs: DoubleArray, val ys: DoubleArray) {
        fun with(values: List<Any?>) = Feature(wkb, values, xs, ys)
    }

    private fun gpkgGeom(srs: Int, wkb: ByteArray): ByteArray =
        buf(8 + wkb.size).put('G'.code.toByte()).put('P'.code.toByte()).put(B0).put(B1).putInt(srs).put(wkb).array()

    // ---------------------------------------------------------------- zápis
    /** Zapíše GeoPackage do [file] (přepíše). */
    fun write(file: File, srs: Srs, layers: List<Layer>) {
        file.delete()
        File(file.path + "-journal").delete()
        // bez lokalizace: Android jinak přidá tabulku android_metadata
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            db.execSQL("PRAGMA application_id = 1196444487")   // 'GPKG'
            db.version = 10300
            db.execSQL(
                "CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER NOT NULL PRIMARY KEY, " +
                    "organization TEXT NOT NULL, organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)"
            )
            db.execSQL(
                "CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, " +
                    "description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), " +
                    "min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER, " +
                    "CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))"
            )
            db.execSQL(
                "CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, " +
                    "geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, " +
                    "CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name), " +
                    "CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name), " +
                    "CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id))"
            )
            fun srsRow(name: String, id: Int, org: String, orgId: Int, def: String) = ContentValues().apply {
                put("srs_name", name); put("srs_id", id); put("organization", org); put("organization_coordsys_id", orgId)
                put("definition", def)
            }
            db.insert("gpkg_spatial_ref_sys", null, srsRow("Undefined cartesian SRS", -1, "NONE", -1, "undefined"))
            db.insert("gpkg_spatial_ref_sys", null, srsRow("Undefined geographic SRS", 0, "NONE", 0, "undefined"))
            db.insert("gpkg_spatial_ref_sys", null, srsRow("WGS 84 geodetic", 4326, "EPSG", 4326, WKT_4326))
            db.insert("gpkg_spatial_ref_sys", null, srsRow(srs.name, srs.id, "EPSG", srs.id, srs.wkt))

            for (l in layers) {
                val cols = l.columns.joinToString("") { ", \"${it.name}\" ${it.type}" }
                db.execSQL("CREATE TABLE \"${l.name}\" (fid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, geom ${l.geomType}$cols)")
                val xs = l.features.flatMap { it.xs.asList() }
                val ys = l.features.flatMap { it.ys.asList() }
                db.insert("gpkg_contents", null, ContentValues().apply {
                    put("table_name", l.name); put("data_type", "features"); put("identifier", l.name); put("description", l.description)
                    if (xs.isNotEmpty()) {
                        put("min_x", xs.min()); put("min_y", ys.min()); put("max_x", xs.max()); put("max_y", ys.max())
                    }
                    put("srs_id", srs.id)
                })
                db.insert("gpkg_geometry_columns", null, ContentValues().apply {
                    put("table_name", l.name); put("column_name", "geom"); put("geometry_type_name", l.geomType)
                    put("srs_id", srs.id); put("z", 0); put("m", 0)
                })
                for (f in l.features) {
                    val cv = ContentValues()
                    cv.put("geom", gpkgGeom(srs.id, f.wkb))
                    l.columns.forEachIndexed { i, c ->
                        when (val v = f.values.getOrNull(i)) {
                            null -> cv.putNull(c.name)
                            is Double -> if (v.isNaN()) cv.putNull(c.name) else cv.put(c.name, v)
                            is Int -> cv.put(c.name, v)
                            is Long -> cv.put(c.name, v)
                            is Boolean -> cv.put(c.name, if (v) 1 else 0)
                            else -> cv.put(c.name, v.toString())
                        }
                    }
                    db.insert("\"${l.name}\"", null, cv)
                }
            }
        } finally {
            db.close()
        }
    }
}
