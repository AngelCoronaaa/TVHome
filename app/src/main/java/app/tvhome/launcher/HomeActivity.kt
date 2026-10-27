@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.tvhome.launcher

import android.accounts.AccountManager
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Datos
// ---------------------------------------------------------------------------------------------

@Immutable
class AppItem(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val image: ImageBitmap?,
    val banner: ImageBitmap?,   // banner 16:9 de la app (Android TV); null = se arma uno con el ícono
    val fill: Boolean,   // true: el ícono llena todo el círculo (íconos adaptativos)
    val tint: Color,
    val neon: Color,
    val group: Int,      // 0 = Google, 1 = instaladas por ti, 2 = sistema
)

@Immutable
class InputItem(
    val id: String,
    val label: String,
    val original: String,
    val connected: Boolean,
    val name: String?,
    val logo: ImageBitmap?,
    val neon: Color,
)

@Immutable
class ProcItem(val pkg: String, val label: String, val icon: ImageBitmap?, val lastUsed: Long)

@Immutable
class HomeData(
    val services: List<AppItem> = emptyList(),
    val inputs: List<InputItem> = emptyList(),
    val rec: List<AppItem> = emptyList(),
    val apps: List<AppItem> = emptyList(),
    val loaded: Boolean = false,
)

/** Servicios que salen primero, en este orden, si están instalados. */
private val PINNED = listOf(
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.google.android.youtube.tv",
    "com.disney.disneyplus",
    "com.wbd.stream",
    "com.hbo.hbonow",
    "com.apple.atve.androidtv.appletv",
    "com.google.android.youtube.tvmusic",
    "com.spotify.tv.android",
    "tv.pluto.android",
    "com.tubitv",
    "com.univision.prendetv",
    "com.plexapp.android",
    "org.videolan.vlc",
)

/** Nunca se ofrecen para cerrar. */
private val PROTECTED = setOf(
    "android",
    "com.android.systemui",
    "com.google.android.tvlauncher",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.tcl.tv",
    "com.tcl.system.server",
)

private const val KEY = "n_"
private const val INPUT_KEY = "in_"
private const val TV_ID = "__tv"
private const val WALLPAPERS = 12
private const val LOW_RAM_MB = 300   // por debajo de esto se avisa al abrir una app

internal val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/** Nombres que rota una pulsación larga sobre una entrada. "" = el nombre original de la TV. */
private val INPUT_NAMES = listOf("Decodificador", "Consola", "PC", "Streaming", "Blu-ray", "Barra de sonido", "")

private val INPUT_NEONS = listOf(
    Color(0xFF2BE38B), Color(0xFFFF9A1F), Color(0xFFD0BCFF),
    Color(0xFFFF6FB5), Color(0xFF7CC4FF), Color(0xFFFFD84D),
)

/** Cada entrada recibe siempre el mismo color de neón, según su nombre. */
private fun neonFor(key: String?): Color =
    if (key.isNullOrEmpty()) INPUT_NEONS[2] else INPUT_NEONS[(key.hashCode() and 0x7fffffff) % INPUT_NEONS.size]

/**
 * Zona horaria del reloj, el saludo y el reposo. null = la del sistema (lo normal).
 * Si tu TV la tiene mal configurada y no puedes corregirla, fíjala aquí,
 * por ejemplo: TimeZone.getTimeZone("America/Mexico_City").
 */
private val CLOCK_TZ: TimeZone? = null

private fun clockCal(millis: Long = System.currentTimeMillis()): Calendar =
    Calendar.getInstance(CLOCK_TZ ?: TimeZone.getDefault()).apply { timeInMillis = millis }

/** La fecha siempre en español, aunque el sistema esté en otro idioma. */
private val DATE_LOCALE = Locale("es", "MX")

private fun clockFmt(pattern: String, locale: Locale = Locale.getDefault()): SimpleDateFormat =
    SimpleDateFormat(pattern, locale).apply { CLOCK_TZ?.let { timeZone = it } }

private fun daypart(): Int = clockCal().get(Calendar.HOUR_OF_DAY) / 6

// ---------------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------------

class HomeActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var cache: List<AppItem> = emptyList()
    private var data by mutableStateOf(HomeData())
    private var account by mutableStateOf<String?>(null)
    private var tick by mutableIntStateOf(0)
    private var dirty = true
    private var started = false

    // modo reposo
    private var resting by mutableStateOf(false)

    // canales: antena + IPTV
    private var showChannels by mutableStateOf(false)
    private var chSearch by mutableStateOf(false)   // abrir canales con el foco en el buscador
    private var channels by mutableStateOf<List<TvChannel>>(emptyList())
    private var chStatus by mutableStateOf("")
    private var chLoading by mutableStateOf(false)
    private var iptvDirty = false

    // procesos en segundo plano
    private var showProcs by mutableStateOf(false)
    private var procs by mutableStateOf<List<ProcItem>>(emptyList())
    private var hasUsage by mutableStateOf(false)
    private var freeMb by mutableIntStateOf(0)
    private val killedAt = HashMap<String, Long>()

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dirty = true
            if (started) load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", MODE_PRIVATE)
        saveAccount(intent)
        saveIptv(intent)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)

        setContent {
            BackHandler {
                when {
                    resting -> exitRest()
                    showProcs -> showProcs = false
                    showChannels -> showChannels = false
                    else -> tick++
                }
            }
            HomeTheme {
                when {
                    resting -> RestScreen()
                    showProcs -> ProcPanel(
                        procs = procs,
                        hasAccess = hasUsage,
                        freeMb = freeMb,
                        onKill = ::killOne,
                        onKillAll = ::killAll,
                        onGrant = ::openUsageAccess,
                        onClose = { showProcs = false },
                    )
                    showChannels -> ChannelsScreen(
                        channels = channels,
                        status = chStatus,
                        loading = chLoading,
                        initialKey = prefs.getString("last_channel", null),
                        onCurrent = { prefs.edit().putString("last_channel", it).apply() },
                        onRefresh = { loadChannels(true) },
                        startSearch = chSearch,
                        onClose = { showChannels = false; chSearch = false },
                    )
                    else -> HomeScreen(
                        d = data,
                        account = account,
                        tick = tick,
                        onApp = ::launch,
                        onInfo = ::appInfo,
                        onInput = ::openInput,
                        onInputLong = ::renameInput,
                        onSettings = ::openSettings,
                        onProcs = ::openProcs,
                        onLiveTv = ::openLiveTv,
                        onRest = ::enterRest,
                        onNativeHome = ::openNativeLauncher,
                        onChannels = ::openChannels,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        account = readAccount()
        if (showProcs) refreshProcs()   // al volver de dar el permiso
        if (dirty) load() else publish()
    }

    override fun onStop() {
        started = false
        if (resting) exitRest()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pkgReceiver) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveAccount(intent)
        saveIptv(intent)
        account = readAccount()
        if (resting) exitRest()
        showProcs = false
        showChannels = false
        tick++ // botón Home: regresar arriba
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (resting) {            // cualquier tecla despierta
            exitRest()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_TV -> { openLiveTv(); return true }                     // botón TV del control
            KeyEvent.KEYCODE_GUIDE -> { chSearch = false; openChannels(); return true }  // guía: lista de canales
            KeyEvent.KEYCODE_SEARCH -> { chSearch = true; openChannels(); return true }  // buscar: canales con el buscador
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openSettings(Settings.ACTION_SETTINGS); return true }  // botón de ajustes del control
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) loadChannels()
    }

    // --- reposo -------------------------------------------------------------------------------

    private fun enterRest() {
        resting = true
        setBrightness(REST_BRIGHTNESS)
    }

    private fun exitRest() {
        resting = false
        setBrightness(-1f)   // -1 = brillo normal del sistema
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    // --- carga --------------------------------------------------------------------------------

    private fun load() {
        Thread {
            val list = queryApps()
            runOnUiThread {
                cache = list
                dirty = false
                publish()
            }
        }.start()
    }

    private fun publish() {
        val byPkg = cache.associateBy { it.pkg }
        val services = PINNED.mapNotNull { byPkg[it] }
        val pinned = services.map { it.pkg }.toSet()
        val rest = cache.filter { it.pkg !in pinned }

        // Recomendadas: lo que sueles abrir a esta hora del día pesa el triple que el total.
        val part = daypart()
        val rec = rest
            .map { it to (prefs.getInt("d${part}_" + it.pkg, 0) * 3 + prefs.getInt(KEY + it.pkg, 0)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first }

        data = HomeData(
            services = services,
            inputs = queryInputs(),
            rec = rec,
            apps = rest,
            loaded = true,
        )
    }

    private fun queryApps(): List<AppItem> {
        val pm = packageManager
        val map = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val p = ri.activityInfo.packageName
                if (p == packageName || map.containsKey(p)) continue
                map[p] = ri
            }
        }
        val out = ArrayList<AppItem>(map.size)
        for (ri in map.values) {
            val p = ri.activityInfo.packageName
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(p, ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            val (bmp, adaptive) = renderIcon(ri.loadIcon(pm))
            val banner = runCatching {
                (ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm))
                    ?.render(256, 144)?.asImageBitmap()
            }.getOrNull()
            val avg = bmp.averageColor()
            val corner = bmp.getPixel(6, 6)
            // Si la esquina del ícono es opaca, el círculo se pinta de ese color y el ícono se funde con él.
            val tint = if (android.graphics.Color.alpha(corner) > 200) Color(corner)
            else Color(0xFF1D1B22).blend(Color(avg), 0.28f)

            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(avg, hsv)
            if (hsv[1] >= 0.15f) hsv[1] = maxOf(hsv[1], 0.65f)   // colores vivos; los grises quedan blancos
            hsv[2] = 1f
            val neon = Color(android.graphics.Color.HSVToColor(hsv))

            val flags = ri.activityInfo.applicationInfo.flags
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isGoogle = p.startsWith("com.google.") || p == "com.android.vending" || p == "com.android.chrome"
            val group = when {
                isGoogle -> 0
                !isSystem -> 1
                else -> 2
            }
            out += AppItem(
                pkg = p,
                label = ri.loadLabel(pm).toString(),
                intent = intent,
                image = bmp.asImageBitmap(),
                banner = banner,
                fill = adaptive,
                tint = tint,
                neon = neon,
                group = group,
            )
        }
        return out.sortedWith(compareBy({ it.group }, { it.label.lowercase() }))
    }

    private fun queryInputs(): List<InputItem> {
        val tv = InputItem(
            id = TV_ID, label = "TV en vivo", original = "TV",
            connected = true, name = null, logo = null, neon = Color(0xFF7CC4FF),
        )
        val hdmi = runCatching {
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            tim.tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI }
                .map { info ->
                    val custom = info.loadCustomLabel(this)?.toString()
                    val base = info.loadLabel(this)?.toString().orEmpty()
                    val connected = runCatching {
                        tim.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED
                    }.getOrDefault(false)
                    val original = if (!custom.isNullOrBlank()) custom else base.ifBlank { "Entrada" }
                    // Nombre propio: se cambia con pulsación larga sobre la entrada. Sin él, el que le da la TV.
                    val stored = prefs.getString(INPUT_KEY + info.id, null)
                    val name = stored?.takeIf { it.isNotEmpty() }
                    InputItem(
                        id = info.id,
                        label = name ?: original,
                        original = original,
                        connected = connected,
                        name = name,
                        logo = name?.let { logoFor(it) },
                        neon = neonFor(name ?: original),
                    )
                }
        }.getOrDefault(emptyList())
        return listOf(tv) + hdmi
    }

    private fun readAccount(): String? {
        val fromSystem = try {
            AccountManager.get(this).getAccountsByType("com.google").firstOrNull()?.name
        } catch (e: Exception) {
            null
        }
        // Si Android no deja leerla, se usa la que se pasó por adb (--es account correo)
        return fromSystem ?: prefs.getString("account", null)
    }

    private fun saveAccount(i: Intent?) {
        i?.getStringExtra("account")?.takeIf { it.isNotBlank() }
            ?.let { prefs.edit().putString("account", it).apply() }
    }

    // --- acciones -----------------------------------------------------------------------------

    private var lastRamWarn = 0L

    private fun launch(a: AppItem) {
        val free = freeRamMb()
        val now = System.currentTimeMillis()
        if (free < LOW_RAM_MB && now - lastRamWarn > 10 * 60_000L) {
            lastRamWarn = now
            toast("Poca memoria libre ($free MB). Cierra apps en Sistema > Procesos")
        }
        try {
            prefs.edit()
                .putInt(KEY + a.pkg, prefs.getInt(KEY + a.pkg, 0) + 1)
                .putInt("d${daypart()}_" + a.pkg, prefs.getInt("d${daypart()}_" + a.pkg, 0) + 1)
                .apply()
            startActivity(a.intent)
        } catch (e: Exception) {
            toast("No se pudo abrir ${a.label}")
        }
    }

    private fun appInfo(a: AppItem) {
        openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null))
    }

    private fun openInput(i: InputItem) {
        if (i.id == TV_ID) {
            openLiveTv()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(i.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("Esta TV no permite abrir ${i.label} desde aquí")
        }
    }

    /** Abre el launcher original de la TV (Google TV / Android TV) sin dejar de ser el Home por defecto. */
    private fun saveIptv(i: Intent?) {
        i?.getStringExtra("iptv_url")?.takeIf { it.isNotBlank() }?.let {
            prefs.edit().putString("iptv_url", it).apply()
            iptvDirty = true
        }
    }

    private fun openChannels() {
        showChannels = true
        if (checkSelfPermission(ChannelSources.TV_PERM) != PackageManager.PERMISSION_GRANTED) {
            // si el sistema no muestra el diálogo, la respuesta llega negada y se cargan solo los IPTV
            requestPermissions(arrayOf(ChannelSources.TV_PERM), 7)
        } else {
            loadChannels()
        }
    }

    /** Antena (canales ya escaneados por la TV) + listas M3U de IPTV (por país y deportes), en una sola lista. */
    private fun loadChannels(force: Boolean = false) {
        if (chLoading) return
        chLoading = true
        val hasPerm = checkSelfPermission(ChannelSources.TV_PERM) == PackageManager.PERMISSION_GRANTED
        val extra = prefs.getString("iptv_url", null)?.takeIf { it.isNotBlank() }
        val sources = DEFAULT_SOURCES + listOfNotNull(extra?.let { IptvSource("custom", "Mi lista", it, null) })
        val refresh = force || iptvDirty
        Thread {
            val ant = if (hasPerm) ChannelSources.antenna(this) else emptyList()
            val errs = ArrayList<String>()
            val iptv = ArrayList<TvChannel>()
            val seen = HashSet<String>()
            val names = HashSet<String>()
            for (src in sources) {
                val file = File(filesDir, "iptv_${src.id}.m3u")
                if (refresh || !file.exists()) {
                    try {
                        file.writeText(ChannelSources.download(src.url))
                    } catch (e: Exception) {
                        errs += "${src.label}: ${e.message?.take(40)}"
                    }
                }
                if (file.exists()) {
                    runCatching { ChannelSources.parseM3u(file.readText(), src) }
                        .getOrDefault(emptyList())
                        .forEach {
                            // sin repetidos: ni la misma URL ni el mismo nombre en otra lista
                            if ((it.url == null || seen.add(it.url)) && names.add(it.name.lowercase())) iptv += it
                        }
                }
            }
            iptvDirty = false
            val status = buildList {
                if (!hasPerm) add("Sin permiso para leer los canales de antena. En tu computadora: adb shell pm grant app.tvhome.launcher android.permission.READ_TV_LISTINGS")
                else if (ant.isEmpty()) add("No encontré canales de antena: escanea canales en Ajustes de la TV.")
                if (errs.isNotEmpty()) add("No pude bajar: " + errs.joinToString(" · "))
            }.joinToString("\n")
            runOnUiThread {
                channels = ant + iptv
                chStatus = status
                chLoading = false
            }
        }.start()
    }

    private fun openNativeLauncher() {
        val homes = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName }
        val target = homes.firstOrNull { it.packageName == "com.google.android.apps.tv.launcherx" }
            ?: homes.firstOrNull { it.packageName == "com.google.android.tvlauncher" }
            ?: homes.firstOrNull()
        if (target == null) {
            toast("No encontré el launcher original")
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(target.packageName, target.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("No pude abrir el launcher original")
        }
    }

    private fun openLiveTv() {
        // Algunas apps de TV del fabricante (p. ej. TCL) abren la última entrada usada, que puede ser HDMI;
        // por eso se pide primero el sintonizador.
        val tuner = runCatching {
            (getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager).tvInputList
                .firstOrNull { it.type == TvInputInfo.TYPE_TUNER }?.id
        }.getOrNull()
        val tries = listOfNotNull(
            tuner?.let {
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(it))
                    .setPackage("com.tcl.tv")
            },
            tuner?.let { Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(it)) },
            packageManager.getLaunchIntentForPackage("com.tcl.tv"),
            Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI),
        )
        for (t in tries) {
            if (t == null) continue
            try {
                startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // siguiente intento
            }
        }
        toast("No pude abrir la TV en vivo")
    }

    /** Pulsación larga: pasa al siguiente nombre de la lista y lo guarda. */
    private fun renameInput(i: InputItem) {
        if (i.id == TV_ID) return
        val cur = i.name ?: ""
        val next = INPUT_NAMES[(INPUT_NAMES.indexOf(cur) + 1) % INPUT_NAMES.size]
        prefs.edit().putString(INPUT_KEY + i.id, next).apply()
        publish()
    }

    /** Busca res/drawable/logo_<nombre>.png (ej. logo_consola para una entrada llamada "Consola"). Si no existe, sin logo. */
    private fun logoFor(name: String): ImageBitmap? {
        val res = "logo_" + name.lowercase().filter { it.isLetterOrDigit() }
        val id = resources.getIdentifier(res, "drawable", packageName)
        if (id == 0) return null
        val d = runCatching { getDrawable(id) }.getOrNull() ?: return null
        val h = 96
        val w = if (d.intrinsicHeight > 0) (h.toFloat() * d.intrinsicWidth / d.intrinsicHeight).toInt().coerceIn(16, 256) else h
        return d.render(w, h).asImageBitmap()
    }

    /** Cada pantalla de Ajustes prueba varias rutas; si ninguna existe en tu TV abre Ajustes. */
    private fun openSettings(action: String, uri: Uri? = null) {
        val tries = ArrayList<Intent>()
        tries += Intent(action, uri)
        when (action) {
            Settings.ACTION_DISPLAY_SETTINGS -> {
                tries += Intent().setClassName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.device.display.displaysound.DisplaySoundActivity",
                )
            }
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS -> {
                tries += Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)
                tries += Intent().setClassName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.device.storage.StorageActivity",
                )
            }
        }
        val fallbackFrom = tries.size
        tries += Intent(Settings.ACTION_SETTINGS)
        for ((idx, t) in tries.withIndex()) {
            try {
                startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (idx >= fallbackFrom && action != Settings.ACTION_SETTINGS) {
                    toast("Esta TV no tiene acceso directo; abrí Ajustes")
                }
                return
            } catch (e: Exception) {
                // siguiente ruta
            }
        }
        toast("No disponible en esta TV")
    }

    // --- segundo plano ------------------------------------------------------------------------

    private fun am() = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun freeRamMb(): Int {
        val mi = ActivityManager.MemoryInfo()
        am().getMemoryInfo(mi)
        return (mi.availMem / 1048576L).toInt()
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Esta TV no tiene esa pantalla: usa el comando adb")
        }
    }

    private fun openProcs() {
        showProcs = true
        refreshProcs()
    }

    /**
     * Android no deja a una app ver los procesos de otras. Lo más cercano: las apps que se usaron
     * en las últimas 6 horas (necesita permiso de "acceso de uso", ver README).
     */
    private fun refreshProcs() {
        hasUsage = hasUsageAccess()
        freeMb = freeRamMb()
        if (!hasUsage) {
            procs = emptyList()
            return
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val since = now - 6 * 3600_000L
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now)
        }.getOrNull().orEmpty()
        val last = stats.groupBy { it.packageName }.mapValues { e -> e.value.maxOf { it.lastTimeUsed } }
        val byPkg = cache.associateBy { it.pkg }
        procs = last.entries.asSequence()
            .filter { it.value > since && it.key != packageName && it.key !in PROTECTED }
            .filter { it.value > (killedAt[it.key] ?: 0L) }   // ya cerradas y sin reabrir, no
            .mapNotNull { e -> byPkg[e.key]?.let { ProcItem(e.key, it.label, it.image, e.value) } }
            .sortedByDescending { it.lastUsed }
            .take(20)
            .toList()
    }

    private fun killOne(p: ProcItem) {
        am().killBackgroundProcesses(p.pkg)
        killedAt[p.pkg] = System.currentTimeMillis()
        procs = procs.filter { it.pkg != p.pkg }
        afterKill()
    }

    private fun killAll() {
        val list = procs
        val now = System.currentTimeMillis()
        list.forEach {
            am().killBackgroundProcesses(it.pkg)
            killedAt[it.pkg] = now
        }
        procs = emptyList()
        afterKill()
        toast("Cerradas ${list.size} apps")
    }

    private fun afterKill() {
        freeMb = freeRamMb()
        window.decorView.postDelayed({ freeMb = freeRamMb() }, 900)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------------------------------------
// Utilidades de imagen (sin Coil ni ktx)
// ---------------------------------------------------------------------------------------------

private fun Drawable.render(w: Int, h: Int): Bitmap {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    setBounds(0, 0, w, h)
    draw(c)
    return b
}

/**
 * Íconos adaptativos: se dibujan sin máscara y recortados a su zona visible (los 2/3 centrales),
 * así llenan el círculo completo. Íconos antiguos: tal cual (el círculo los rodea).
 */
private fun renderIcon(d: Drawable): Pair<Bitmap, Boolean> {
    val size = 192
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    if (d is AdaptiveIconDrawable) {
        val big = 288
        val off = ((big - size) / 2).toFloat()
        c.translate(-off, -off)
        d.background?.let {
            it.setBounds(0, 0, big, big)
            it.draw(c)
        }
        d.foreground?.let {
            it.setBounds(0, 0, big, big)
            it.draw(c)
        }
        return b to true
    }
    d.setBounds(0, 0, size, size)
    d.draw(c)
    return b to false
}

private fun Bitmap.averageColor(): Int {
    val one = Bitmap.createScaledBitmap(this, 1, 1, true)
    val px = one.getPixel(0, 0)
    if (one !== this) one.recycle()
    return px or (0xFF shl 24)
}

private fun Color.blend(other: Color, t: Float): Color =
    other.copy(alpha = t).compositeOver(this)

private fun loadWallpaper(ctx: Context, i: Int): ImageBitmap? = runCatching {
    ctx.assets.open("wallpapers/%02d.jpg".format(i + 1)).use { s ->
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeStream(s, null, o)?.asImageBitmap()
    }
}.getOrNull()

// ---------------------------------------------------------------------------------------------
// Tema
// ---------------------------------------------------------------------------------------------

private val Scheme = darkColorScheme(
    primary = Color(0xFFF2F2F7),
    onPrimary = Color(0xFF1C1C1E),
    primaryContainer = Color(0xFF3A3A3C),
    onPrimaryContainer = Color(0xFFF2F2F7),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFF2F2F7),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFE9E9EE),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE9E9EE),
    surfaceVariant = Color(0xFF48484A),
    onSurfaceVariant = Color(0xFFC7C7CC),
)

