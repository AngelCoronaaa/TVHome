@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.tvhome.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.view.inputmethod.InputMethodManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------------------------
// Datos y fuentes: antena (TvProvider del sistema) + IPTV (lista M3U)
// ---------------------------------------------------------------------------------------------

@Immutable
class TvChannel(
    val key: String,
    val name: String,
    val number: String?,
    val antenna: Boolean,
    val inputId: String?,   // solo antena
    val channelId: Long,    // solo antena
    val url: String?,       // solo IPTV
    val group: String?,
    val logo: String?,      // URL del logo (IPTV)
    val category: String? = null,   // "Deportes" para la lista de deportes
    val quality: String? = null,    // "1080p" si el nombre original lo traía
) {
    /** Identificador que no cambia al recargar la lista (la clave `key` depende del orden). */
    val stable: String get() = if (antenna) key else (url ?: key)
}

private val GROUP_ES = mapOf(
    "business" to "Negocios", "news" to "Noticias", "sports" to "Deportes", "movies" to "Películas",
    "entertainment" to "Entretenimiento", "general" to "General", "music" to "Música", "kids" to "Infantil",
    "religious" to "Religión", "documentary" to "Documentales", "culture" to "Cultura",
    "lifestyle" to "Estilo de vida", "education" to "Educación", "comedy" to "Comedia", "series" to "Series",
    "animation" to "Animación", "weather" to "Clima", "travel" to "Viajes", "science" to "Ciencia",
    "shop" to "Compras", "cooking" to "Cocina", "outdoor" to "Exteriores", "family" to "Familia",
    "classic" to "Clásicos", "legislative" to "Legislativo", "public" to "Público", "auto" to "Autos",
    "relax" to "Relax", "interactive" to "Interactivo", "undefined" to "General",
)

/** "Business;Documentary" -> "Negocios · Documentales". */
private fun prettyGroup(raw: String?): String? =
    raw?.split(';', ',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { GROUP_ES[it.lowercase()] ?: it }
        ?.distinct()
        ?.joinToString(" · ")
        ?.ifBlank { null }

private val RE_QUALITY = Regex("""\s*\((\d{3,4}p)\)""")
private val RE_TAG = Regex("""\s*\[[^\]]*\]""")

/** Texto de apoyo bajo el nombre: grupo en español y, si se conoce, la calidad. */
internal fun subtitleOf(c: TvChannel): String {
    val base = if (c.antenna) "Antena" else (c.group ?: c.category ?: "IPTV")
    return if (c.quality != null) "$base · ${c.quality}" else base
}

/** Favoritos, recientes y canales que no responden; se guardan en el equipo. */
class ChannelPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("channels", Context.MODE_PRIVATE)
    private val deadTtl = 24L * 3600_000L

    var favs by mutableStateOf(sp.getStringSet("fav", emptySet()).orEmpty().toSet())
        private set
    var recents by mutableStateOf(sp.getString("recents", "").orEmpty().split('\n').filter { it.isNotBlank() })
        private set
    var dead by mutableStateOf(readDead())
        private set

    private fun readDead(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return sp.getString("dead", "").orEmpty().split('\n').mapNotNull {
            val i = it.lastIndexOf('\t')
            if (i <= 0) null else it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: 0L)
        }.filter { now - it.second < deadTtl }.toMap()
    }

    private fun saveDead() =
        sp.edit().putString("dead", dead.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()

    fun toggleFav(id: String) {
        favs = if (id in favs) favs - id else favs + id
        sp.edit().putStringSet("fav", favs).apply()
    }

    fun played(id: String) {
        recents = (listOf(id) + recents.filter { it != id }).take(20)
        sp.edit().putString("recents", recents.joinToString("\n")).apply()
    }

    fun markDead(id: String) {
        if (id in dead) return
        dead = dead + (id to System.currentTimeMillis())
        saveDead()
    }

    fun revive(id: String) {
        if (id !in dead) return
        dead = dead - id
        saveDead()
    }

    fun clearDead() {
        dead = emptyMap()
        saveDead()
    }
}

