package com.kerbos.app.icons

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.text.Normalizer
import kotlin.concurrent.thread

/** A brand icon from Simple Icons (CC0): single SVG path in a 24×24 viewport. */
class BrandIcon(val slug: String, val title: String, val color: Long, val path: String, val keys: List<String>)

object IconLibrary {
    @Volatile
    var icons: List<BrandIcon> = emptyList()
        private set
    private var bySlug: Map<String, BrandIcon> = emptyMap()
    val loaded = MutableStateFlow(false)
    private val lock = Object()
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        thread(name = "icon-loader") { ensureLoaded() }
    }

    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded.value) return
            val text = appCtx.assets.open("icons.json").bufferedReader().use { it.readText() }
            val a = JSONArray(text)
            val list = ArrayList<BrandIcon>(a.length())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val title = o.getString("t")
                val slug = o.getString("s")
                val keys = LinkedHashSet<String>()
                keys += norm(title); keys += norm(slug)
                o.optJSONArray("a")?.let { al -> for (j in 0 until al.length()) keys += norm(al.getString(j)) }
                keys.remove("")
                list += BrandIcon(slug, title, 0xFF000000 or o.getString("h").toLong(16), o.getString("p"), keys.toList())
            }
            icons = list
            bySlug = list.associateBy { it.slug }
            loaded.value = true
        }
    }

    fun get(slug: String): BrandIcon? = bySlug[slug]

    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.filter { it in 'a'..'z' || it in '0'..'9' }
    }

    private fun words(s: String): List<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    /** Ranked icon suggestions for an account (best first) with a confidence score. */
    fun suggest(issuer: String, name: String, limit: Int = 12): List<Pair<BrandIcon, Int>> {
        ensureLoaded()
        val iss = norm(issuer)
        val issWords = words(issuer).toSet()
        val domain = name.substringAfter('@', "").substringBeforeLast('.').let { norm(it.substringAfterLast('.')) }
        val nameNorm = norm(name)
        if (iss.isEmpty() && domain.isEmpty() && nameNorm.isEmpty()) return emptyList()
        val res = ArrayList<Pair<BrandIcon, Int>>()
        for (ic in icons) {
            var best = 0
            for (k in ic.keys) {
                val s = when {
                    iss.isNotEmpty() && k == iss -> 100
                    k.length >= 3 && k in issWords -> 85
                    domain.isNotEmpty() && k == domain -> 80
                    iss.length >= 4 && k.length >= 4 && iss.startsWith(k) -> 70
                    iss.length >= 4 && k.startsWith(iss) -> 60
                    k.length >= 5 && iss.contains(k) -> 50
                    iss.isEmpty() && k.length >= 4 && nameNorm == k -> 55
                    else -> 0
                }
                if (s > best) best = s
            }
            if (best > 0) res += ic to best
        }
        return res.sortedWith(compareByDescending<Pair<BrandIcon, Int>> { it.second }.thenBy { it.first.title.length })
            .take(limit)
    }

    fun bestMatch(issuer: String, name: String): BrandIcon? =
        suggest(issuer, name, 1).firstOrNull()?.takeIf { it.second >= 60 }?.first

    fun search(q: String): List<BrandIcon> {
        ensureLoaded()
        val n = norm(q)
        if (n.isEmpty()) return icons
        return icons.filter { ic -> ic.keys.any { it.contains(n) } }
            .sortedWith(compareBy<BrandIcon> { ic -> if (ic.keys.any { it.startsWith(n) }) 0 else 1 }.thenBy { it.title.length })
    }
}