@Composable
private fun HomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

// Liquid Glass: solo en la capa de controles (barra superior y tarjetas de sistema), nunca en el contenido.
// Sin color propio: relleno translúcido, brillo en el canto superior y enfoque en blanco neutro.
internal val GlassFill = Color(0x1AFFFFFF)
internal val GlassFillFocus = Color(0x4DFFFFFF)
internal val GlassInk = Color.White
internal val GlassSheen = Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x00FFFFFF)))

internal fun glassBorder(shape: Shape, focused: Boolean = false): Border = Border(
    BorderStroke(
        if (focused) 1.5.dp else 1.dp,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (focused) 0.95f else 0.55f), Color.White.copy(alpha = 0.08f)),
        ),
    ),
    shape = shape,
)

internal fun glassGlow(): Glow = Glow(elevationColor = Color.White.copy(alpha = 0.35f), elevation = 12.dp)

internal val EDGE = 40.dp
internal val R_NORMAL = 18.dp
internal val R_FOCUS = 30.dp      // la forma "se redondea" al enfocar
internal val GLOW = 20.dp         // alcance del neón
private val ROW_PAD = 14.dp      // espacio vertical justo para que el neón no se recorte

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    return now
}

// ---------------------------------------------------------------------------------------------
// Pantalla principal
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    d: HomeData,
    account: String?,
    tick: Int,
    onApp: (AppItem) -> Unit,
    onInfo: (AppItem) -> Unit,
    onInput: (InputItem) -> Unit,
    onInputLong: (InputItem) -> Unit,
    onSettings: (String, Uri?) -> Unit,
    onProcs: () -> Unit,
    onLiveTv: () -> Unit,
    onRest: () -> Unit,
    onNativeHome: () -> Unit,
    onChannels: () -> Unit,
) {
    var section by remember { mutableStateOf("home") }
    val menuFocus = remember { FocusRequester() }

    LaunchedEffect(d.loaded, tick) {
        if (d.loaded) {
            section = "home"
            delay(80)
            runCatching { menuFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Wallpaper()

        Row(Modifier.fillMaxSize()) {
            SideMenu(
                homeFocus = menuFocus,
                account = account,
                section = section,
                hasApps = d.services.isNotEmpty() || d.inputs.isNotEmpty() || d.rec.isNotEmpty() || d.apps.isNotEmpty(),
                onSection = { section = it },
                onLiveTv = onLiveTv,
                onRest = onRest,
                onNativeHome = onNativeHome,
                onChannels = onChannels,
                onSettings = onSettings,
            )

            // el contenido ocupa todo lo que queda a la derecha del menú; el reloj se centra ahí
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (section) {
                    "apps" -> SectionGrid("Apps", 190.dp) {
                        if (d.services.isNotEmpty()) {
                            gridHeader("Streaming")
                            gridItems(d.services, key = { "svc_" + it.pkg }) { a ->
                                AppBanner(a, 176.dp, 99.dp, Modifier, onClick = { onApp(a) }, onLong = { onInfo(a) })
                            }
                        }
                        if (d.inputs.isNotEmpty()) {
                            gridHeader("Entradas")
                            gridItems(d.inputs, key = { "inp_" + it.id }) { i ->
                                InputTile(i, Modifier, onClick = { onInput(i) }, onLong = { onInputLong(i) })
                            }
                        }
                        if (d.rec.isNotEmpty()) {
                            gridHeader("Recomendadas")
                            gridItems(d.rec, key = { "rec_" + it.pkg }) { a ->
                                AppBanner(a, 152.dp, 86.dp, Modifier, onClick = { onApp(a) }, onLong = { onInfo(a) })
                            }
                        }
                        if (d.apps.isNotEmpty()) {
                            gridHeader("Todas las apps")
                            gridItems(d.apps, key = { "app_" + it.pkg }) { a ->
                                AppBanner(a, 152.dp, 86.dp, Modifier, onClick = { onApp(a) }, onLong = { onInfo(a) })
                            }
                        }
                    }
                    "sys" -> SectionGrid("Sistema", 190.dp) {
                        item { SysCard("Procesos", "procs", Color(0xFF7CC4FF)) { onProcs() } }
                        val sys = listOf(
                            Triple("Almacenamiento", "storage", Color(0xFFFFB84D)) to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                            Triple("Aplicaciones", "apps", Color(0xFF2BE38B)) to Settings.ACTION_APPLICATION_SETTINGS,
                            Triple("Pantalla", "display", Color(0xFFFF6FB5)) to Settings.ACTION_DISPLAY_SETTINGS,
                            Triple("Bluetooth", "bluetooth", Color(0xFF6C8BFF)) to Settings.ACTION_BLUETOOTH_SETTINGS,
                        )
                        gridItems(sys) { (t, action) ->
                            SysCard(t.first, t.second, t.third) { onSettings(action, null) }
                        }
                    }
                    else -> Hero(Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun LazyGridScope.gridHeader(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title,
            fontFamily = Poppins, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = SUBTLE_TEXT,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private val SUBTLE_TEXT = Color(0xB3FFFFFF)

@Composable
private fun SectionGrid(title: String, minCell: Dp, content: LazyGridScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(
            title,
            fontFamily = Poppins, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            color = Color(0xFFE9E9EE),
            modifier = Modifier.padding(start = EDGE),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = EDGE, vertical = ROW_PAD + 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Fotos de naturaleza que se cruzan cada 10 minutos, con un velo oscuro para leer bien. */
@Composable
internal fun Wallpaper() {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(Random.nextInt(WALLPAPERS)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10 * 60_000L)
            idx = (idx + 1) % WALLPAPERS
        }
    }
    Crossfade(targetState = idx, animationSpec = tween(1500), label = "wallpaper") { i ->
        val img = remember(i) { loadWallpaper(ctx, i) }
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x66000000), Color(0xA6000000), Color(0xE60F0E12))
                )
            )
    )
}

@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val is24 = DateFormat.is24HourFormat(ctx)
    val hour = clockCal(now).get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 6 -> "Buenas noches"
        hour < 12 -> "Buenos días"
        hour < 19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
    val time = clockFmt(if (is24) "HH:mm" else "h:mm").format(Date(now))
    val ampm = if (is24) "" else clockFmt(" a", Locale.US).format(Date(now))
    val date = clockFmt("EEEE d 'de' MMMM", DATE_LOCALE).format(Date(now))
        .replaceFirstChar { it.uppercase() }

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            greeting,
            fontFamily = Poppins, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            color = Color(0xFFE9E9EE),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                time,
                fontFamily = Poppins, fontSize = 120.sp, fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                ampm,
                fontFamily = Poppins, fontSize = 36.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFFE9E9EE),
                modifier = Modifier.padding(bottom = 22.dp),
            )
        }
        Text(
            date,
            fontFamily = Poppins, fontSize = 22.sp,
            color = Color(0xFFC7C7CC),
        )
    }
}