/** Una lista M3U de IPTV. La categoría (p. ej. "Deportes") sirve para separarla con un filtro. */
class IptvSource(val id: String, val label: String, val url: String, val category: String?)

/**
 * País de la lista de iptv-org que se carga (código de 2 letras: mx, us, es, ar, co, cl, pe…).
 * Todos los códigos: https://iptv-org.github.io/iptv/countries/
 */
const val IPTV_COUNTRY = "mx"

/** Listas que se cargan por defecto. Para añadir otra sin tocar el código: adb ... --es iptv_url "URL". */
val DEFAULT_SOURCES = listOf(
    IptvSource(
        "country_$IPTV_COUNTRY", "IPTV-Org ${IPTV_COUNTRY.uppercase()}",
        "https://iptv-org.github.io/iptv/countries/$IPTV_COUNTRY.m3u", null,
    ),
    IptvSource("sports", "Deportes", "https://iptv-org.github.io/iptv/categories/sports.m3u", "Deportes"),
    IptvSource("business", "Negocios", "https://iptv-org.github.io/iptv/categories/business.m3u", "Negocios"),
)

object ChannelSources {

    const val TV_PERM = "android.permission.READ_TV_LISTINGS"

    /** Canales que la TV ya escaneó de la antena. Necesita el permiso READ_TV_LISTINGS. */
    fun antenna(ctx: Context): List<TvChannel> = runCatching {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val tuners = tim.tvInputList.filter { it.type == TvInputInfo.TYPE_TUNER }.map { it.id }.toSet()
        val proj = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_TYPE,
        )
        val out = ArrayList<TvChannel>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(TvContract.Channels.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(1)
                val name = c.getString(2).orEmpty().ifBlank { "Canal ${number ?: id}" }
                val input = c.getString(3) ?: continue
                val type = c.getString(4)
                if (type == TvContract.Channels.TYPE_PREVIEW) continue          // recomendaciones de apps
                if (tuners.isNotEmpty() && input !in tuners) continue            // solo el sintonizador
                if (!seen.add("$input/$id")) continue
                out += TvChannel("a$id", name, number, true, input, id, null, "Antena", null)
            }
        }
        out.sortedWith(compareBy({ numberKey(it.number) }, { it.name.lowercase() }))
    }.getOrDefault(emptyList())

    private fun numberKey(n: String?): Double {
        if (n.isNullOrBlank()) return Double.MAX_VALUE
        val parts = n.split('.', '-')
        val a = parts.getOrNull(0)?.toDoubleOrNull() ?: return Double.MAX_VALUE
        val b = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return a + b / 1000.0
    }

    private val ATTR = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parseM3u(text: String, src: IptvSource): List<TvChannel> {
        val out = ArrayList<TvChannel>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var n = 0
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF", ignoreCase = true)) {
                attrs = ATTR.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = l.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"].orEmpty() }
            } else if (!l.startsWith("#")) {
                if (name != null && (l.startsWith("http://") || l.startsWith("https://"))) {
                    n++
                    val quality = RE_QUALITY.find(name)?.groupValues?.get(1)
                    val clean = name.replace(RE_QUALITY, "").replace(RE_TAG, "").trim()
                    out += TvChannel(
                        key = "${src.id}_$n",
                        name = clean.ifBlank { "Canal $n" },
                        quality = quality,
                        number = attrs["tvg-chno"],
                        antenna = false,
                        inputId = null,
                        channelId = 0L,
                        url = l,
                        group = prettyGroup(attrs["group-title"]),
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
                        category = src.category,
                    )
                }
                name = null
                attrs = emptyMap()
            }
        }
        return out
    }

    fun download(url0: String): String {
        var url = url0
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) TVHome")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirección sin destino")
                url = URL(URL(url), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        }
        throw IOException("demasiadas redirecciones")
    }
}

// ---------------------------------------------------------------------------------------------
// Logos remotos (sin Coil): se descargan al verse y se guardan en memoria
// ---------------------------------------------------------------------------------------------

private val logoCache = android.util.LruCache<String, ImageBitmap>(120)

