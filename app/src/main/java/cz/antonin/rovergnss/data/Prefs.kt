package cz.antonin.rovergnss.data

import android.content.Context
import cz.antonin.rovergnss.ntrip.NtripProfile
import org.json.JSONArray
import org.json.JSONObject

/** Nastavení aplikace (SharedPreferences). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("rovergnss", Context.MODE_PRIVATE)

    var roverAddress: String?
        get() = sp.getString("roverAddress", null)
        set(v) = sp.edit().putString("roverAddress", v).apply()

    /** Aktivní zakázka (0 = ještě nezvolená) */
    var activeJobId: Long
        get() = sp.getLong("activeJobId", 0)
        set(v) = sp.edit().putLong("activeJobId", v).apply()

    /** Poslední pohled mapy [lat, lon, zoom] (null = ještě nebyl) */
    var mapCamera: DoubleArray?
        get() = sp.getString("mapCamera", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 3 }?.toDoubleArray()
        set(v) = sp.edit().putString("mapCamera", v?.joinToString(",")).apply()

    /** Mapa sleduje polohu roveru */
    var mapFollow: Boolean
        get() = sp.getBoolean("mapFollow", true)
        set(v) = sp.edit().putBoolean("mapFollow", v).apply()

    /** Vrstva bodových polí ČÚZK na mapě */
    var showCuzk: Boolean
        get() = sp.getBoolean("showCuzk", false)
        set(v) = sp.edit().putBoolean("showCuzk", v).apply()

    var ntripIndex: Int
        get() = sp.getInt("ntripIndex", 0)
        set(v) = sp.edit().putInt("ntripIndex", v).apply()

    /** 0 OpenFreeMap (vektor), 1 ČÚZK ortofoto, 2 ČÚZK ZTM */
    var mapStyle: Int
        get() = sp.getInt("mapStyle", 0)
        set(v) = sp.edit().putInt("mapStyle", v).apply()

    /** Záznam trasy: každá n-tá epocha roveru (1 = všechny) */
    var recordEvery: Int
        get() = sp.getInt("recordEvery", 1)
        set(v) = sp.edit().putInt("recordEvery", v).apply()

    /** Při záznamu trasy ukládat i surový proud z roveru */
    var recordRaw: Boolean
        get() = sp.getBoolean("recordRaw", true)
        set(v) = sp.edit().putBoolean("recordRaw", v).apply()

    var antennaH: Double
        get() = sp.getFloat("antennaH", 0f).toDouble()
        set(v) = sp.edit().putFloat("antennaH", v.toFloat()).apply()

    var ntripProfiles: List<NtripProfile>
        get() {
            val s = sp.getString("ntripProfiles", null) ?: return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    NtripProfile(
                        name = o.optString("name"),
                        host = o.optString("host"),
                        port = o.optInt("port", 2101),
                        mount = o.optString("mount"),
                        user = o.optString("user"),
                        pass = o.optString("pass"),
                        ggaSec = o.optInt("ggaSec", 10),
                        v2 = o.optBoolean("v2", false),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(list) {
            val a = JSONArray()
            list.forEach { p ->
                a.put(
                    JSONObject()
                        .put("name", p.name).put("host", p.host).put("port", p.port).put("mount", p.mount)
                        .put("user", p.user).put("pass", p.pass).put("ggaSec", p.ggaSec).put("v2", p.v2)
                )
            }
            sp.edit().putString("ntripProfiles", a.toString()).apply()
        }
}