internal val MENU_W = 236.dp

/** Menú lateral de vidrio con todas las opciones; al enfocar una sección su contenido aparece a la derecha. */
@Composable
private fun SideMenu(
    homeFocus: FocusRequester,
    account: String?,
    section: String,
    hasApps: Boolean,
    onSection: (String) -> Unit,
    onLiveTv: () -> Unit,
    onRest: () -> Unit,
    onNativeHome: () -> Unit,
    onChannels: () -> Unit,
    onSettings: (String, Uri?) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val st = rememberWifi()
    Column(
        Modifier
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
            .width(MENU_W - 16.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(GlassFill)
            .background(GlassSheen)
            .border(glassBorder(shape).border, shape)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        MenuItem("home", Color.White, "Inicio", selected = section == "home",
            modifier = Modifier.focusRequester(homeFocus), onFocus = { onSection("home") }) { onSection("home") }
        MenuItem("tv", Color.White, "En vivo", onClick = onLiveTv)
        MenuItem("display", Color.White, "Canales", onClick = onChannels)
        if (hasApps) MenuItem("apps", Color.White, "Apps", selected = section == "apps",
            onFocus = { onSection("apps") }) { onSection("apps") }
        MenuItem("procs", Color.White, "Sistema", selected = section == "sys",
            onFocus = { onSection("sys") }) { onSection("sys") }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconPill("moon", Color(0xFFFFD27A)) { onRest() }
            IconPill("wifi", wifiColor(st.level), level = st.level) { onSettings(Settings.ACTION_WIFI_SETTINGS, null) }
            IconPill("gear", Color(0xFFE9E9EE)) { onSettings(Settings.ACTION_SETTINGS, null) }
        }
    }
}