@Composable
private fun rememberLogo(url: String?): ImageBitmap? {
    val st = produceState<ImageBitmap?>(initialValue = url?.let { logoCache.get(it) }, url) {
        if (url != null && value == null) {
            val img = withContext(Dispatchers.IO) { loadLogo(url) }
            if (img != null) logoCache.put(url, img)
            value = img
        }
    }
    return st.value
}

private fun loadLogo(url: String): ImageBitmap? = runCatching {
    val conn = URL(url).openConnection().apply { connectTimeout = 8_000; readTimeout = 8_000 }
    val bytes = conn.getInputStream().use { it.readBytes() }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (o.outHeight / (sample * 2) >= 128) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

// ---------------------------------------------------------------------------------------------
// Pantalla de canales
// ---------------------------------------------------------------------------------------------

// Estilo glass: sin color propio por fuente; solo blanco y tonos translúcidos
private val NEON_ANT = Color.White
private val RED = Color(0xFFFF6B6B)
private val SUBTLE = Color(0xB3FFFFFF)

@Suppress("UNUSED_PARAMETER")
private fun neonOf(c: TvChannel): Color = Color.White

private const val ST_LOADING = 0
private const val ST_PLAYING = 1
private const val ST_ERROR = 2

private val HEADER_H = 64.dp   // alto del título (columna derecha)

@Composable
fun ChannelsScreen(
    channels: List<TvChannel>,
    status: String,
    loading: Boolean,
    initialKey: String?,
    onCurrent: (String) -> Unit,
    onRefresh: () -> Unit,
    startSearch: Boolean = false,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val store = remember { ChannelPrefs(ctx) }
    var filter by remember { mutableIntStateOf(0) }        // 0 todos, 1 favoritos, 2 recientes, 3 antena, 4 IPTV, 5 deportes, 6 negocios
    var query by remember { mutableStateOf("") }
    var curKey by remember { mutableStateOf(initialKey) }
    var full by remember { mutableStateOf(false) }
    val visible = remember(channels, filter, query, store.favs, store.recents, store.dead, curKey) {
        val base = when (filter) {
            1 -> channels.filter { it.stable in store.favs }
            2 -> store.recents.mapNotNull { id -> channels.firstOrNull { it.stable == id } }
            3 -> channels.filter { it.antenna }
            4 -> channels.filter { !it.antenna && it.category == null }
            5 -> channels.filter { it.category == "Deportes" }
            6 -> channels.filter { it.category == "Negocios" }
            else -> channels
        }.filter { it.antenna || it.stable !in store.dead || it.key == curKey }   // los que no responden se ocultan
        val q = query.trim()
        if (q.isEmpty()) base
        else base.filter {
            it.name.contains(q, ignoreCase = true) || it.number == q ||
                (it.group?.contains(q, ignoreCase = true) == true)
        }
    }
    val hiddenDead = remember(channels, store.dead) { channels.count { !it.antenna && it.stable in store.dead } }
    val focusManager = LocalFocusManager.current
    val current = remember(channels, curKey) { channels.firstOrNull { it.key == curKey } }
    // fila que recibe el foco: el canal actual si está en el filtro; si no, el primero
    val focusKey = visible.firstOrNull { it.key == curKey }?.key ?: visible.firstOrNull()?.key

    var state by remember(curKey) { mutableIntStateOf(ST_LOADING) }
    var note by remember(curKey) { mutableStateOf<String?>(null) }
    var showInfo by remember(curKey, full) { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }

    val videoFr = remember { FocusRequester() }
    val rowFr = remember { FocusRequester() }
    val searchFr = remember { FocusRequester() }
    val listState = rememberLazyListState()

    fun play(c: TvChannel) {
        store.played(c.stable)
        curKey = c.key
        onCurrent(c.key)
    }

    fun step(d: Int) {
        if (visible.isEmpty()) return
        val i = visible.indexOfFirst { it.key == curKey }
        val n = if (i < 0) 0 else (i + d + visible.size) % visible.size
        play(visible[n])
    }

    // en pantalla completa: nombres del canal siguiente (▲) y anterior (▼)
    val peek = remember(visible, curKey) {
        val i = visible.indexOfFirst { it.key == curKey }
        val n = visible.size
        if (i < 0 || n < 2) null else "▲ ${visible[(i + 1) % n].name}     ▼ ${visible[(i - 1 + n) % n].name}"
    }

    BackHandler(enabled = full) { full = false }

    // si un canal se queda cargando demasiado, se marca fuera de servicio
    LaunchedEffect(curKey, state, full, retry) {
        val c = current
        if (c != null && state == ST_LOADING) {
            delay(if (c.antenna) 12_000L else 20_000L)
            state = ST_ERROR
            note = null
        }
    }
    // la antena cambia de sesión al entrar o salir de pantalla completa: se empieza de cero
    LaunchedEffect(full) {
        if (current?.antenna == true) {
            state = ST_LOADING
            note = null
        }
    }
    LaunchedEffect(curKey, full, showInfo) {
        if (full && showInfo) {
            delay(3500)
            showInfo = false
        }
    }
    LaunchedEffect(query, filter) { listState.scrollToItem(0) }
    // un canal IPTV que no responde se oculta un día; si vuelve a reproducir, reaparece
    LaunchedEffect(state, curKey) {
        val c = current
        if (c != null && !c.antenna) {
            if (state == ST_ERROR) store.markDead(c.stable)
            else if (state == ST_PLAYING) store.revive(c.stable)
        }
    }
    // abierto con la tecla de buscar: el foco va al buscador (después de que la lista tome el suyo)
    LaunchedEffect(startSearch, visible.isNotEmpty()) {
        if (startSearch && !full) {
            delay(350)
            runCatching { searchFr.requestFocus() }
        }
    }
    LaunchedEffect(curKey) {
        if (!full) {
            val i = visible.indexOfFirst { it.key == curKey }
            if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.animateScrollToItem(i)
        }
    }
    // foco: en pantalla completa en el video; en la vista dividida, en el canal actual de la lista
    LaunchedEffect(full, visible.isNotEmpty()) {
        if (full) {
            delay(60)
            runCatching { videoFr.requestFocus() }
        } else if (visible.isNotEmpty()) {
            val i = visible.indexOfFirst { it.key == focusKey }.coerceAtLeast(0)
            listState.scrollToItem(i)
            delay(80)
            runCatching { rowFr.requestFocus() }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                // CH+ / CH- del control funcionan estés donde estés en esta pantalla
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { step(1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(-1); true }
                    else -> false
                }
            }
    ) {
        val vw = maxWidth * 0.54f
        val vh = vw * 9f / 16f
        val listW = maxWidth * 0.36f
        if (!full) Wallpaper()

        // --- video: siempre en la misma posición del árbol, así no se reinicia al pasar a pantalla completa
        VideoPane(
            modifier = if (full) Modifier.fillMaxSize()
            else Modifier.size(vw, vh),   // en (0,0): la TV dibuja el video de antena desde la esquina, sin respetar márgenes
            full = full,
            current = current,
            state = state,
            note = note,
            showInfo = showInfo,
            focus = videoFr,
            onState = { s, n -> state = s; note = n },
            retry = retry,
            peek = peek,
            onClick = {
                when {
                    state == ST_ERROR -> {
                        retry++
                        state = ST_LOADING
                        note = null
                    }
                    full -> showInfo = !showInfo
                    current != null -> full = true
                }
            },
            onKey = { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (full) { step(1); true } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (full) { step(-1); true } else false
                    else -> false
                }
            },
        )

        if (!full) {
            // --- panel de vidrio a la derecha: título, iconos y lista
            val panelShape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 20.dp, top = 16.dp, bottom = 16.dp)
                    .width(listW + 20.dp)
                    .fillMaxHeight()
                    .clip(panelShape)
                    .background(GlassFill)
                    .background(GlassSheen)
                    .border(glassBorder(panelShape).border, panelShape),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(HEADER_H)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("tv", Color.White, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Canales",
                        fontFamily = Poppins, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.weight(1f))
                    IconPill("refresh", Color.White) { store.clearDead(); onRefresh() }
                    Spacer(Modifier.width(8.dp))
                    IconPill("back", Color.White) { onClose() }
                }

                SearchField(
                    value = query,
                    onChange = { query = it },
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp).focusRequester(searchFr),
                    onDone = { focusManager.moveFocus(FocusDirection.Down) },
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (visible.isEmpty() && !loading) {
                        item {
                            Text(
                                "Sin resultados",
                                fontFamily = Poppins, fontSize = 13.sp, color = SUBTLE,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(visible, key = { _, c -> c.key }) { _, c ->
                        ChannelRow(
                            c,
                            selected = c.key == curKey,
                            fav = c.stable in store.favs,
                            modifier = if (c.key == focusKey) Modifier.focusRequester(rowFr) else Modifier,
                            onLong = { store.toggleFav(c.stable) },
                        ) { play(c) }
                    }
                }
            }

            // --- columna izquierda, debajo del video: filtros y datos del canal actual
            val infoShape = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .padding(start = 12.dp, top = vh + 14.dp)
                    .width(vw - 24.dp)
                    .clip(infoShape)
                    .background(GlassFill)
                    .background(GlassSheen)
                    .border(glassBorder(infoShape).border, infoShape)
                    .padding(12.dp),
            ) {
                val nAnt = channels.count { it.antenna }
                val nSport = channels.count { it.category == "Deportes" }
                val nBiz = channels.count { it.category == "Negocios" }
                val nIptv = channels.count { !it.antenna && it.category == null }
                val chips = buildList {
                    add(0 to "Todos ${channels.size}")
                    add(1 to "Favoritos ${store.favs.size}")
                    add(2 to "Recientes ${store.recents.size}")
                    if (nAnt > 0) add(3 to "Antena $nAnt")
                    add(4 to "IPTV $nIptv")
                    add(5 to "Deportes $nSport")
                    add(6 to "Negocios $nBiz")
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(chips, key = { it.first }) { (i, label) ->
                        FilterChip(label, filter == i) { filter = i }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!current.number.isNullOrBlank()) {
                            Text(
                                current.number,
                                fontFamily = Poppins, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = neonOf(current),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            current.name,
                            fontFamily = Poppins, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        subtitleOf(current),
                        fontFamily = Poppins, fontSize = 12.sp, color = SUBTLE, maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "OK sobre el video: pantalla completa · Mantén OK en un canal: favorito",
                        fontFamily = Poppins, fontSize = 11.sp, color = cs.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Elige un canal de la lista",
                        fontFamily = Poppins, fontSize = 14.sp, color = cs.onSurfaceVariant,
                    )
                }
                val line = if (loading) "Cargando lista de canales…" else listOf(
                    status,
                    if (hiddenDead > 0) "$hiddenDead canales ocultos por no responder (Recargar los muestra otra vez)" else "",
                ).filter { it.isNotBlank() }.joinToString("\n")
                if (line.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        line,
                        fontFamily = Poppins, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 4,
                    )
                }
            }

        }
    }
}

