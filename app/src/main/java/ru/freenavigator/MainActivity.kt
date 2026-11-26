package ru.freenavigator

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ProgressBar
import android.widget.ScrollView
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.view.setPadding
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textRotate
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.roundToInt
import kotlin.math.tan
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.Date

/** First usable map screen. Search uses the public Photon demo geocoder. */
class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var darkTheme = false
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var searchPanel: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: LinearLayout
    private var searchExpanded = false
    private var radarPopupText: TextView? = null
    private val radarPopupHandler = Handler(Looper.getMainLooper())
    private val radarSoundHandler = Handler(Looper.getMainLooper())
    private var radarToneGenerator: ToneGenerator? = null
    private val hideRadarPopup = Runnable { radarPopupText?.visibility = View.GONE }
    private var radarEnabled = true
    private var locationSource: GeoJsonSource? = null
    private var radarSource: GeoJsonSource? = null
    private var lastKnownPoint: Point? = null
    private var searchResultSource: GeoJsonSource? = null
    private var selectedSearchPoint: Point? = null
    private var routeLine: LineString? = null
    private var navigationActive = false
    private var navigationOffline = false
    private var foregroundGuidanceStarted = false
    private val guidanceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!navigationActive) return
            foregroundGuidanceStarted = intent?.getBooleanExtra(NavigationForegroundService.EXTRA_TTS_READY, false) ?: false
        }
    }
    private val radarAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(NavigationForegroundService.EXTRA_RADAR_MESSAGE).orEmpty()
            if (message.isNotBlank()) showRadarPopup(message)
        }
    }
    private var navigationLocationListener: LocationListener? = null
    private var navigationSource: GeoJsonSource? = null
    private var navigationPoints: List<Point> = emptyList()
    private var navigationDistanceMeters = 0.0
    private var navigationDurationSeconds = 0
    private var navigationSteps: List<RouteStep> = emptyList()
    private var nextVoiceStepIndex = 0
    private var currentRouteIndex = 0
    private var navigationInfo: TextView? = null
    private var maneuverInfo: TextView? = null
    private var maneuverIcon: TextView? = null
    private var maneuverHud: LinearLayout? = null
    private var maneuverHudIcon: TextView? = null
    private var maneuverHudText: TextView? = null
    private var navigationPanel: LinearLayout? = null
    private var bottomControlsView: LinearLayout? = null
    private var routeProgress: ProgressBar? = null
    private var routeStartTime: TextView? = null
    private var routeArrivalTime: TextView? = null
    private var routeRemainingText: TextView? = null
    private var navigationStartedAt = 0L
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var arrivalAnnounced = false
    private var radarLocationListener: LocationListener? = null
    private val radarWarningStages = mutableMapOf<String, Int>()
    private var lastNavigationLocation: Location? = null
    private var pendingNavigation: RouteData? = null
    private var rerouteInProgress = false
    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var lastAcceptedFix: Location? = null
    private var lastAcceptedFixTime = 0L

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            val pending = pendingNavigation
            if (pending != null) {
                pendingNavigation = null
                beginNavigation(pending)
            } else centerOnMyLocation()
            startRadarMonitoring()
        } else {
            pendingNavigation = null
            toast("Разрешение на местоположение не предоставлено")
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Без разрешения уведомлений предупреждения о камерах могут не появляться поверх других приложений")
    }

    private val offlineIndexPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            screenScope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            OfflinePlaceIndex(this@MainActivity).replaceFromGeoJson(stream)
                        } ?: throw IllegalStateException("Не удалось открыть файл")
                    }
                    toast("Офлайн-поиск: загружено объектов — $imported")
                } catch (e: Exception) {
                    toast("Не удалось импортировать базу: ${e.message ?: "ошибка файла"}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)
        configureMapNetworking()
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this, guidanceStatusReceiver,
            IntentFilter(NavigationForegroundService.ACTION_TTS_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, radarAlertReceiver,
            IntentFilter(NavigationForegroundService.ACTION_RADAR_ALERT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        radarEnabled = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
            .getBoolean("radar_enabled", true)
        seedBundledRadarDatabase()
        // Clear obsolete optional traffic settings; live traffic is postponed.
        getSharedPreferences("free_navigator_settings", MODE_PRIVATE).edit()
            .remove("tomtom_api_key").remove("traffic_enabled").apply()
        speech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val languageStatus = speech?.setLanguage(Locale("ru", "RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                speechReady = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                    languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                speechReady = false
            }
        }
        buildScreen(savedInstanceState)
        scheduleRadarUpdates()
    }

    private fun buildScreen(savedInstanceState: Bundle?) {
        val root = FrameLayout(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        // Compact white-and-blue toolbar. The map remains visible behind it.
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(7).toFloat()
        }
        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFF174A7E.toInt())
            contentDescription = "Открыть меню"
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { showDrawer() }
        }
        topPanel.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(topPanel, FrameLayout.LayoutParams(dp(64), -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10); topMargin = dp(10)
        })

        // Search is collapsed until the user taps FIND.
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        searchInput = EditText(this).apply {
            hint = "Адрес или название места"
            setSingleLine(true)
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), 0, dp(8), 0)
            background = null
        }
        val closeSearch = TextView(this).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF52677C.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Закрыть поиск"
            setOnClickListener { hideSearch() }
        }
        searchPanel.addView(searchInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        searchPanel.addView(closeSearch, LinearLayout.LayoutParams(dp(42), dp(44)))
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { searchPlace(); true } else false
        }

        // The two familiar map controls stay on the right-hand side.
        val rightButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val locateButton = TextView(this).apply {
            text = "◎"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFF1769AA.toInt())
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(5).toFloat()
            contentDescription = "Моё местоположение"
            setOnClickListener { requestLocationOrCenter() }
        }
        val themeButton = TextView(this).apply {
            text = "☾"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFF1769AA.toInt())
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(5).toFloat()
            contentDescription = "Переключить тему карты"
            setOnClickListener { toggleMapTheme(this) }
        }
        val zoomInButton = TextView(this).apply {
            text = "+"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(0xFF1769AA.toInt())
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(5).toFloat()
            contentDescription = "Увеличить карту"
            setOnClickListener { changeZoom(1.0) }
        }
        val zoomOutButton = TextView(this).apply {
            text = "−"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(0xFF1769AA.toInt())
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(5).toFloat()
            contentDescription = "Уменьшить карту"
            setOnClickListener { changeZoom(-1.0) }
        }
        val compassButton = TextView(this).apply {
            text = "↑N"
            textSize = 20f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1769AA.toInt())
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(5).toFloat()
            contentDescription = "Повернуть карту на север"
            setOnClickListener {
                val current = map?.cameraPosition
                if (current != null) {
                    map?.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(current.target)
                                .zoom(current.zoom)
                                .bearing(0.0)
                                .tilt(current.tilt)
                                .build()
                        )
                    )
                }
            }
        }
        rightButtons.addView(locateButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        rightButtons.addView(themeButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
        rightButtons.addView(zoomInButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
        rightButtons.addView(zoomOutButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
        rightButtons.addView(compassButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
        root.addView(rightButtons, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(10)
        })

        // Keep status updates internally, but do not show the large bottom information panel.
        // This leaves the map unobstructed and removes the location/attribution captions.
        statusText = TextView(this).apply {
            text = ""
            visibility = View.GONE
        }

        // Search is opened only from the side menu and sits below the top toolbar.
        root.addView(searchPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(82); rightMargin = dp(70); topMargin = dp(10)
        })
        bottomControlsView = null

        // Navigation bottom panel: live progress, start/arrival times and exact remaining-distance caption.
        val navPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(9).toFloat()
            visibility = View.GONE
        }
        val maneuverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(0xFFEAF3FB.toInt())
        }
        maneuverIcon = TextView(this).apply {
            text = "↑"
            textSize = 30f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1769AA.toInt())
        }
        maneuverInfo = TextView(this).apply {
            text = "Ожидаем маршрут…"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF173B5E.toInt())
            setPadding(dp(12), 0, 0, 0)
        }
        maneuverRow.addView(maneuverIcon, LinearLayout.LayoutParams(dp(48), dp(48)))
        maneuverRow.addView(maneuverInfo, LinearLayout.LayoutParams(0, -2, 1f))
        maneuverRow.visibility = View.GONE
        navPanel.addView(maneuverRow, LinearLayout.LayoutParams(-1, -2))
        val navTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val navInfo = TextView(this).apply {
            text = "Получаем координаты GPS…"
            textSize = 13f
            setTextColor(0xFF173B5E.toInt())
        }
        navigationInfo = navInfo
        navTopRow.addView(navInfo, LinearLayout.LayoutParams(0, -2, 1f))
        val stopNavigation = TextView(this).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFB3261E.toInt())
            contentDescription = "Завершить маршрут"
            setOnClickListener { stopNavigation() }
        }
        navTopRow.addView(stopNavigation, LinearLayout.LayoutParams(dp(44), dp(40)))
        navPanel.addView(navTopRow)
        routeProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF1769AA.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDCE7F1.toInt())
        }
        navPanel.addView(routeProgress, LinearLayout.LayoutParams(-1, dp(5)).apply {
            topMargin = dp(8); bottomMargin = dp(5)
        })
        val timesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        routeStartTime = TextView(this).apply { textSize = 11f; setTextColor(0xFF60758A.toInt()) }
        routeArrivalTime = TextView(this).apply { textSize = 11f; gravity = Gravity.END; setTextColor(0xFF60758A.toInt()) }
        timesRow.addView(routeStartTime, LinearLayout.LayoutParams(0, -2, 1f))
        timesRow.addView(routeArrivalTime, LinearLayout.LayoutParams(0, -2, 1f))
        navPanel.addView(timesRow)
        routeRemainingText = TextView(this).apply {
            text = "До конца маршрута: — км."
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF173B5E.toInt())
            setPadding(0, dp(5), 0, 0)
        }
        navPanel.addView(routeRemainingText)
        navigationPanel = navPanel
        root.addView(navPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(14)
        })

        // Prominent next-maneuver banner, separate from the bottom route-progress panel.
        val maneuverBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE1769AA.toInt())
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        val bannerIcon = TextView(this).apply {
            text = "↑"; textSize = 30f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        }
        val bannerText = TextView(this).apply {
            text = "Определяем следующий манёвр…"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), 0, 0, 0)
            maxLines = 2
        }
        maneuverBanner.addView(bannerIcon, LinearLayout.LayoutParams(dp(44), dp(46)))
        maneuverBanner.addView(bannerText, LinearLayout.LayoutParams(0, -2, 1f))
        maneuverHud = maneuverBanner
        maneuverHudIcon = bannerIcon
        maneuverHudText = bannerText
        root.addView(maneuverBanner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(82); rightMargin = dp(70); topMargin = dp(66)
        })

        val radarPopup = TextView(this).apply {
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEEB3261E.toInt())
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        radarPopupText = radarPopup
        root.addView(radarPopup, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(132)
        })

        // Drawer is added last so it opens above the map controls.
        drawerScrim = View(this).apply {
            setBackgroundColor(0x660B1E32)
            visibility = View.GONE
            setOnClickListener { hideDrawer() }
        }
        root.addView(drawerScrim, FrameLayout.LayoutParams(-1, -1))
        drawerPanel = buildDrawer()
        drawerPanel.visibility = View.GONE
        root.addView(drawerPanel, FrameLayout.LayoutParams(dp(300), -1, Gravity.START).apply {
            topMargin = dp(0)
        })

        setContentView(root)
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.isCompassEnabled = false
            loadMapStyle()
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(55.751244, 37.618423))
                .zoom(4.8)
                .build()
        }
    }

    private fun buildDrawer(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(12).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(12))
        }
        val heading = TextView(this).apply {
            text = "FREE NAVIGATOR"
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF173D6B.toInt())
            setPadding(dp(22), dp(8), dp(16), dp(3))
        }
        val subheading = TextView(this).apply {
            text = "Ваш помощник в дороге"
            textSize = 12f
            setTextColor(0xFF718399.toInt())
            setPadding(dp(22), 0, dp(16), dp(18))
        }
        content.addView(heading)
        content.addView(subheading)
        drawerDivider(content)
        drawerItem(content, "⌖", "Карта и режим", "Онлайн / офлайн") { showMapModeDialog() }
        drawerItem(content, "⇩", "Загрузка офлайн-карт", "Сохранить выбранную территорию") {
            hideDrawer()
            showOfflineMapsDialog()
        }
        drawerItem(content, "⌕", "Загрузить офлайн-поиск", "Сохранить адреса и места рядом с картой") {
            hideDrawer()
            downloadOfflinePlaces()
        }
        drawerItem(content, "⌁", "Загрузить дорожную сеть", "Подготовить маршруты без интернета рядом с картой") {
            hideDrawer()
            downloadOfflineRoads()
        }
        drawerItem(content, "◇", "Вид карты", "2D / 3D / спутник") { showMapTypeDialog() }
        drawerDivider(content)
        drawerItem(content, "⌕", "Поиск", "Найти адрес или место") {
            hideDrawer()
            toggleSearch()
        }
        drawerItem(content, "➤", "Проложить маршрут", "От моего местоположения до адреса") {
            hideDrawer()
            startRoutePlanning()
        }
        drawerItem(content, "☆", "Избранное", "Сохранённые места") {
            infoDialog("Избранное", "Сохранение избранных мест будет добавлено позже.")
        }
        drawerDivider(content)
        drawerItem(content, "◉", "Антирадар", radarStatusText()) {
            radarEnabled = !radarEnabled
            getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
                .edit().putBoolean("radar_enabled", radarEnabled).apply()
            // Rebuild the drawer row so its status reflects the saved selection.
            val updatedDrawer = buildDrawer()
            drawerPanel.removeAllViews()
            while (updatedDrawer.childCount > 0) {
                val child = updatedDrawer.getChildAt(0)
                updatedDrawer.removeViewAt(0)
                drawerPanel.addView(child)
            }
            hideDrawer()
            if (radarEnabled) {
                startRadarMonitoring()
                requestLocationOrCenter()
                currentDeviceLocation()?.let { enqueueRadarRefresh(it.latitude, it.longitude, true) }
            } else stopRadarMonitoring()
            toast(if (radarEnabled) "Антирадар включён" else "Антирадар выключен")
        }
        
        drawerItem(content, "⬇", "База камер", radarDatabaseStatus()) { showRadarDatabaseDialog() }