@Composable
private fun MenuItem(
    kind: String,
    tint: Color,
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(vertical = 1.dp)
            .onFocusChanged { if (it.hasFocus) onFocus?.invoke() },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) GlassFill else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = GlassFillFocus,
            focusedContentColor = GlassInk,
            pressedContainerColor = GlassFillFocus,
            pressedContentColor = GlassInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) glassBorder(shape) else Border.None,
            focusedBorder = glassBorder(shape, focused = true),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = glassGlow()),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(kind, tint, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            fontFamily = Poppins, fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE6E0E9),
            modifier = Modifier.padding(start = EDGE, top = 0.dp),
        )
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Componentes
// ---------------------------------------------------------------------------------------------

/** Banner 16:9 de la app con borde neón tenue y neón fuerte al enfocar. Si la app no trae banner se arma uno con su ícono. */
@Composable
private fun AppBanner(a: AppItem, w: Dp, h: Dp, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(R_NORMAL)
    val focusShape = RoundedCornerShape(R_FOCUS)
    Surface(
        onClick = onClick,
        onLongClick = onLong,
        modifier = modifier.size(w, h),
        interactionSource = src,
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = focusShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1D1B22),
            focusedContainerColor = Color(0xFF1D1B22),
            pressedContainerColor = Color(0xFF1D1B22),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.5.dp, a.neon.copy(alpha = 0.35f)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, a.neon), shape = focusShape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = a.neon.copy(alpha = 0.9f), elevation = GLOW),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (a.banner != null) {
                Image(
                    bitmap = a.banner,
                    contentDescription = a.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // sin banner: degradado del color de la app + ícono + nombre
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(a.tint, Color(0xFF1D1B22).blend(a.neon, 0.12f)),
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (a.image != null) {
                            Box(
                                Modifier
                                    .size(h * 0.46f)
                                    .clip(CircleShape)
                                    .background(a.tint)
                            ) {
                                Image(
                                    a.image, null,
                                    Modifier
                                        .fillMaxSize()
                                        .then(if (a.fill) Modifier else Modifier.padding(4.dp)),
                                    contentScale = if (a.fill) ContentScale.Crop else ContentScale.Fit,
                                )
                            }
                        }
                    }
                    Text(
                        a.label,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontFamily = Poppins, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                    )
                }
            }
            // al enfocar, el nombre aparece sobre un degradado abajo (también en los banners con imagen)
            if (focused && a.banner != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 5.dp),
                ) {
                    Text(
                        a.label,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontFamily = Poppins, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White,
                    )
                }
            }
        }
    }
}