/** Panel de video: vista previa a la izquierda o pantalla completa. Estado, carga y "fuera de servicio" van encima. */
@Composable
private fun VideoPane(
    modifier: Modifier,
    full: Boolean,
    current: TvChannel?,
    state: Int,
    note: String?,
    showInfo: Boolean,
    focus: FocusRequester,
    retry: Int,
    onState: (Int, String?) -> Unit,
    peek: String?,
    onClick: () -> Unit,
    onKey: (Int) -> Boolean,
) {
    val neon = if (current != null) neonOf(current) else NEON_ANT
    var focused by remember { mutableStateOf(false) }
    // Box plano: el SurfaceView no soporta bien recortes redondeados ni escala (el video se pierde)
    Box(
        modifier
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val code = e.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                        onClick()
                        true
                    } else {
                        onKey(code)
                    }
                }
            }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        run {
            if (current == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph("tv", Color(0x66FFFFFF), Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Elige un canal",
                        fontFamily = Poppins, fontSize = 14.sp, color = Color(0x99FFFFFF),
                    )
                }
            } else {
                androidx.compose.runtime.key(current.key, retry, current.antenna && full) {
                    if (current.antenna) AntennaVideo(current, onState) else IptvVideo(current.url.orEmpty(), onState)
                }

                // cargando: spinner pequeño abajo a la izquierda
                if (state == ST_LOADING) {
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(if (full) 32.dp else 10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x99000000))
                            .border(glassBorder(RoundedCornerShape(50)).border, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spinner(16.dp, neon)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            note ?: "Cargando…",
                            fontFamily = Poppins, fontSize = 11.sp, color = Color.White, maxLines = 1,
                        )
                    }
                }

                // fuera de servicio
                if (state == ST_ERROR) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xE6000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Glyph("tvoff", RED, Modifier.size(if (full) 64.dp else 40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Fuera de servicio",
                                fontFamily = Poppins, fontSize = if (full) 22.sp else 16.sp,
                                fontWeight = FontWeight.Bold, color = Color.White,
                            )
                            Text(
                                "Este canal no responde. Prueba con otro.",
                                fontFamily = Poppins, fontSize = if (full) 14.sp else 11.sp,
                                color = Color(0xCCFFFFFF), textAlign = TextAlign.Center,
                            )
                            Text(
                                "Pulsa OK para reintentar",
                                fontFamily = Poppins, fontSize = if (full) 12.sp else 10.sp,
                                color = Color(0xB3FFFFFF), textAlign = TextAlign.Center,
                            )
                            if (note != null) {
                                Text(
                                    note,
                                    fontFamily = Poppins, fontSize = 9.sp,
                                    color = Color(0x80FFFFFF), textAlign = TextAlign.Center, maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // pantalla completa: nombre del canal unos segundos
                if (full && showInfo) {
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(EDGE)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x99000000))
                            .border(glassBorder(RoundedCornerShape(20.dp)).border, RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!current.number.isNullOrBlank()) {
                            Text(
                                current.number,
                                fontFamily = Poppins, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = neon,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(
                                current.name,
                                fontFamily = Poppins, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = Color.White, maxLines = 1,
                            )
                            Text(
                                subtitleOf(current),
                                fontFamily = Poppins, fontSize = 12.sp, color = SUBTLE, maxLines = 1,
                            )
                            if (peek != null) {
                                Text(
                                    peek,
                                    fontFamily = Poppins, fontSize = 11.sp, color = SUBTLE, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        // borde neón encima del video (se dibuja después, así no lo tapa)
        if (!full) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        if (focused) 2.dp else 1.dp,
                        Color.White.copy(alpha = if (focused) 0.9f else 0.25f),
                        RectangleShape,
                    )
            )
        }
    }
}

@Composable
private fun Spinner(size: Dp, color: Color) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(Modifier.size(size)) {
        val sw = this.size.width * 0.14f
        val inset = sw / 2
        val box = Size(this.size.width - sw, this.size.height - sw)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(sw))
        drawArc(color, a, 100f, false, Offset(inset, inset), box, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Buscador de vidrio: al pulsar OK sobre él se abre el teclado en pantalla. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val hasIme = remember {
        runCatching {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.isNotEmpty()
        }.getOrDefault(true)
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(if (focused) GlassFillFocus else GlassFill)
            .border(glassBorder(shape, focused).border, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("search", Color.White, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(if (hasIme) "Buscar canal" else "Sin teclado instalado", fontFamily = Poppins, fontSize = 13.sp, color = SUBTLE, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = Poppins),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val code = e.nativeKeyEvent.keyCode
                        if (e.type == KeyEventType.KeyDown &&
                            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                        ) {
                            keyboard?.show()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = Color(0xFF1B1B1F)
    Surface(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) GlassFillFocus else GlassFill,
            contentColor = if (selected) GlassInk else Color.White,
            focusedContainerColor = GlassFillFocus,
            focusedContentColor = GlassInk,
            pressedContainerColor = GlassFillFocus,
            pressedContentColor = GlassInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = glassBorder(RoundedCornerShape(50)),
            focusedBorder = glassBorder(RoundedCornerShape(50), focused = true),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = glassGlow()),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .background(GlassSheen)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontFamily = Poppins, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Fila de la lista: logo o número en un recuadro de vidrio, nombre y grupo. El canal en reproducción lleva vidrio más claro y un punto. */
@Composable
private fun ChannelRow(
    c: TvChannel,
    selected: Boolean,
    fav: Boolean,
    modifier: Modifier,
    onLong: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val logo = rememberLogo(c.logo)
    Surface(
        onClick = onClick,
        onLongClick = onLong,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) GlassFill else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = GlassFillFocus,
            focusedContentColor = GlassInk,
            pressedContainerColor = GlassFillFocus,
            pressedContentColor = GlassInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) glassBorder(shape) else Border.None,
            focusedBorder = glassBorder(shape, focused = true),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = glassGlow()),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (logo != null) Color(0xF2F2F2F7) else GlassFill),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) {
                    Image(logo, null, Modifier.size(32.dp), contentScale = ContentScale.Fit)
                } else if (!c.number.isNullOrBlank()) {
                    Text(
                        c.number,
                        fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                } else {
                    Glyph("tv", Color.White, Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c.name,
                    fontFamily = Poppins, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleOf(c),
                    fontFamily = Poppins, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = SUBTLE,
                )
            }
            if (fav) {
                Text("★", fontSize = 15.sp, color = Color(0xFFFFD27A))
                Spacer(Modifier.width(8.dp))
            }
            if (selected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reproductores: IPTV (ExoPlayer) y antena (TvView). Avisan su estado: cargando / reproduciendo / error
// ---------------------------------------------------------------------------------------------

@Composable
private fun IptvVideo(url: String, onState: (Int, String?) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            // con 1,7 GB de RAM un 4K no cabe: se limita a 1080p
            trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> onState(ST_LOADING, null)
                    Player.STATE_READY -> onState(ST_PLAYING, null)
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("TVHome", "IPTV error: " + error.errorCodeName + " " + url, error)
                onState(ST_ERROR, error.errorCodeName)
            }
        }
        player.addListener(l)
        // aquí (y no al crear el reproductor) para que el canal anterior ya haya soltado el decodificador
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    AndroidView(
        factory = { c ->
            SurfaceView(c).also { v ->
                v.isFocusable = false
                player.setVideoSurfaceView(v)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun AntennaVideo(ch: TvChannel, onState: (Int, String?) -> Unit) {
    val ctx = LocalContext.current
    val view = remember(ch.key) { TvView(ctx) }
    DisposableEffect(view) {
        onDispose { runCatching { view.reset() } }
    }
    // la sesión anterior tarda un momento en soltar el sintonizador; sintonizar de inmediato falla
    var ready by remember(ch.key) { mutableStateOf(false) }
    LaunchedEffect(ch.key) {
        delay(500)
        ready = true
    }
    if (ready) AndroidView(
        factory = {
            view.apply {
                isFocusable = false
                setCallback(object : TvView.TvInputCallback() {
                    override fun onConnectionFailed(inputId: String?) {
                        android.util.Log.e("TVHome", "TvView connection failed: $inputId")
                        onState(ST_ERROR, "No se pudo conectar con el sintonizador")
                    }

                    override fun onVideoAvailable(inputId: String?) {
                        onState(ST_PLAYING, null)
                    }

                    override fun onVideoUnavailable(inputId: String?, reason: Int) {
                        when (reason) {
                            TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> onState(ST_LOADING, "Señal débil")
                            TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> onState(ST_LOADING, "Sintonizando…")
                            TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> onState(ST_LOADING, null)
                            else -> onState(ST_LOADING, "Sin video")   // si no llega video, vence el tiempo y pasa a "fuera de servicio"
                        }
                    }
                })
                tune(ch.inputId.orEmpty(), TvContract.buildChannelUri(ch.channelId))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