drawerItem(content, "⏻", "Выход", "Закрыть навигатор") { showExitDialog() }
        drawerItem(content, "⚙", "Настройки", "Параметры навигатора") {
            infoDialog("Настройки", "Дополнительные настройки добавим по мере подключения функций.")
        }
        // Keep the version footer fixed; only the menu items participate in scrolling.
        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            isSmoothScrollingEnabled = true
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        val version = TextView(this).apply {
            text = "FreeNavigator · версия 0.6.0"
            textSize = 11f
            setTextColor(0xFF91A0B1.toInt())
            setPadding(dp(22), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF7F9FC.toInt())
        }
        panel.addView(version, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun drawerItem(panel: LinearLayout, icon: String, title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(10))
            setOnClickListener { action() }
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val iconView = TextView(this).apply {
            text = icon
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF1769AA.toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF20364D.toInt())
        }
        val subView = TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(0xFF7A8999.toInt())
        }
        texts.addView(titleView)
        texts.addView(subView)
        titleView.maxLines = 2
        subView.maxLines = 2
        row.addView(iconView, LinearLayout.LayoutParams(dp(32), dp(48)))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.minimumHeight = dp(68)
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun drawerDivider(panel: LinearLayout) {
        val line = View(this).apply { setBackgroundColor(0xFFE8EEF4.toInt()) }
        panel.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(5); bottomMargin = dp(5)
        })
    }

    private fun showDrawer() {
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
    }

    private fun hideDrawer() {
        drawerScrim.visibility = View.GONE
        drawerPanel.visibility = View.GONE
    }

    private fun toggleSearch() {
        if (searchExpanded) hideSearch() else {
            hideDrawer()
            searchExpanded = true
            searchPanel.visibility = View.VISIBLE
            searchInput.requestFocus()
            val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearch() {
        searchExpanded = false
        searchPanel.visibility = View.GONE
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }



    private fun downloadOfflinePlaces() {
        val center = map?.cameraPosition?.target ?: currentDeviceLocation()?.let { LatLng(it.latitude, it.longitude) }
        if (center == null) {
            toast("Сначала определите местоположение или откройте нужную область карты")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Загрузить места для офлайн-поиска?")
            .setMessage("Сохраним названные места, организации, магазины, аптеки, больницы, станции и достопримечательности в радиусе 10 км. Данные будут добавлены к ранее сохранённым объектам.")
            .setPositiveButton("Загрузить") { _, _ ->
                statusText.text = "Загружаем места для офлайн-поиска…"
                screenScope.launch {
                    try {
                        val count = withContext(Dispatchers.IO) {
                            val query = "[out:json][timeout:60];(nwr(around:10000,${center.latitude},${center.longitude})[name];nwr(around:10000,${center.latitude},${center.longitude})[addr:housenumber];);out center;"
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            val endpoints = listOf(
                                "https://overpass-api.de/api/interpreter?data=",
                                "https://overpass.kumi.systems/api/interpreter?data="
                            )
                            var body: String? = null
                            var lastError: Exception? = null
                            for (endpoint in endpoints) {
                                try { body = httpGetLong(endpoint + encoded); break }
                                catch (error: Exception) { lastError = error }
                            }
                            val response = body ?: throw lastError ?: IllegalStateException("Серверы мест недоступны")
                            val elements = JSONObject(response).optJSONArray("elements")
                                ?: throw IllegalStateException("Сервер не вернул список мест")
                            val features = org.json.JSONArray()
                            val categories = setOf("amenity", "shop", "tourism", "leisure", "historic", "railway", "healthcare", "emergency", "office", "craft", "natural", "public_transport")
                            for (i in 0 until elements.length()) {
                                val item = elements.optJSONObject(i) ?: continue
                                val tags = item.optJSONObject("tags") ?: continue
                                val addressLabel = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber"))
                                    .map { it.trim() }.filter { it.isNotBlank() && it != "null" }.joinToString(" ")
                                val name = sequenceOf("name:ru", "name", "official_name", "brand")
                                    .map { tags.optString(it).trim() }
                                    .firstOrNull { it.isNotBlank() && it != "null" }
                                    ?: addressLabel.takeIf { it.isNotBlank() } ?: continue
                                val categoryKey = categories.firstOrNull { tags.has(it) }
                                    ?: if (tags.has("addr:housenumber")) "address" else continue
                                val centerPoint = item.optJSONObject("center")
                                val lat = if (item.has("lat")) item.optDouble("lat", Double.NaN) else centerPoint?.optDouble("lat", Double.NaN) ?: Double.NaN
                                val lon = if (item.has("lon")) item.optDouble("lon", Double.NaN) else centerPoint?.optDouble("lon", Double.NaN) ?: Double.NaN
                                if (!lat.isFinite() || !lon.isFinite()) continue
                                features.put(JSONObject().apply {
                                    put("type", "Feature")
                                    put("id", item.optString("type") + "/" + item.optString("id"))
                                    put("geometry", JSONObject().apply {
                                        put("type", "Point")
                                        put("coordinates", org.json.JSONArray().put(lon).put(lat))
                                    })
                                    put("properties", JSONObject().apply {
                                        put("osm_id", item.optString("type") + "/" + item.optString("id"))
                                        put("name", name)
                                        put("name:ru", tags.optString("name:ru", name))
                                        put("category", if (categoryKey == "address") "address" else categoryKey + ":" + tags.optString(categoryKey))
                                        put("city", tags.optString("addr:city", tags.optString("addr:suburb", "")))
                                    })
                                })
                                if (features.length() >= 12000) break
                            }
                            if (features.length() == 0) throw IllegalStateException("В этой области не найдено именованных объектов нужных категорий")
                            val geoJson = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
                            java.io.ByteArrayInputStream(geoJson.toByteArray(Charsets.UTF_8)).use {
                                OfflinePlaceIndex(this@MainActivity).mergeFromGeoJson(it)
                            }
                        }
                        statusText.text = "Офлайн-поиск обновлён"
                        toast("Добавлено объектов: $count. Они доступны в поиске без интернета.")
                    } catch (error: Exception) {
                        statusText.text = "Не удалось загрузить офлайн-поиск"
                        toast("Загрузка мест не удалась: ${error.message ?: "ошибка сети"}")
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadOfflineRoads() {
        val center = map?.cameraPosition?.target ?: currentDeviceLocation()?.let { LatLng(it.latitude, it.longitude) }
        if (center == null) {
            toast("Сначала определите местоположение или откройте нужную область карты")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Загрузить дорожную сеть?")
            .setMessage("Будут загружены автомобильные дороги в радиусе 8 км от центра карты. Данные сохранятся на телефоне и будут использоваться для офлайн-прокладки маршрутов.")
            .setPositiveButton("Загрузить") { _, _ ->
                statusText.text = "Загружаем дорожную сеть…"
                screenScope.launch {
                    try {
                        val edgeCount = withContext(Dispatchers.IO) {
                            val query = "[out:json][timeout:45];way(around:8000,${center.latitude},${center.longitude})[highway~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|road)$\"];out geom;"
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            val endpoints = listOf("https://overpass-api.de/api/interpreter?data=", "https://overpass.kumi.systems/api/interpreter?data=")
                            var lastError: Exception? = null
                            var imported = 0
                            for (endpoint in endpoints) {
                                try {
                                    val body = httpGetLong(endpoint + encoded)
                                    imported = useOfflineRoadGraph { it.mergeFromOverpassJson(body) }
                                    break
                                } catch (error: Exception) { lastError = error }
                            }
                            if (imported == 0) throw lastError ?: IllegalStateException("Дорожные данные не получены")
                            imported
                        }
                        statusText.text = "Офлайн-дороги сохранены"
                        toast("Загружено дорожных сегментов: $edgeCount. Теперь можно строить офлайн-маршруты в пределах сохранённой области.")
                    } catch (error: Exception) {
                        statusText.text = "Не удалось загрузить дорожную сеть"
                        toast("Загрузка дорог не удалась: ${error.message ?: "ошибка сети"}")
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun httpGetLong(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "FreeNavigator/0.6.0 (Android offline road graph)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("Overpass HTTP $code: ${response.take(180)}")
            return response
        } finally { connection.disconnect() }
    }

    private fun <T> useOfflineRoadGraph(block: (OfflineRoadGraph) -> T): T {
        val graph = OfflineRoadGraph(this)
        return try { block(graph) } finally { graph.close() }
    }

    private fun calculateOfflineRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteData {
        val result = useOfflineRoadGraph { it.calculateRoute(fromLat, fromLon, toLat, toLon, fastest = true) }
            ?: throw IllegalStateException("Офлайн-путь не найден. Загрузите дорожные данные для обеих точек маршрута.")
        val points = result.points.map { Point.fromLngLat(it.second, it.first) }
        val steps = result.steps.map { step ->
            RouteStep(Point.fromLngLat(step.longitude, step.latitude), step.instruction, step.distanceMeters)
        }
        return RouteData(points, result.durationSeconds, result.distanceMeters, steps, offline = true)
    }

    /** Keep OpenFreeMap offline downloads within the provider's published limits. */
    private fun configureMapNetworking() {
        try {
            val dispatcher = Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 4
            }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "FreeNavigator/0.6.0 (Android offline map navigation)")
                        .build()
                    chain.proceed(request)
                }
                .build()
            HttpRequestUtil.setOkHttpClient(client)
            OfflineManager.getInstance(this).setOfflineMapboxTileCountLimit(15000L)
        } catch (error: Exception) {
            android.util.Log.e("FreeNavigator", "Map download configuration failed", error)
        }
    }

    private fun showOfflineMapsDialog() {
        val options = arrayOf("Выбрать страну, регион или город", "Скачать текущую область карты", "Мои офлайн-карты", "Закрыть")
        AlertDialog.Builder(this)
            .setTitle("Офлайн-карты")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> promptOfflineTerritorySearch()
                    1 -> confirmCurrentAreaDownload()
                    2 -> showOfflineRegionList()
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun promptOfflineTerritorySearch() {
        val field = EditText(this).apply {
            hint = "Например: Московская область, Казань"
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Что сохранить для офлайн-режима?")
            .setMessage("Поиск по России: страна, республика, край, область, район или город.")
            .setView(field)
            .setPositiveButton("Найти") { _, _ ->
                val query = field.text.toString().trim()
                if (query.length < 2) {
                    toast("Введите название территории")
                } else {
                    searchOfflineTerritories(query)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun searchOfflineTerritories(query: String) {
        statusText.text = "Ищем территорию: $query…"
        screenScope.launch {
            try {
                val matches = withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val body = httpGet("https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&addressdetails=1&limit=8&countrycodes=ru&accept-language=ru")
                    val array = org.json.JSONArray(body)
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val box = item.optJSONArray("boundingbox") ?: return@mapNotNull null
                        if (box.length() < 4) return@mapNotNull null
                        val south = box.optString(0).toDoubleOrNull() ?: return@mapNotNull null
                        val north = box.optString(1).toDoubleOrNull() ?: return@mapNotNull null
                        val west = box.optString(2).toDoubleOrNull() ?: return@mapNotNull null
                        val east = box.optString(3).toDoubleOrNull() ?: return@mapNotNull null
                        if (south >= north || west >= east) return@mapNotNull null
                        val fullName = item.optString("display_name")
                        val shortName = fullName.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            .take(3).joinToString(", ")
                        val kind = item.optString("type").takeIf { it.isNotBlank() && it != "null" }
                        val label = listOfNotNull(shortName, kind).distinct().joinToString(" · ")
                        OfflineTerritory(label, fullName, south, north, west, east)
                    }
                }
                if (matches.isEmpty()) {
                    toast("В России ничего не найдено. Уточните название.")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Выберите территорию")
                        .setItems(matches.map { it.label }.toTypedArray()) { _, index ->
                            confirmTerritoryDownload(matches[index])
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            } catch (error: Exception) {
                toast("Не удалось найти территорию: проверьте интернет")
            }
        }
    }

    private data class OfflineTerritory(
        val label: String,
        val fullName: String,
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double
    )

    private fun confirmTerritoryDownload(territory: OfflineTerritory) {
        val bounds = try {
            LatLngBounds.Builder()
                .include(LatLng(territory.south, territory.west))
                .include(LatLng(territory.north, territory.east))
                .build()
        } catch (_: Exception) {
            toast("Не удалось определить границы территории")
            return
        }
        var maxZoom = 14
        var estimate = estimateOfflineTileCount(bounds, 5, maxZoom)
        while (estimate > 15000L && maxZoom > 5) {
            maxZoom--
            estimate = estimateOfflineTileCount(bounds, 5, maxZoom)
        }
        if (estimate <= 0L || estimate > 15000L) {
            infoDialog("Территория слишком большая", "Даже при обзорном масштабе она превышает лимит одной загрузки. Выберите отдельную область или город.")
            return
        }
        val minZoom = 5
        val estimatedMb = estimate * 12L / 1024L
        AlertDialog.Builder(this)
            .setTitle("Сохранить ${territory.label}?")
            .setMessage("Будет загружена территория из России с масштабами $minZoom–$maxZoom.\n\nРасчётно: $estimate плиток, около $estimatedMb МБ. Для больших областей приложение автоматически уменьшает максимальное увеличение, чтобы уложиться в лимит одной загрузки.")
            .setPositiveButton("Скачать") { _, _ ->
                startOfflineRegionDownload(bounds, territory.label, estimate, minZoom.toDouble(), maxZoom.toDouble())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmCurrentAreaDownload() {
        val bounds = try { map?.projection?.visibleRegion?.latLngBounds } catch (_: Exception) { null }
        if (bounds == null) {
            infoDialog("Карта ещё загружается", "Подождите, пока карта появится на экране, затем повторите попытку.")
            return
        }
        val tileEstimate = estimateOfflineTileCount(bounds, 8, 14)
        if (tileEstimate <= 0L || tileEstimate > 15000L) {
            infoDialog(
                "Область слишком большая",
                "Сейчас расчётная загрузка — около $tileEstimate картографических плиток. Увеличьте масштаб карты до нужного города или района и повторите. Лимит одной загрузки — 15 000 плиток и 200 МБ."
            )
            return
        }
        val title = "Область карты"
        val estimatedMb = tileEstimate * 12L / 1024L
        AlertDialog.Builder(this)
            .setTitle("Скачать офлайн-карту?")
            .setMessage("Будет сохранена видимая область с уровнями масштаба 8–14.\n\nРасчётно: $tileEstimate плиток, около $estimatedMb МБ. Фактический размер зависит от данных карты. Загрузка выполняется только после вашего подтверждения.")
            .setPositiveButton("Скачать") { _, _ -> startOfflineRegionDownload(bounds, title, tileEstimate) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun estimateOfflineTileCount(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
        fun tileX(lon: Double, zoom: Int): Int {
            val n = 1 shl zoom
            return floor(((lon + 180.0) / 360.0 * n)).toInt().coerceIn(0, n - 1)
        }
        fun tileY(lat: Double, zoom: Int): Int {
            val n = 1 shl zoom
            val safeLat = lat.coerceIn(-85.05112878, 85.05112878)
            val rad = Math.toRadians(safeLat)
            return floor((1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * n)
                .toInt().coerceIn(0, n - 1)
        }
        var total = 0L
        for (zoom in minZoom..maxZoom) {
            val west = tileX(bounds.longitudeWest, zoom)
            val east = tileX(bounds.longitudeEast, zoom)
            val north = tileY(bounds.latitudeNorth, zoom)
            val south = tileY(bounds.latitudeSouth, zoom)
            val xCount = if (east >= west) east - west + 1 else (1 shl zoom) - west + east + 1
            total += xCount.toLong() * (south - north + 1).coerceAtLeast(1)
        }
        return total
    }

    private fun startOfflineRegionDownload(bounds: LatLngBounds, name: String, estimatedTiles: Long, minZoom: Double = 8.0, maxZoom: Double = 14.0) {
        val styleUrl = if (darkTheme) DARK_STYLE else LIGHT_STYLE
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, minZoom, maxZoom, resources.displayMetrics.density, true
        )
        val metadata = JSONObject().apply {
            put("name", name)
            put("createdAt", System.currentTimeMillis())
            put("estimatedTiles", estimatedTiles)
        }.toString().toByteArray(Charsets.UTF_8)
        val manager = OfflineManager.getInstance(this)
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val sizeMb = status.completedResourceSize / (1024L * 1024L)
                        if (status.completedTileCount > 15000L) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            statusText.text = "Офлайн-карта остановлена: более 15 000 плиток"
                            toast("Одна загрузка ограничена 15 000 плитками. Уменьшите область и сохраните её частями.")
                            return
                        }
                        if (status.completedResourceSize > 200L * 1024L * 1024L) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            statusText.text = "Офлайн-карта остановлена: достигнут лимит 200 МБ"
                            toast("Загрузка остановлена на лимите 200 МБ. Уменьшите область и загрузите её частями.")
                            return
                        }
                        if (status.isComplete) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            statusText.text = "Офлайн-карта сохранена"
                            toast("Офлайн-карта полностью загружена · $sizeMb МБ")
                        } else {
                            val progress = if (status.requiredResourceCount > 0L)
                                (status.completedResourceCount * 100L / status.requiredResourceCount).coerceIn(0L, 99L)
                            else 0L
                            statusText.text = "Офлайн-карта · $progress% · $sizeMb МБ"
                        }
                    }

                    override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                        statusText.text = "Ошибка загрузки офлайн-карты"
                        toast("Ошибка загрузки карты: ${error.message}")
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        toast("Достигнут лимит картографических плиток ($limit). Уменьшите область.")
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                toast("Загрузка офлайн-карты началась")
            }

            override fun onError(error: String) {
                toast("Не удалось создать офлайн-карту: $error")
            }
        })
    }

    private fun showOfflineRegionList() {
        OfflineManager.getInstance(this).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions ?: emptyArray()
                if (regions.isEmpty()) {
                    infoDialog("Мои офлайн-карты", "Пока нет сохранённых областей. Чтобы скачать карту, сначала приблизьте нужный район на экране.")
                    return
                }
                val labels = regions.map { region ->
                    val metadata = try { JSONObject(String(region.metadata, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                    val name = metadata.optString("name", "Область карты")
                    "$name · ID ${region.id}"
                }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Сохранённые области")
                    .setItems(labels) { _, index -> showOfflineRegionActions(regions[index]) }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }

            override fun onError(error: String) {
                toast("Не удалось прочитать список карт: $error")
            }
        })
    }

    private fun showOfflineRegionActions(region: OfflineRegion) {
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status == null) {
                    toast("Статус офлайн-карты недоступен")
                    return
                }
                val metadata = try { JSONObject(String(region.metadata, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                val name = metadata.optString("name", "Область карты")
                val state = if (status.downloadState == OfflineRegion.STATE_ACTIVE) "Загрузка активна" else if (status.isComplete) "Загрузка завершена" else "Приостановлена"
                val percent = if (status.requiredResourceCount > 0L)
                    (status.completedResourceCount * 100L / status.requiredResourceCount).coerceIn(0L, 100L) else 0L
                val sizeMb = status.completedResourceSize / (1024L * 1024L)
                val actions = if (status.downloadState == OfflineRegion.STATE_ACTIVE)
                    arrayOf("Приостановить", "Удалить", "Закрыть")
                else arrayOf(if (status.isComplete) "Проверить карту" else "Продолжить загрузку", "Удалить", "Закрыть")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(name)
                    .setMessage("$state · $percent%\nЗагружено плиток: ${status.completedTileCount}\nРазмер: $sizeMb МБ")
                    .setItems(actions) { _, which ->
                        when (which) {
                            0 -> {
                                if (status.downloadState == OfflineRegion.STATE_ACTIVE) {
                                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    toast("Загрузка приостановлена")
                                } else if (!status.isComplete) {
                                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                                        override fun onStatusChanged(next: OfflineRegionStatus) {
                                            val mb = next.completedResourceSize / (1024L * 1024L)
                                            if (next.completedTileCount > 15000L) {
                                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                                toast("Загрузка остановлена на лимите 15 000 плиток")
                                            } else if (next.completedResourceSize > 200L * 1024L * 1024L) {
                                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                                toast("Загрузка остановлена на лимите 200 МБ")
                                            } else if (next.isComplete) {
                                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                                toast("Офлайн-карта готова · $mb МБ")
                                            } else {
                                                statusText.text = "Офлайн-карта · ${next.completedTileCount} плиток · $mb МБ"
                                            }
                                        }
                                        override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                                            toast("Ошибка загрузки: ${error.message}")
                                        }
                                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                            toast("Достигнут лимит плиток: $limit")
                                        }
                                    })
                                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                    toast("Загрузка продолжена")
                                } else {
                                    toast("Карта сохранена и будет доступна без сети в пределах загруженной области")
                                }
                            }
                            1 -> AlertDialog.Builder(this@MainActivity)
                                .setTitle("Удалить офлайн-карту?")
                                .setMessage("Сохранённые данные этой области будут удалены с телефона.")
                                .setPositiveButton("Удалить") { _, _ ->
                                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                        override fun onDelete() { toast("Офлайн-карта удалена") }
                                        override fun onError(error: String) { toast("Не удалось удалить карту: $error") }
                                    })
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        }
                    }
                    .setNegativeButton("Назад", null)
                    .show()
            }

            override fun onError(error: String?) {
                toast("Не удалось проверить карту: ${error ?: "неизвестная ошибка"}")
            }
        })
    }

    private fun showMapModeDialog() {
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val mode = prefs.getString("search_mode", "auto") ?: "auto"
        val selected = when (mode) { "online" -> 0; "offline" -> 2; else -> 1 }
        AlertDialog.Builder(this)
            .setTitle("Режим карты, поиска и маршрута")
            .setSingleChoiceItems(arrayOf("Онлайн · поиск и маршруты", "Автоматически · онлайн с локальным резервом", "Офлайн · только сохранённые данные"), selected) { dialog, which ->
                val chosen = when (which) { 0 -> "online"; 2 -> "offline"; else -> "auto" }
                prefs.edit().putString("search_mode", chosen).apply()
                statusText.text = when(chosen) {
                    "offline" -> "Поиск · офлайн (локальная база)"
                    "online" -> "Поиск · онлайн"
                    else -> "Поиск · автоматический"
                }
                dialog.dismiss()
                hideDrawer()
                if (chosen == "offline") {
                    val missing = mutableListOf<String>()
                    if (OfflinePlaceIndex(this).count() == 0) missing.add("импортируйте GeoJSON с местами")
                    if (useOfflineRoadGraph { it.edgeCount() } == 0) missing.add("загрузите дорожные данные для офлайн-маршрута")
                    if (missing.isNotEmpty()) infoDialog("Офлайн-данные не полностью подготовлены", "Чтобы пользоваться всеми офлайн-функциями, сначала ${missing.joinToString(" и ")}.")
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showMapTypeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Вид карты")
            .setItems(arrayOf("2D", "3D", "Спутник (скоро)")) { _, which ->
                when (which) {
                    0 -> setMapTilt(0.0, "Карта · 2D")
                    1 -> setMapTilt(45.0, "Карта · 3D")
                    else -> infoDialog("Спутниковая карта", "Спутниковую подложку подключим отдельно.")
                }
                hideDrawer()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun setMapTilt(tilt: Double, label: String) {
        val current = map?.cameraPosition ?: return
        val camera = CameraPosition.Builder()
            .target(current.target ?: LatLng(55.751244, 37.618423))
            .zoom(current.zoom)
            .bearing(current.bearing)
            .tilt(tilt)
            .build()
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(camera))
        statusText.text = label
    }

    private fun infoDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }

    private fun loadMapStyle() {
        val styleUrl = if (darkTheme) DARK_STYLE else LIGHT_STYLE
        map?.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            addLocationMarker(style)
            addRadarMarkers(style)
            addSearchResultMarker(style)
            addRouteLine(style)
            if (navigationActive) lastNavigationLocation?.let { addNavigationMarker(style, it) }
        }
        statusText.text = if (darkTheme) "Тёмная карта · онлайн" else "Карта · онлайн"
    }

    private fun rebuildDrawer() {
        val updated = buildDrawer()
        drawerPanel.removeAllViews()
        while (updated.childCount > 0) {
            val child = updated.getChildAt(0)
            updated.removeViewAt(0)
            drawerPanel.addView(child)
        }
    }

    /** Draws fixed speed cameras from the locally cached OSM radar database. */
    private fun addRadarMarkers(style: Style) {
        val source = GeoJsonSource(RADAR_SOURCE_ID, radarFeatureCollection())
        style.addSource(source)
        style.addLayer(CircleLayer(RADAR_LAYER_ID, RADAR_SOURCE_ID).withProperties(
            circleRadius(7f),
            circleColor("#D32F2F"),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(2.5f)
        ))
        style.addLayer(SymbolLayer(RADAR_LABEL_LAYER_ID, RADAR_SOURCE_ID).withProperties(
            textField("{radar_label}"),
            textSize(10f),
            textColor("#FFFFFF"),
            textHaloColor("#8B0000"),
            textHaloWidth(1.5f),
            textAllowOverlap(false),
            textIgnorePlacement(false)
        ))
        radarSource = source
    }

    /** Refreshes markers after GPS movement or a successful background database update. */
    private fun refreshRadarMarkers() {
        radarSource?.setGeoJson(radarFeatureCollection())
    }

    private fun radarFeatureCollection(): org.maplibre.geojson.FeatureCollection {
        if (!radarEnabled) return org.maplibre.geojson.FeatureCollection.fromFeatures(emptyArray())
        val raw = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
            .getString("radar_cache", null) ?: return org.maplibre.geojson.FeatureCollection.fromFeatures(emptyArray())
        val features = mutableListOf<Feature>()
        try {
            val cameras = org.json.JSONArray(raw)
            for (i in 0 until cameras.length()) {
                val camera = cameras.optJSONObject(i) ?: continue
                val lat = camera.optDouble("lat", Double.NaN)
                val lon = camera.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
                feature.addStringProperty("id", camera.optString("id"))
                val speed = camera.optString("maxspeed").trim()
                if (speed.isNotBlank()) {
                    feature.addStringProperty("maxspeed", speed)
                    val label = when {
                        speed.matches(Regex("\\d+(?:\\.\\d+)?")) -> "$speed км/ч"
                        speed.matches(Regex("\\d+(?:\\.\\d+)? ?(?:km/h|mph)", RegexOption.IGNORE_CASE)) -> speed
                        else -> ""
                    }
                    if (label.isNotBlank()) feature.addStringProperty("radar_label", label)
                }
                features.add(feature)
            }
        } catch (_: Exception) {
            // Keep the map usable if the cached radar JSON is damaged.
        }
        return org.maplibre.geojson.FeatureCollection.fromFeatures(features)
    }

    /** Adds a visible blue position marker and restores it after every style change. */
    private fun addLocationMarker(style: Style) {
        val source = GeoJsonSource(LOCATION_SOURCE_ID)
        lastKnownPoint?.let { source.setGeoJson(Feature.fromGeometry(it)) }
        style.addSource(source)
        style.addLayer(CircleLayer(LOCATION_HALO_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
            circleRadius(11f), circleColor("#FFFFFF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
        ))
        style.addLayer(CircleLayer(LOCATION_DOT_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
            circleRadius(7f), circleColor("#1677FF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
        ))
        locationSource = source
    }

    private fun showLocationMarker(latitude: Double, longitude: Double) {
        val point = Point.fromLngLat(longitude, latitude)
        lastKnownPoint = point
        locationSource?.setGeoJson(Feature.fromGeometry(point))
    }

    /** Draws a separate blue marker for the object selected in search results. */
    private fun addSearchResultMarker(style: Style) {
        val source = GeoJsonSource(SEARCH_RESULT_SOURCE_ID)
        selectedSearchPoint?.let { source.setGeoJson(Feature.fromGeometry(it)) }
        style.addSource(source)
        style.addLayer(CircleLayer(SEARCH_RESULT_HALO_LAYER_ID, SEARCH_RESULT_SOURCE_ID).withProperties(
            circleRadius(14f), circleColor("#FFFFFF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
        ))
        style.addLayer(CircleLayer(SEARCH_RESULT_DOT_LAYER_ID, SEARCH_RESULT_SOURCE_ID).withProperties(
            circleRadius(9f), circleColor("#006BFF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)
        ))
        searchResultSource = source
    }

    private fun showSearchResultMarker(latitude: Double, longitude: Double) {
        val point = Point.fromLngLat(longitude, latitude)
        selectedSearchPoint = point
        searchResultSource?.setGeoJson(Feature.fromGeometry(point))
    }

    private fun changeZoom(delta: Double) {
        val currentMap = map ?: return
        val currentZoom = currentMap.cameraPosition.zoom
        val targetZoom = (currentZoom + delta).coerceIn(2.0, 20.0)
        currentMap.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.zoomTo(targetZoom)
        )
    }

    private fun toggleMapTheme(button: TextView) {
        darkTheme = !darkTheme
        button.text = if (darkTheme) "☀" else "☾"
        loadMapStyle()
    }

    private fun searchPlace() {
        val query = searchInput.text.toString().trim()
        if (query.length < 2) { toast("Введите название места или адрес"); return }
        statusText.text = "Ищем: $query…"
        screenScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { searchWithFallback(query) }
                if (results.isEmpty()) {
                    statusText.text = "Ничего не найдено"
                    toast("По запросу ничего не найдено")
                } else {
                    statusText.text = "Найдено: ${results.size}"
                    val origin = currentDeviceLocation()
                    val sortedResults = if (origin != null) {
                        results.sortedBy { item ->
                            distanceBetween(origin.latitude, origin.longitude, item.lat, item.lon)
                        }
                    } else results
                    val displayLabels = sortedResults.map { item ->
                        val distanceLabel = origin?.let {
                            val meters = distanceBetween(it.latitude, it.longitude, item.lat, item.lon)
                            if (meters < 1000.0) "${meters.roundToInt()} м"
                            else "${(meters / 1000.0).roundToInt()} км"
                        }
                        listOfNotNull(item.shortLabel, distanceLabel).joinToString(" · ")
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Результаты поиска")
                        .setItems(displayLabels.toTypedArray()) { _, index ->
                            val item = sortedResults[index]
                            showSearchResultMarker(item.lat, item.lon)
                            map?.animateCamera(
                                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                                    LatLng(item.lat, item.lon), 15.0
                                )
                            )
                            statusText.text = item.label
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            } catch (e: Exception) {
                statusText.text = "Серверы поиска недоступны"
                toast("Поиск не удался. Проверьте интернет и повторите попытку")
            }
        }
    }

    /** Try Photon first, then use Nominatim if Photon is unreachable or returns an error. */
    private fun searchWithFallback(query: String): List<PlaceResult> {
        val mode = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
            .getString("search_mode", "auto") ?: "auto"
        // Explicit offline mode never contacts remote search services.
        if (mode == "offline") return localPlaceSearch(query)
        try {
            val photon = photonSearch(query)
            if (photon.isNotEmpty()) return photon
        } catch (_: Exception) { }
        if (mode == "online") return emptyList()
        try {
            val nominatim = nominatimSearch(query)
            if (nominatim.isNotEmpty()) return nominatim
        } catch (_: Exception) { }
        return localPlaceSearch(query)
    }

    private fun localPlaceSearch(query: String): List<PlaceResult> =
        OfflinePlaceIndex(this).search(query).map { place ->
            val category = place.category.takeIf { it.isNotBlank() }
            val city = place.city.takeIf { it.isNotBlank() }
            val short = listOfNotNull(place.name, category, city).distinct().joinToString(" · ")
            val label = listOfNotNull(place.name, category, city).distinct().joinToString(", ")
            PlaceResult(label, place.latitude, place.longitude, short)
        }

    private fun photonSearch(query: String): List<PlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://photon.komoot.io/api/?q=$encoded&lang=ru&limit=6")
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { index ->
            val feature = features.optJSONObject(index) ?: return@mapNotNull null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            if (coords.length() < 2) return@mapNotNull null
            val props = feature.optJSONObject("properties") ?: JSONObject()
            val label = listOf("name", "street", "housenumber", "city", "district", "state", "country")
                .mapNotNull { key -> props.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .distinct().joinToString(", ")
            if (label.isBlank()) return@mapNotNull null
            val name = props.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: label.substringBefore(",")
            val kind = listOf(props.optString("type"), props.optString("osm_value"), props.optString("category"))
                .firstOrNull { it.isNotBlank() && it != "null" }
            val city = listOf("city", "town", "village", "county")
                .mapNotNull { key -> props.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .firstOrNull { !name.equals(it, true) }
            val short = listOfNotNull(name, kind, city).distinct().joinToString(" · ")
            PlaceResult(label, coords.getDouble(1), coords.getDouble(0), short)
        }
    }

    private fun nominatimSearch(query: String): List<PlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&addressdetails=1&limit=6&accept-language=ru")
        val results = org.json.JSONArray(body)
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val label = item.optString("display_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val parts = label.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val name = parts.firstOrNull() ?: label
            val kind = item.optString("type").takeIf { it.isNotBlank() && it != "null" }
            val address = item.optJSONObject("address") ?: JSONObject()
            val city = listOf("city", "town", "village", "municipality")
                .mapNotNull { key -> address.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .firstOrNull { !it.equals(name, true) }
            PlaceResult(label, lat, lon, listOfNotNull(name, kind, city).distinct().joinToString(" · "))
        }
    }

    private fun httpGet(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "FreeNavigator/0.6.0 (Android navigation app)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "ru")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${response.take(160)}")
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun startRoutePlanning() {
        val originInput = EditText(this).apply {
            hint = "Откуда? (по умолчанию — моё местоположение)"
            setSingleLine(true)
            setText("Моё местоположение")
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val destinationInput = EditText(this).apply {
            hint = "Куда проложить маршрут?"
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(originInput, LinearLayout.LayoutParams(-1, -2))
            addView(destinationInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        AlertDialog.Builder(this)
            .setTitle("Проложить маршрут")
            .setMessage("Укажите начальную и конечную точки. Начальная точка по умолчанию определяется по GPS.")
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Построить", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val origin = originInput.text.toString().trim()
                        val destination = destinationInput.text.toString().trim()
                        if (destination.length < 2) {
                            destinationInput.error = "Введите адрес или место"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        calculateAndShowRoute(origin, destination)
                    }
                }
                dialog.show()
            }
    }

    private fun calculateAndShowRoute(originQuery: String, destinationQuery: String) {
        val useGps = originQuery.isBlank() || originQuery.equals("Моё местоположение", ignoreCase = true)
        val gpsOrigin = if (useGps) currentDeviceLocation() else null
        if (useGps && gpsOrigin == null) {
            toast("Не удалось получить координаты. Включите GPS или укажите начальную точку вручную")
            return
        }
        statusText.text = "Ищем точки маршрута…"
        screenScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val origin = gpsOrigin ?: searchWithFallback(originQuery).firstOrNull()?.let {
                        Location("search").apply { latitude = it.lat; longitude = it.lon }
                    } ?: throw IllegalStateException("Начальная точка не найдена")
                    val destination = searchWithFallback(destinationQuery).firstOrNull()
                        ?: throw IllegalStateException("Место назначения не найдено")
                    val mode = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
                        .getString("search_mode", "auto") ?: "auto"
                    val routes = when (mode) {
                        "offline" -> listOf(calculateOfflineRoute(origin.latitude, origin.longitude, destination.lat, destination.lon))
                        "online" -> osrmRoutes(origin.latitude, origin.longitude, destination.lat, destination.lon)
                        else -> try {
                            osrmRoutes(origin.latitude, origin.longitude, destination.lat, destination.lon)
                        } catch (onlineError: Exception) {
                            try {
                                listOf(calculateOfflineRoute(origin.latitude, origin.longitude, destination.lat, destination.lon))
                            } catch (_: Exception) { throw onlineError }
                        }
                    }
                    Pair(destination, routes)
                }
                val destination = result.first
                val routes = result.second
                if (routes.size > 1) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Варианты маршрута")
                        .setItems(routes.mapIndexed { index, route ->
                            "${index + 1}. ${String.format(Locale.getDefault(), "%.1f", route.distanceMeters / 1000.0)} км · ${route.durationSeconds / 60} мин"
                        }.toTypedArray()) { _, index -> showRoutePreview(destination, routes[index]) }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    showRoutePreview(destination, routes.first())
                }
            } catch (e: Exception) {
                statusText.text = "Не удалось построить маршрут"
                toast("Маршрут не построен: ${e.message ?: "проверьте интернет"}")
            }
        }
    }

    private fun showRoutePreview(destination: PlaceResult, route: RouteData) {
        routeLine = LineString.fromLngLats(route.points)
        map?.style?.let { addRouteLine(it) }
        val boundsBuilder = LatLngBounds.Builder()
        route.points.forEach { boundsBuilder.include(LatLng(it.latitude(), it.longitude())) }
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), dp(56)))
        val km = route.distanceMeters / 1000.0
        val minutes = route.durationSeconds / 60
        statusText.text = "${if (route.offline) "Офлайн-маршрут" else "Маршрут"}: ${String.format(Locale.getDefault(), "%.1f", km)} км · около $minutes мин"
        speak("Маршрут готов. Расстояние ${String.format(Locale.getDefault(), "%.1f", km)} километра. В пути около $minutes минут")
        AlertDialog.Builder(this)
            .setTitle("Маршрут")
            .setMessage("До: ${destination.shortLabel}\nРасстояние: ${String.format(Locale.getDefault(), "%.1f", km)} км\nВ пути: около $minutes мин\nИсточник: ${if (route.offline) "сохранённая дорожная сеть (офлайн)" else "онлайн"}\n\nПолный маршрут будет показан на карте 5 секунд перед включением навигации.")
            .setPositiveButton("Начать маршрут") { _, _ ->
                screenScope.launch {
                    toast("Просмотр полного маршрута — 5 секунд")
                    kotlinx.coroutines.delay(5000)
                    beginNavigation(route)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun beginNavigation(route: RouteData) {
        val points = route.points
        val distanceMeters = route.distanceMeters
        val durationSeconds = route.durationSeconds
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            pendingNavigation = route
            toast("Разрешите доступ к местоположению для начала ведения")
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        navigationPoints = points
        navigationOffline = route.offline
        rerouteInProgress = false
        offRouteFixes = 0
        lastRerouteAt = 0L
        navigationDistanceMeters = distanceMeters
        navigationDurationSeconds = durationSeconds
        navigationSteps = route.steps
        nextVoiceStepIndex = 0
        currentRouteIndex = 0
        navigationActive = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastAcceptedFix = null
        lastAcceptedFixTime = 0L
        navigationStartedAt = System.currentTimeMillis()
        arrivalAnnounced = false
        lastNavigationLocation = null
        startForegroundGuidance(route)
        routeProgress?.progress = 0
        val startLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(navigationStartedAt))
        routeStartTime?.text = startLabel
        routeArrivalTime?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(navigationStartedAt + durationSeconds * 1000L))
        routeRemainingText?.text = "До конца маршрута: ${String.format(Locale.getDefault(), "%.1f", distanceMeters / 1000.0)} км."
        speak("Маршрут начат")
        navigationPanel?.visibility = View.VISIBLE
        maneuverHud?.visibility = View.VISIBLE
        maneuverHudIcon?.text = "↑"
        maneuverHudText?.text = "Определяем следующий манёвр…"
        bottomControlsView?.visibility = View.GONE
        navigationInfo?.text = "Получаем координаты GPS…"
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { updateNavigation(location) }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    if (navigationActive) navigationInfo?.text = "Ожидаем доступные координаты"
                }
            }
            navigationLocationListener = listener
            var requested = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    if (provider == LocationManager.PASSIVE_PROVIDER || manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 1000L, 2f, listener, mainLooper)
                        requested = true
                    }
                } catch (_: Exception) {
                    // Continue registering the remaining providers.
                }
            }
            if (!requested) {
                stopNavigation()
                toast("Включите GPS или сетевое местоположение")
                return
            }
            currentDeviceLocation()?.let { updateNavigation(it) }
        } catch (_: SecurityException) {
            stopNavigation()
            toast("Нет разрешения на местоположение")
        } catch (e: Exception) {
            stopNavigation()
            toast("Не удалось запустить GPS-ведение: ${e.message ?: "ошибка GPS"}")
        }
    }


    private fun startForegroundGuidance(route: RouteData) {
        val destination = route.points.lastOrNull() ?: return
        val intent = Intent(this, NavigationForegroundService::class.java).apply {
            action = NavigationForegroundService.ACTION_START
            putExtra(NavigationForegroundService.EXTRA_DEST_LAT, destination.latitude())
            putExtra(NavigationForegroundService.EXTRA_DEST_LON, destination.longitude())
            putExtra(NavigationForegroundService.EXTRA_STEP_LATS, route.steps.map { it.point.latitude() }.toDoubleArray())
            putExtra(NavigationForegroundService.EXTRA_STEP_LONS, route.steps.map { it.point.longitude() }.toDoubleArray())
            putExtra(NavigationForegroundService.EXTRA_INSTRUCTIONS, route.steps.map { it.instruction }.toTypedArray())
        }
        try {
            // The service reports true only after Russian TTS has initialized.
            // Do not suppress the Activity's voice fallback merely because the service started.
            foregroundGuidanceStarted = false
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            foregroundGuidanceStarted = false
            toast("Не удалось включить фоновое ведение: ${e.message ?: "ошибка службы"}")
        }
    }

    private fun updateNavigation(location: Location) {
        if (!navigationActive) return
        if (location.hasAccuracy() && location.accuracy > 60f) return
        if (System.currentTimeMillis() - location.time > 15000L) return
        val previousFix = lastAcceptedFix
        if (previousFix != null) {
            // Ignore duplicate/out-of-order fixes from multiple Android providers.
            if (location.time <= lastAcceptedFixTime) return
            val deltaSeconds = (location.time - lastAcceptedFixTime) / 1000.0
            val jumpMeters = distanceBetween(previousFix.latitude, previousFix.longitude, location.latitude, location.longitude)
            val previousAccuracy = if (previousFix.hasAccuracy()) previousFix.accuracy.coerceAtMost(35f) else 25f
            val currentAccuracy = if (location.hasAccuracy()) location.accuracy.coerceAtMost(35f) else 25f
            val plausibleMeters = (deltaSeconds * 55.0).coerceAtLeast(25.0) + previousAccuracy + currentAccuracy
            if (jumpMeters > plausibleMeters) return
        }
        lastAcceptedFix = Location(location)
        lastAcceptedFixTime = location.time
        if (!foregroundGuidanceStarted) checkRadarProximity(location)
        val projection = nearestRouteProjection(location.latitude, location.longitude)
        currentRouteIndex = projection.segmentIndex
        maybeReroute(location, projection.distanceMeters)
        map?.style?.let { addNavigationMarker(it, location) }
        announceNextManeuver(location)
        var remaining = distanceBetween(location.latitude, location.longitude, projection.point.latitude(), projection.point.longitude())
        val segmentEnd = navigationPoints[projection.segmentIndex + 1]
        remaining += distanceBetween(projection.point.latitude(), projection.point.longitude(), segmentEnd.latitude(), segmentEnd.longitude())
        for (i in projection.segmentIndex + 1 until navigationPoints.lastIndex) {
            val a = navigationPoints[i]
            val b = navigationPoints[i + 1]
            remaining += distanceBetween(a.latitude(), a.longitude(), b.latitude(), b.longitude())
        }
        updateManeuverDisplay(location)
        val total = navigationDistanceMeters.coerceAtLeast(1.0)
        val fraction = (remaining / total).coerceIn(0.0, 1.0)
        routeProgress?.progress = ((1.0 - fraction) * 1000).toInt().coerceIn(0, 1000)
        val minutes = (navigationDurationSeconds * fraction / 60.0).toInt().coerceAtLeast(1)
        navigationInfo?.text = "В пути · около $minutes мин"
        routeRemainingText?.text = "До конца маршрута: ${String.format(Locale.getDefault(), "%.1f", (remaining / 1000.0).coerceAtLeast(0.0))} км."
        val arrival = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + minutes * 60_000L))
        routeArrivalTime?.text = arrival
        val bearing = if (location.hasBearing() && location.speed > 0.5f) location.bearing.toDouble()
            else lastNavigationLocation?.let { it.bearingTo(location).toDouble() } ?: map?.cameraPosition?.bearing ?: 0.0
        val bottomPadding = (navigationPanel?.height?.takeIf { it > 0 } ?: dp(116)) + dp(28)
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(17.0)
                .bearing(bearing)
                .tilt(45.0)
                // Shift the map's logical center above the bottom route panel so
                // the live vehicle arrow remains visible in the usable map area.
                .padding(0.0, 0.0, 0.0, bottomPadding.toDouble())
                .build()
        ))
        lastNavigationLocation = Location(location)
        if (remaining < 25.0) {
            navigationInfo?.text = "Вы прибыли к месту назначения"
            routeRemainingText?.text = "До конца маршрута: 0,0 км."
            if (!foregroundGuidanceStarted && !arrivalAnnounced) { speak("Вы прибыли к месту назначения"); arrivalAnnounced = true }
        }
    }

    private fun updateManeuverDisplay(location: Location) {
        val step = navigationSteps.getOrNull(nextVoiceStepIndex)
        if (step == null) {
            maneuverIcon?.text = "✓"
            maneuverInfo?.text = "Маршрут завершён"
            maneuverHudIcon?.text = "✓"
            maneuverHudText?.text = "Маршрут завершён"
            return
        }
        val distance = distanceBetween(
            location.latitude, location.longitude,
            step.point.latitude(), step.point.longitude()
        )
        if (foregroundGuidanceStarted && distance <= 35.0) {
            nextVoiceStepIndex++
            updateManeuverDisplay(location)
            return
        }
        val rounded = if (distance < 100.0) distance.toInt().coerceAtLeast(0)
            else ((distance / 50.0).roundToInt() * 50)
        val lower = step.instruction.lowercase(Locale("ru", "RU"))
        maneuverIcon?.text = when {
            "разворот" in lower -> "↶"
            "налево" in lower || "лев" in lower -> "←"
            "направо" in lower || "прав" in lower -> "→"
            "круговом" in lower || "кольц" in lower -> "⟳"
            "съезд" in lower -> "↗"
            else -> "↑"
        }
        val maneuverText = if (distance <= 35.0) {
            "Сейчас · ${step.instruction}"
        } else {
            "Через $rounded м · ${step.instruction}"
        }
        maneuverInfo?.text = maneuverText
        maneuverHudIcon?.text = maneuverIcon?.text ?: "↑"
        maneuverHudText?.text = maneuverText
    }

    /** Rebuilds the route after several reliable GPS fixes show that the driver left it. */
    private fun maybeReroute(location: Location, distanceFromRoute: Double) {
        if (!navigationActive || rerouteInProgress || navigationPoints.size < 2) return
        if (location.hasAccuracy() && location.accuracy > 45f) {
            offRouteFixes = 0
            return
        }
        if (distanceFromRoute > 70.0) offRouteFixes++ else offRouteFixes = 0
        val now = System.currentTimeMillis()
        if (offRouteFixes < 3 || now - lastRerouteAt < 20_000L) return

        val destination = navigationPoints.last()
        val originLat = location.latitude
        val originLon = location.longitude
        rerouteInProgress = true
        lastRerouteAt = now
        navigationInfo?.text = "Отклонение от маршрута · перестраиваем…"
        screenScope.launch {
            try {
                val replacement = withContext(Dispatchers.IO) {
                    if (navigationOffline) calculateOfflineRoute(originLat, originLon, destination.latitude(), destination.longitude())
                    else osrmRoutes(originLat, originLon, destination.latitude(), destination.longitude()).firstOrNull()
                        ?: throw IllegalStateException("Новый путь не найден")
                }
                if (!navigationActive) return@launch
                navigationPoints = replacement.points
                navigationDistanceMeters = replacement.distanceMeters
                navigationDurationSeconds = replacement.durationSeconds
                navigationSteps = replacement.steps
                nextVoiceStepIndex = 0
                currentRouteIndex = 0
                arrivalAnnounced = false
                routeLine = LineString.fromLngLats(replacement.points)
                map?.style?.let { addRouteLine(it) }
                startForegroundGuidance(replacement)
                speak("Маршрут перестроен")
                navigationInfo?.text = "Новый маршрут построен"
            } catch (error: Exception) {
                if (navigationActive) {
                    navigationInfo?.text = "Маршрут не перестроен · следуйте к синей линии"
                }
            } finally {
                rerouteInProgress = false
                offRouteFixes = 0
            }
        }
    }


    /** Recreates the live position arrow after a map-style reload. */
    private fun addNavigationMarker(style: Style, location: Location) {
        val point = Point.fromLngLat(location.longitude, location.latitude)
        val rotation = if (location.hasBearing()) location.bearing else 0f
        val source = style.getSource(NAV_SOURCE_ID) as? GeoJsonSource
        if (source == null) {
            style.addSource(GeoJsonSource(NAV_SOURCE_ID, Feature.fromGeometry(point)))
            style.addLayer(SymbolLayer(NAV_LAYER_ID, NAV_SOURCE_ID).withProperties(
                textField("▲"), textSize(27f), textColor("#075BFF"),
                textRotate(rotation), textAllowOverlap(true), textIgnorePlacement(true)
            ))
        } else {
            source.setGeoJson(Feature.fromGeometry(point))
            (style.getLayer(NAV_LAYER_ID) as? SymbolLayer)?.setProperties(textRotate(rotation))
        }
        navigationSource = style.getSource(NAV_SOURCE_ID) as? GeoJsonSource
    }

    /** Projects the GPS fix onto the nearest forward route segment, avoiding corner false positives. */
    private fun nearestRouteProjection(lat: Double, lon: Double): RouteProjection {
        if (navigationPoints.size < 2) return RouteProjection(0, navigationPoints.first(), 0.0)
        val metersPerLat = 111320.0
        val metersPerLon = (111320.0 * kotlin.math.cos(Math.toRadians(lat))).coerceAtLeast(1.0)
        var bestIndex = currentRouteIndex.coerceIn(0, navigationPoints.lastIndex - 1)
        var bestDistance = Double.MAX_VALUE
        var bestPoint = navigationPoints[bestIndex]
        val firstIndex = (currentRouteIndex - 1).coerceAtLeast(0)
        for (index in firstIndex until navigationPoints.lastIndex) {
            val a = navigationPoints[index]
            val b = navigationPoints[index + 1]
            val ax = (a.longitude() - lon) * metersPerLon
            val ay = (a.latitude() - lat) * metersPerLat
            val bx = (b.longitude() - lon) * metersPerLon
            val by = (b.latitude() - lat) * metersPerLat
            val dx = bx - ax
            val dy = by - ay
            val denominator = dx * dx + dy * dy
            val fraction = if (denominator <= 0.0001) 0.0 else (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
            val projectedX = ax + fraction * dx
            val projectedY = ay + fraction * dy
            val distance = kotlin.math.hypot(projectedX, projectedY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
                bestPoint = Point.fromLngLat(lon + projectedX / metersPerLon, lat + projectedY / metersPerLat)
            }
        }
        return RouteProjection(bestIndex, bestPoint, bestDistance)
    }

    private fun announceNextManeuver(location: Location) {
        if (foregroundGuidanceStarted) return
        if (!speechReady || nextVoiceStepIndex >= navigationSteps.size) return
        val step = navigationSteps[nextVoiceStepIndex]
        val distance = distanceBetween(location.latitude, location.longitude, step.point.latitude(), step.point.longitude())
        if (distance <= 250.0 && !step.announced) {
            val distanceText = if (distance >= 100) "Через ${((distance / 50).toInt() * 50)} метров. " else "Сейчас. "
            speak(distanceText + step.instruction)
            step.announced = true
        }
        if (distance <= 35.0) nextVoiceStepIndex++
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun stopNavigation() {
        navigationActive = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastAcceptedFix = null
        lastAcceptedFixTime = 0L
        navigationOffline = false
        rerouteInProgress = false
        offRouteFixes = 0
        foregroundGuidanceStarted = false
        try { startService(Intent(this, NavigationForegroundService::class.java).setAction(NavigationForegroundService.ACTION_STOP)) } catch (_: Exception) { }
        pendingNavigation = null
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            navigationLocationListener?.let { manager.removeUpdates(it) }
        } catch (_: Exception) {}
        navigationLocationListener = null
        navigationPanel?.visibility = View.GONE
        map?.cameraPosition?.let { current ->
            map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(current.target)
                    .zoom(current.zoom)
                    .bearing(current.bearing)
                    .tilt(current.tilt)
                    .padding(0.0, 0.0, 0.0, 0.0)
                    .build()
            ))
        }
        maneuverHud?.visibility = View.GONE
        bottomControlsView?.visibility = View.VISIBLE
        map?.style?.let { style ->
            if (style.getLayer(NAV_LAYER_ID) != null) style.removeLayer(NAV_LAYER_ID)
            if (style.getSource(NAV_SOURCE_ID) != null) style.removeSource(NAV_SOURCE_ID)
        }
        navigationSource = null
        lastNavigationLocation = null
        navigationSteps = emptyList()
        nextVoiceStepIndex = 0
        routeProgress?.progress = 0
        routeLine = null
        map?.style?.let { style ->
            if (style.getLayer(ROUTE_LAYER_ID) != null) style.removeLayer(ROUTE_LAYER_ID)
            if (style.getSource(ROUTE_SOURCE_ID) != null) style.removeSource(ROUTE_SOURCE_ID)
        }
        statusText.text = "Маршрут завершён"
    }

    private fun currentDeviceLocation(): Location? {
        return try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider -> if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
    }

    private fun osrmRoutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<RouteData> {
        val address = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true&alternatives=true"
        val body = httpGet(address)
        val routes = JSONObject(body).optJSONArray("routes") ?: throw IllegalStateException("Сервис маршрутов не вернул путь")
        if (routes.length() == 0) throw IllegalStateException("Маршрут не найден")
        return (0 until routes.length()).mapNotNull { routeIndex ->
            val route = routes.optJSONObject(routeIndex) ?: return@mapNotNull null
            val coordinates = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val points = (0 until coordinates.length()).map { i ->
                val pair = coordinates.getJSONArray(i)
                Point.fromLngLat(pair.getDouble(0), pair.getDouble(1))
            }
            if (points.size < 2) return@mapNotNull null
            val steps = mutableListOf<RouteStep>()
            val legs = route.optJSONArray("legs")
            val leg = legs?.optJSONObject(0)
            val stepArray = leg?.optJSONArray("steps")
            if (stepArray != null) {
                for (stepIndex in 0 until stepArray.length()) {
                    val step = stepArray.optJSONObject(stepIndex) ?: continue
                    val maneuver = step.optJSONObject("maneuver") ?: continue
                    val coordinates = maneuver.optJSONArray("location") ?: continue
                    if (coordinates.length() < 2) continue
                    val point = Point.fromLngLat(coordinates.getDouble(0), coordinates.getDouble(1))
                    val type = maneuver.optString("type")
                    val modifier = maneuver.optString("modifier")
                    val road = step.optString("name").takeIf { it.isNotBlank() }
                    val action = when (type) {
                        "depart" -> "Начало движения"
                        "arrive" -> "Прибытие к месту назначения"
                        "turn", "end of road", "fork" -> when (modifier) {
                            "left" -> "Поверните налево"
                            "slight left" -> "Плавно налево"
                            "sharp left" -> "Резко налево"
                            "right" -> "Поверните направо"
                            "slight right" -> "Плавно направо"
                            "sharp right" -> "Резко направо"
                            "uturn" -> "Развернитесь"
                            else -> "Продолжайте движение"
                        }
                        "roundabout", "rotary" -> "На круговом движении"
                        "merge" -> "Перестройтесь"
                        "on ramp" -> "Съезд на полосу разгона"
                        "off ramp" -> "Съезд с дороги"
                        else -> "Продолжайте движение"
                    }
                    val instruction = if (road != null && type != "arrive" && type != "depart") "$action на $road" else action
                    if (type != "depart" && type != "arrive") steps.add(RouteStep(point, instruction, step.optDouble("distance")))
                }
            }
            RouteData(points, route.optDouble("duration").toInt(), route.optDouble("distance"), steps)
        }.ifEmpty { throw IllegalStateException("Недостаточно данных маршрута") }
    }

    private fun addRouteLine(style: Style) {
        val line = routeLine ?: return
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, Feature.fromGeometry(line)))
            style.addLayer(LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor("#1769AA"), lineWidth(5f)
            ))
        } else {
            (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(Feature.fromGeometry(line))
        }
    }

    private fun requestLocationOrCenter() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) centerOnMyLocation()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun centerOnMyLocation() {
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            val location: Location? = providers.mapNotNull { provider ->
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.maxByOrNull { it.time }
            if (location == null) {
                toast("Местоположение пока недоступно. Включите GPS и попробуйте ещё раз")
                return
            }
            showLocationMarker(location.latitude, location.longitude)
            map?.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude), 16.0
                )
            )
            statusText.text = "Ваше местоположение · синяя точка"
        } catch (_: SecurityException) {
            toast("Нет разрешения на местоположение")
        } catch (_: Exception) {
            toast("Не удалось определить местоположение")
        }
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выход из FreeNavigator")
            .setMessage("Закрыть навигатор и остановить навигацию и антирадар?")
            .setPositiveButton("Выйти") { _, _ ->
                stopNavigation()
                stopRadarMonitoring()
                try { speech?.stop() } catch (_: Exception) {}
                finishAffinity()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun radarStatusText(): String {
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val updated = prefs.getLong("radar_updated_at", 0L)
        val sourceDate = prefs.getString("radar_source_date", null)
        val date = if (updated > 0L) {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updated))
        } else if (!sourceDate.isNullOrBlank()) {
            "предустановлена · данные от $sourceDate"
        } else "ещё не загружена"
        return (if (radarEnabled) "Включён" else "Выключен") + " · база: $date"
    }

    /** Copies the bundled Russia camera snapshot into local storage on first launch. */
    private fun seedBundledRadarDatabase() {
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val existingCount = try {
            org.json.JSONArray(prefs.getString("radar_cache", "[]")).length()
        } catch (_: Exception) { 0 }
        if (existingCount > 0) return
        try {
            val cameras = assets.open("radar/ru-all.csv").use { RadarCsvParser.parse(it) }
            if (cameras.length() < 1000) throw IllegalStateException("Встроенная база камер неполная")
            prefs.edit()
                .putString("radar_cache", cameras.toString())
                .putString("radar_source_date", "21.09.2026")
                .putString("radar_last_result", "Предустановленная база России: ${cameras.length()} камер, OpenStreetMap")
                .apply()
            android.util.Log.i("FreeNavigator", "Bundled Russia radar database installed: ${cameras.length()} cameras")
        } catch (error: Exception) {
            android.util.Log.e("FreeNavigator", "Bundled radar database is unavailable", error)
        }
    }

    private fun radarDatabaseStatus(): String {
        val count = try { org.json.JSONArray(getSharedPreferences("free_navigator_settings", MODE_PRIVATE).getString("radar_cache", "[]")).length() } catch (_: Exception) { 0 }
        return if (count > 0) "Камер: $count · " + radarStatusText().substringAfter("база: ") else "База не загружена"
    }

    private fun showRadarDatabaseDialog() {
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val count = try { org.json.JSONArray(prefs.getString("radar_cache", "[]")).length() } catch (_: Exception) { 0 }
        val updated = prefs.getLong("radar_updated_at", 0L)
        val sourceDate = prefs.getString("radar_source_date", null)
        val date = if (updated > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updated))
            else if (!sourceDate.isNullOrBlank()) "предустановлена · данные от $sourceDate" else "нет"
        val error = prefs.getString("radar_last_error", null)
        val lastResult = prefs.getString("radar_last_result", null)
        val details = if (!error.isNullOrBlank()) "\nОшибка последней загрузки: $error" else if (!lastResult.isNullOrBlank()) "\nПоследний результат: $lastResult" else ""
        AlertDialog.Builder(this)
            .setTitle("База камер")
            .setMessage("Загружено камер: $count\nПоследнее обновление: $date$details\n\nЗагрузка использует ваше текущее местоположение и Wi-Fi (не тарифицируемую сеть).")
            .setPositiveButton("Загрузить / обновить") { _, _ ->
                if (!radarEnabled) {
                    radarEnabled = true
                    prefs.edit().putBoolean("radar_enabled", true).apply()
                    startRadarMonitoring()
                }
                val loc = currentDeviceLocation()
                if (loc != null) {
                    enqueueRadarRefresh(loc.latitude, loc.longitude, true)
                    toast("Загрузка базы камер начата")
                } else {
                    toast("Ожидаем GPS для загрузки базы камер")
                    requestRadarLocationAndDownload()
                }
            }
            .setNegativeButton(if (radarEnabled) "Выключить антирадар" else "Включить антирадар") { _, _ ->
                radarEnabled = !radarEnabled
                prefs.edit().putBoolean("radar_enabled", radarEnabled).apply()
                if (radarEnabled) startRadarMonitoring() else stopRadarMonitoring()
                refreshRadarMarkers()
                toast(if (radarEnabled) "Антирадар включён" else "Антирадар выключен")
            }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private fun requestRadarLocationAndDownload() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        var completed = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed) return
                completed = true
                try { manager.removeUpdates(this) } catch (_: Exception) {}
                enqueueRadarRefresh(location.latitude, location.longitude, true)
                toast("Загрузка базы камер начата")
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            var requested = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    if (provider == LocationManager.PASSIVE_PROVIDER || manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 1000L, 0f, listener, mainLooper)
                        requested = true
                    }
                } catch (_: Exception) {
                    // Try another provider if this one is unavailable.
                }
            }
            if (!requested) {
                toast("Включите геолокацию, чтобы загрузить базу камер")
                return
            }
            // Stop the one-shot location request after 30 seconds; later location
            // updates from normal monitoring can still trigger automatic downloads.
            android.os.Handler(mainLooper).postDelayed({
                if (!completed) {
                    completed = true
                    try { manager.removeUpdates(listener) } catch (_: Exception) {}
                    toast("GPS пока недоступен. Повторите загрузку, когда появится сигнал")
                }
            }, 30000L)
        } catch (_: SecurityException) {
            toast("Нет разрешения на получение местоположения")
        } catch (_: Exception) {
            try { manager.removeUpdates(listener) } catch (_: Exception) {}
            toast("Не удалось получить координаты для базы камер")
        }
    }

    private fun scheduleRadarUpdates() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val periodic = PeriodicWorkRequestBuilder<RadarUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "freenavigator-radar-daily", ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        currentDeviceLocation()?.let { enqueueRadarRefresh(it.latitude, it.longitude, false) }
    }

    private fun enqueueRadarRefresh(latitude: Double, longitude: Double, force: Boolean) {
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val updated = prefs.getLong("radar_updated_at", 0L)
        val oldLat = prefs.getFloat("radar_center_lat", 0f).toDouble()
        val oldLon = prefs.getFloat("radar_center_lon", 0f).toDouble()
        val moved = oldLat == 0.0 || distanceBetween(latitude, longitude, oldLat, oldLon) > 15000.0
        if (!force && updated > 0L && System.currentTimeMillis() - updated < 24L * 60L * 60L * 1000L && !moved) return
        val request = OneTimeWorkRequestBuilder<RadarUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(workDataOf("center_lat" to latitude, "center_lon" to longitude))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("freenavigator-radar-refresh", if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun startRadarMonitoring() {
        if (!radarEnabled || radarLocationListener != null) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    refreshRadarMarkers()
                    checkRadarProximity(location)
                    enqueueRadarRefresh(location.latitude, location.longitude, false)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            radarLocationListener = listener
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    if (provider == LocationManager.PASSIVE_PROVIDER || manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 5000L, 20f, listener, mainLooper)
                    }
                } catch (_: Exception) {
                    // Keep radar monitoring active with whichever providers are available.
                }
            }
            currentDeviceLocation()?.let { refreshRadarMarkers(); checkRadarProximity(it); enqueueRadarRefresh(it.latitude, it.longitude, false) }
        } catch (_: Exception) { radarLocationListener = null }
    }

    private fun stopRadarMonitoring() {
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            radarLocationListener?.let { manager.removeUpdates(it) }
        } catch (_: Exception) {}
        radarLocationListener = null
    }

    private fun checkRadarProximity(location: Location) {
        // Camera warnings are notification-based and must not depend on TTS readiness.
        if (foregroundGuidanceStarted || !radarEnabled) return
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val raw = prefs.getString("radar_cache", null) ?: return
        try {
            val cameras = org.json.JSONArray(raw)
            data class RadarAlert(val id: String, val distance: Double, val rearDirection: Boolean, val stage: Int)
            val eligible = mutableListOf<RadarAlert>()
            for (i in 0 until cameras.length()) {
                val camera = cameras.optJSONObject(i) ?: continue
                val id = camera.optString("id")
                if (id.isBlank()) continue
                val distance = distanceBetween(
                    location.latitude, location.longitude,
                    camera.optDouble("lat"), camera.optDouble("lon")
                )
                if (distance > 2500.0) {
                    radarWarningStages.remove(id)
                    continue
                }
                // Suppress only cameras whose mapped direction clearly faces away
                // from the vehicle's reliable GPS course.
                val cameraDirection = camera.optString("direction").toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..360.0 }
                if (cameraDirection != null && location.hasBearing() && location.speed >= 3.0f) {
                    val approachHeading = (cameraDirection + 180.0) % 360.0
                    val angleDifference = kotlin.math.abs(((location.bearing - approachHeading + 540f) % 360f) - 180f)
                    if (angleDifference > 75f) continue
                }
                val currentStage = radarWarningStages[id] ?: 0
                val nextStage = when {
                    currentStage == 0 && distance <= 60.0 -> 3
                    currentStage == 0 && distance <= 300.0 -> 2
                    currentStage == 0 && distance <= 800.0 -> 1
                    currentStage == 1 && distance <= 60.0 -> 3
                    currentStage == 1 && distance <= 300.0 -> 2
                    currentStage == 2 && distance <= 60.0 -> 3
                    else -> 0
                }
                if (nextStage > currentStage) {
                    eligible.add(RadarAlert(
                        id, distance,
                        camera.optString("direction_backward").isNotBlank(),
                        nextStage
                    ))
                }
            }
            val selected = eligible.minByOrNull { it.distance }
            if (selected != null) {
                radarWarningStages[selected.id] = selected.stage
                showRadarNotification(selected.distance, selected.rearDirection, selected.stage)
            }
        } catch (_: Exception) {}
    }

    private fun showRadarPopup(message: String) {
        radarPopupText?.let { view ->
            radarPopupHandler.removeCallbacks(hideRadarPopup)
            view.text = message
            view.visibility = View.VISIBLE
            view.bringToFront()
            radarPopupHandler.postDelayed(hideRadarPopup, 3000L)
        }
    }

    private fun playRadarTone(stage: Int) {
        try {
            radarSoundHandler.removeCallbacksAndMessages(null)
            val tone = radarToneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also {
                radarToneGenerator = it
            }
            val count = when (stage) { 1 -> 1; 2 -> 2; else -> 3 }
            val interval = when (stage) { 1 -> 700L; 2 -> 350L; else -> 180L }
            repeat(count) { index ->
                radarSoundHandler.postDelayed({
                    try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 130) } catch (_: Exception) { }
                }, index * interval)
            }
        } catch (_: Exception) { }
    }

    private fun showRadarNotification(distanceMeters: Double, rearDirection: Boolean, stage: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "radar_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "Предупреждения о камерах",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Звуковые предупреждения о приближении к камерам контроля скорости"
                enableVibration(true)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(channel)
        }
        val distanceText = when {
            distanceMeters < 100.0 -> "Рядом"
            else -> "Через ${((distanceMeters / 50.0).roundToInt() * 50).coerceAtLeast(50)} м"
        }
        val directionText = if (rearDirection) " · контроль в спину" else ""
        showRadarPopup("$distanceText$directionText")
        playRadarTone(stage)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Камера контроля скорости")
            .setContentText("$distanceText$directionText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$distanceText$directionText"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // A new notification ID for each distance stage makes every approach
            // warning audible; remove the previous stage to avoid stale banners.
            manager.cancel(2002)
            manager.cancel(2003)
            manager.cancel(2004)
            manager.notify(2001 + stage, notification)
        }
    }

    private fun speak(message: String) {
        if (speechReady) speech?.speak(message, TextToSpeech.QUEUE_ADD, null, "fn-${System.currentTimeMillis()}")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); mapView.onStart(); startRadarMonitoring() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { stopRadarMonitoring(); mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        if (navigationActive) {
            try {
                val manager = getSystemService(LOCATION_SERVICE) as LocationManager
                navigationLocationListener?.let { manager.removeUpdates(it) }
            } catch (_: Exception) { }
            navigationLocationListener = null
        } else stopNavigation()
        try { unregisterReceiver(guidanceStatusReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(radarAlertReceiver) } catch (_: Exception) { }
        radarPopupHandler.removeCallbacksAndMessages(null)
        radarSoundHandler.removeCallbacksAndMessages(null)
        try { radarToneGenerator?.release() } catch (_: Exception) { }
        radarToneGenerator = null
        speech?.stop(); speech?.shutdown(); screenScope.cancel(); mapView.onDestroy(); super.onDestroy()
    }

    private data class PlaceResult(val label: String, val lat: Double, val lon: Double, val shortLabel: String = label)
    private data class RouteStep(val point: Point, val instruction: String, val distanceMeters: Double, var announced: Boolean = false)
    private data class RouteData(val points: List<Point>, val durationSeconds: Int, val distanceMeters: Double, val steps: List<RouteStep>, val offline: Boolean = false)
    private data class RouteProjection(val segmentIndex: Int, val point: Point, val distanceMeters: Double)

    companion object {
        private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
        private const val LOCATION_SOURCE_ID = "free-navigator-location-source"
        private const val RADAR_SOURCE_ID = "free-navigator-radar-source"
        private const val RADAR_LAYER_ID = "free-navigator-radar-layer"
        private const val RADAR_LABEL_LAYER_ID = "free-navigator-radar-label-layer"
        private const val LOCATION_HALO_LAYER_ID = "free-navigator-location-halo"
        private const val LOCATION_DOT_LAYER_ID = "free-navigator-location-dot"
        private const val SEARCH_RESULT_SOURCE_ID = "free-navigator-search-result-source"
        private const val SEARCH_RESULT_HALO_LAYER_ID = "free-navigator-search-result-halo"
        private const val SEARCH_RESULT_DOT_LAYER_ID = "free-navigator-search-result-dot"
        private const val ROUTE_SOURCE_ID = "free-navigator-route-source"
        private const val ROUTE_LAYER_ID = "free-navigator-route-layer"
        private const val NAV_SOURCE_ID = "free-navigator-navigation-source"
        private const val NAV_LAYER_ID = "free-navigator-navigation-layer"
    }
}