/** Tarjeta de Sistema: ícono dibujado + nombre, con neón del color de cada una. */
@Composable
private fun SysCard(title: String, kind: String, neon: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(R_NORMAL)
    val focusShape = RoundedCornerShape(R_FOCUS)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(176.dp, 62.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = focusShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = GlassFill,
            focusedContainerColor = GlassFillFocus,
            pressedContainerColor = GlassFillFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = glassBorder(shape),
            focusedBorder = glassBorder(focusShape, focused = true),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = glassGlow()),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(GlassSheen)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(neon.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(kind, neon, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontFamily = Poppins, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Iconos dibujados (sin librería de íconos) y botón de Wi-Fi con intensidad
// ---------------------------------------------------------------------------------------------

/** Nivel 0..4 de la señal; -1 = sin conexión; -2 = cable (Ethernet). dBm solo con Wi-Fi. */
@Immutable
class WifiState(val level: Int, val dbm: Int?)

private fun readWifi(ctx: Context): WifiState {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return WifiState(-1, null)
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val rssi = wm.connectionInfo.rssi
                WifiState(wm.calculateSignalLevel(rssi).coerceIn(0, 4), rssi)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> WifiState(-2, null)
            else -> WifiState(-1, null)
        }
    } catch (e: Exception) {
        WifiState(-1, null)
    }
}

@Composable
private fun rememberWifi(): WifiState {
    val ctx = LocalContext.current.applicationContext
    var st by remember { mutableStateOf(WifiState(-1, null)) }
    LaunchedEffect(Unit) {
        while (true) {
            st = readWifi(ctx)
            delay(4_000L)
        }
    }
    return st
}

private fun wifiColor(level: Int): Color = when {
    level >= 3 || level == -2 -> Color(0xFF2BE38B)
    level == 2 -> Color(0xFFFFC94D)
    level >= 0 -> Color(0xFFFF6B6B)
    else -> Color(0xFF79747E)
}

@Composable
internal fun IconPill(kind: String, tint: Color, level: Int = 4, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = GlassFill,
            focusedContainerColor = GlassFillFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = ClickableSurfaceDefaults.border(
            border = glassBorder(CircleShape),
            focusedBorder = glassBorder(CircleShape, focused = true),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = glassGlow()),
    ) {
        Box(Modifier.fillMaxSize().background(GlassSheen), contentAlignment = Alignment.Center) {
            Glyph(kind, tint, Modifier.size(20.dp), level = level)
        }
    }
}

/** Íconos simples dibujados con Canvas: moon, gear, wifi, procs, storage, apps, display, bluetooth. */
@Composable
internal fun Glyph(kind: String, color: Color, modifier: Modifier = Modifier, level: Int = 4) {
    Canvas(modifier) {
        val w = size.width
        val sw = w * 0.1f
        val line = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "moon" -> {
                val a = Path().apply { addOval(Rect(Offset(w * 0.12f, w * 0.12f), Size(w * 0.76f, w * 0.76f))) }
                val b = Path().apply { addOval(Rect(Offset(w * 0.36f, w * 0.0f), Size(w * 0.7f, w * 0.7f))) }
                drawPath(Path.combine(PathOperation.Difference, a, b), color)
            }
            "gear" -> {
                val c = center
                for (i in 0 until 8) {
                    rotate(i * 45f, c) {
                        drawRoundRect(
                            color, Offset(c.x - w * 0.07f, w * 0.03f), Size(w * 0.14f, w * 0.2f),
                            CornerRadius(w * 0.03f),
                        )
                    }
                }
                drawCircle(color, w * 0.3f, c, style = Stroke(width = w * 0.13f))
            }
            "wifi" -> {
                // punto + 3 arcos; se encienden según el nivel (0..4). Sin conexión: tachado.
                val lit = if (level == -2) 4 else level.coerceAtLeast(0)
                val dim = color.copy(alpha = 0.28f)
                val base = Offset(w * 0.5f, w * 0.84f)
                drawCircle(if (lit >= 1) color else dim, w * 0.075f, base)
                for (i in 1..3) {
                    val r = w * 0.2f * i
                    drawArc(
                        if (lit > i) color else dim, 225f, 90f, false,
                        Offset(base.x - r, base.y - r), Size(r * 2, r * 2), style = line,
                    )
                }
                if (level == -1) {
                    drawLine(Color(0xFFFF6B6B), Offset(w * 0.12f, w * 0.12f), Offset(w * 0.88f, w * 0.88f), sw)
                }
            }
            "tv" -> {
                drawRoundRect(
                    color, Offset(w * 0.06f, w * 0.26f), Size(w * 0.88f, w * 0.6f),
                    CornerRadius(w * 0.1f), style = line,
                )
                drawLine(color, Offset(w * 0.5f, w * 0.26f), Offset(w * 0.3f, w * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, w * 0.26f), Offset(w * 0.7f, w * 0.08f), sw, StrokeCap.Round)
            }
            "tvoff" -> {
                drawRoundRect(
                    color, Offset(w * 0.06f, w * 0.26f), Size(w * 0.88f, w * 0.6f),
                    CornerRadius(w * 0.1f), style = line,
                )
                drawLine(color, Offset(w * 0.5f, w * 0.26f), Offset(w * 0.3f, w * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, w * 0.26f), Offset(w * 0.7f, w * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, w * 0.94f), Offset(w * 0.86f, w * 0.1f), sw * 1.2f, StrokeCap.Round)
            }
            "back" -> {
                drawLine(color, Offset(w * 0.8f, w * 0.5f), Offset(w * 0.2f, w * 0.5f), sw, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.46f, w * 0.24f)
                    lineTo(w * 0.2f, w * 0.5f)
                    lineTo(w * 0.46f, w * 0.76f)
                }
                drawPath(head, color, style = line)
            }
            "search" -> {
                drawCircle(color, w * 0.28f, Offset(w * 0.43f, w * 0.43f), style = Stroke(width = sw))
                drawLine(color, Offset(w * 0.64f, w * 0.64f), Offset(w * 0.86f, w * 0.86f), sw, StrokeCap.Round)
            }
            "refresh" -> {
                val r = w * 0.34f
                val c0 = center
                drawArc(
                    color, 40f, 280f, false,
                    Offset(c0.x - r, c0.y - r), Size(r * 2, r * 2), style = line,
                )
                // punta de flecha al final del arco (320°), siguiendo la tangente
                val th = Math.toRadians(320.0)
                val ex = c0.x + r * Math.cos(th).toFloat()
                val ey = c0.y + r * Math.sin(th).toFloat()
                val tx = -Math.sin(th).toFloat()
                val ty = Math.cos(th).toFloat()
                val nx = Math.cos(th).toFloat()
                val ny = Math.sin(th).toFloat()
                val k = w * 0.14f
                val tip = Path().apply {
                    moveTo(ex + tx * k * 1.2f, ey + ty * k * 1.2f)
                    lineTo(ex + nx * k, ey + ny * k)
                    lineTo(ex - nx * k, ey - ny * k)
                    close()
                }
                drawPath(tip, color)
            }
            "home" -> {
                val roof = Path().apply {
                    moveTo(w * 0.08f, w * 0.5f)
                    lineTo(w * 0.5f, w * 0.12f)
                    lineTo(w * 0.92f, w * 0.5f)
                }
                drawPath(roof, color, style = line)
                val body = Path().apply {
                    moveTo(w * 0.22f, w * 0.44f)
                    lineTo(w * 0.22f, w * 0.88f)
                    lineTo(w * 0.78f, w * 0.88f)
                    lineTo(w * 0.78f, w * 0.44f)
                }
                drawPath(body, color, style = line)
            }
            "procs" -> {
                val bw = w * 0.2f
                for ((i, hh) in listOf(0.5f, 0.85f, 0.65f).withIndex()) {
                    val x = w * 0.1f + i * w * 0.3f
                    drawRoundRect(color, Offset(x, w * (0.92f - hh)), Size(bw, w * hh), CornerRadius(w * 0.05f))
                }
            }
            "storage" -> {
                drawRoundRect(
                    color, Offset(w * 0.1f, w * 0.28f), Size(w * 0.8f, w * 0.44f),
                    CornerRadius(w * 0.1f), style = line,
                )
                drawCircle(color, w * 0.05f, Offset(w * 0.74f, w * 0.5f))
                drawLine(color, Offset(w * 0.24f, w * 0.5f), Offset(w * 0.5f, w * 0.5f), sw, StrokeCap.Round)
            }
            "apps" -> {
                val q = w * 0.36f
                for (r in 0..1) for (cc in 0..1) {
                    drawRoundRect(
                        color, Offset(w * 0.1f + cc * (q + w * 0.08f), w * 0.1f + r * (q + w * 0.08f)),
                        Size(q, q), CornerRadius(w * 0.08f),
                    )
                }
            }
            "display" -> {
                drawRoundRect(
                    color, Offset(w * 0.06f, w * 0.14f), Size(w * 0.88f, w * 0.56f),
                    CornerRadius(w * 0.08f), style = line,
                )
                drawLine(color, Offset(w * 0.5f, w * 0.7f), Offset(w * 0.5f, w * 0.84f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.3f, w * 0.86f), Offset(w * 0.7f, w * 0.86f), sw, StrokeCap.Round)
            }
            "bluetooth" -> {
                val pts = listOf(
                    0.25f to 0.3f, 0.75f to 0.7f, 0.5f to 0.92f, 0.5f to 0.08f, 0.75f to 0.3f, 0.25f to 0.7f,
                )
                val path = Path().apply {
                    moveTo(w * pts[0].first, w * pts[0].second)
                    for (i in 1 until pts.size) lineTo(w * pts[i].first, w * pts[i].second)
                }
                drawPath(path, color, style = line)
            }
        }
    }
}

/** Atajo a una entrada: el logo llena la tarjeta y una etiqueta chica dice qué entrada es. */
@Composable
private fun InputTile(i: InputItem, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLong,
        modifier = modifier.size(168.dp, 70.dp),   // 2.4:1, la misma proporción que los logos
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(R_NORMAL),
            focusedShape = RoundedCornerShape(R_FOCUS),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xCC2B2833),
            focusedContainerColor = Color(0xE62B2833),
            pressedContainerColor = Color(0xE62B2833),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, i.neon), shape = RoundedCornerShape(R_FOCUS)),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = i.neon.copy(alpha = 0.9f), elevation = GLOW),
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(if (i.connected) 1f else 0.55f)
        ) {
            if (i.logo != null) {
                Image(
                    bitmap = i.logo,
                    contentDescription = i.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                Text(
                    i.label,
                    fontFamily = Poppins, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 10.dp),
                )
            }
            // etiqueta en la esquina: no tapa el logo
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (i.id != TV_ID) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (i.connected) i.neon else Color(0xFF79747E))
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    i.original,
                    fontFamily = Poppins, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = GlassFill,
            contentColor = Color.White,
            focusedContainerColor = GlassFillFocus,
            focusedContentColor = GlassInk,
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
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontFamily = Poppins, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Modo reposo: paisajes con zoom lento y un reloj de vidrio que se desplaza despacio
// ---------------------------------------------------------------------------------------------

private const val REST_SLIDE_MS = 25_000L        // cada cuánto cambia la foto
private const val REST_BRIGHTNESS = 0.35f        // brillo en reposo: suficiente para ver las fotos sin molestar

@Composable
private fun RestScreen() {
    val ctx = LocalContext.current
    val now = rememberNow()
    val is24 = DateFormat.is24HourFormat(ctx)
    val time = clockFmt(if (is24) "HH:mm" else "h:mm").format(Date(now))
    val ampm = if (is24) "" else clockFmt(" a", Locale.US).format(Date(now))
    val date = clockFmt("EEEE d 'de' MMMM", DATE_LOCALE).format(Date(now)).replaceFirstChar { it.uppercase() }

    var idx by remember { mutableIntStateOf(Random.nextInt(WALLPAPERS)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REST_SLIDE_MS)
            idx = (idx + 1) % WALLPAPERS
        }
    }

    // el reloj cambia de sitio un poco cada minuto, muy despacio
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(now / 60_000L) {
        tx = Random.nextFloat() * 2f - 1f
        ty = Random.nextFloat() * 2f - 1f
    }
    val ax by animateFloatAsState(tx, tween(20_000), label = "x")
    val ay by animateFloatAsState(ty, tween(20_000), label = "y")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = idx, animationSpec = tween(2500), label = "rest") { i ->
            val img = remember(i) { loadWallpaper(ctx, i) }
            var go by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { go = true }
            val dir = if (i % 2 == 0) 1f else -1f
            val zoom by animateFloatAsState(if (go) 1.18f else 1.04f, tween(30_000, easing = LinearEasing), label = "zoom")
            val pan by animateFloatAsState(if (go) -dir * 36f else dir * 36f, tween(30_000, easing = LinearEasing), label = "pan")
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan },
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // velo suave y más oscuro abajo, donde va el reloj
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x40000000), Color(0x26000000), Color(0xA6000000)),
                    )
                )
        )

        val shape = RoundedCornerShape(32.dp)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = (ax * 16).dp, y = (ay * 10).dp)
                .padding(start = 56.dp, bottom = 52.dp)
                .clip(shape)
                .background(GlassFill)
                .background(GlassSheen)
                .border(glassBorder(shape).border, shape)
                .padding(horizontal = 32.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    time,
                    fontFamily = Poppins, fontSize = 72.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xE6FFFFFF),
                )
                if (ampm.isNotEmpty()) {
                    Text(
                        ampm,
                        fontFamily = Poppins, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        color = Color(0xB3FFFFFF),
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            }
            Text(
                date,
                fontFamily = Poppins, fontSize = 18.sp,
                color = Color(0xB3FFFFFF),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Procesos en segundo plano
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProcPanel(
    procs: List<ProcItem>,
    hasAccess: Boolean,
    freeMb: Int,
    onKill: (ProcItem) -> Unit,
    onKillAll: () -> Unit,
    onGrant: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { fr.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(horizontal = EDGE, vertical = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Segundo plano",
                fontFamily = Poppins, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = cs.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "RAM libre: $freeMb MB",
                fontFamily = Poppins, fontSize = 14.sp,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            if (hasAccess) {
                Pill("Cerrar todas", Modifier.focusRequester(fr)) { onKillAll() }
            } else {
                Pill("Dar permiso", Modifier.focusRequester(fr)) { onGrant() }
            }
            Spacer(Modifier.width(8.dp))
            Pill("Volver") { onClose() }
        }
        Spacer(Modifier.height(10.dp))

        when {
            !hasAccess -> {
                Text(
                    "Falta el permiso de uso de apps. Si el botón no abre nada, ejecuta esto en tu computadora y vuelve a entrar:",
                    fontFamily = Poppins, fontSize = 13.sp, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "adb shell appops set app.tvhome.launcher GET_USAGE_STATS allow",
                    fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = cs.primary,
                )
            }
            procs.isEmpty() -> Text(
                "No hay apps recientes para cerrar.",
                fontFamily = Poppins, fontSize = 14.sp, color = cs.onSurfaceVariant,
            )
            else -> {
                Text(
                    "Apps usadas en las últimas 6 horas. OK sobre una para cerrarla.",
                    fontFamily = Poppins, fontSize = 12.sp, color = cs.onSurfaceVariant,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    items(procs, key = { it.pkg }) { p -> ProcRow(p) { onKill(p) } }
                }
            }
        }
    }
}

@Composable
private fun ProcRow(p: ProcItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mins = ((System.currentTimeMillis() - p.lastUsed) / 60_000L).coerceAtLeast(0)
    val ago = if (mins < 60) "hace $mins min" else "hace ${mins / 60} h"
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp),
            focusedShape = RoundedCornerShape(26.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            focusedContainerColor = cs.primaryContainer,
            focusedContentColor = cs.onPrimaryContainer,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = cs.primary.copy(alpha = 0.8f), elevation = 18.dp),
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.icon != null) {
                Image(p.icon, null, Modifier.size(32.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(p.label, fontFamily = Poppins, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(ago, fontFamily = Poppins, fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text("Cerrar", fontFamily = Poppins, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
