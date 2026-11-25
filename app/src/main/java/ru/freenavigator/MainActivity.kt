package ru.freenavigator

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ProgressBar
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var radarEnabled = true
    private var locationSource: GeoJsonSource? = null
    private var lastKnownPoint: Point? = null
    private var searchResultSource: GeoJsonSource? = null
    private var selectedSearchPoint: Point? = null
    private var routeLine: LineString? = null
    private var navigationActive = false
    private var foregroundGuidanceStarted = false
    private var navigationLocationListener: LocationListener? = null
    private var navigationSource: GeoJsonSource? = null
    private var navigationPoints: List<Point> = emptyList()
    private var navigationDistanceMeters = 0.0
    private var navigationDurationSeconds = 0
    private var navigationSteps: List<RouteStep> = emptyList()
    private var nextVoiceStepIndex = 0
    private var currentRouteIndex = 0
    private var navigationInfo: TextView? = null
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
    private val announcedRadarIds = mutableSetOf<String>()
    private var lastNavigationLocation: Location? = null
    private var pendingNavigation: RouteData? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)
        super.onCreate(savedInstanceState)
        radarEnabled = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
            .getBoolean("radar_enabled", true)
        speech = TextToSpeech(this) { result ->
            speechReady = result == TextToSpeech.SUCCESS
            if (speechReady) speech?.language = Locale("ru", "RU")
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
        rightButtons.addView(locateButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        rightButtons.addView(themeButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
        rightButtons.addView(zoomInButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
        rightButtons.addView(zoomOutButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
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
            text = "До конца маршрута: — км"
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
        panel.addView(heading)
        panel.addView(subheading)
        drawerDivider(panel)
        drawerItem(panel, "⌖", "Карта и режим", "Онлайн / офлайн") { showMapModeDialog() }
        drawerItem(panel, "⇩", "Загрузка офлайн-карт", "Сохранение областей на телефон") {
            infoDialog("Офлайн-карты", "Загрузка и хранение карт появятся в следующем этапе разработки.")
        }
        drawerItem(panel, "◇", "Вид карты", "2D / 3D / спутник") { showMapTypeDialog() }
        drawerDivider(panel)
        drawerItem(panel, "⌕", "Поиск", "Найти адрес или место") {
            hideDrawer()
            toggleSearch()
        }
        drawerItem(panel, "➤", "Проложить маршрут", "От моего местоположения до адреса") {
            hideDrawer()
            startRoutePlanning()
        }
        drawerItem(panel, "☆", "Избранное", "Сохранённые места") {
            infoDialog("Избранное", "Сохранение избранных мест будет добавлено позже.")
        }
        drawerDivider(panel)
        drawerItem(panel, "◉", "Антирадар", radarStatusText()) {
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
        drawerItem(panel, "⚙", "Настройки", "Параметры навигатора") {
            infoDialog("Настройки", "Дополнительные настройки добавим по мере подключения функций.")
        }
        val spacer = View(this)
        panel.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        val version = TextView(this).apply {
            text = "FreeNavigator · версия 0.3.1"
            textSize = 11f
            setTextColor(0xFF91A0B1.toInt())
            setPadding(dp(22), dp(14), dp(16), dp(8))
        }
        panel.addView(version)
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

    private fun showMapModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Режим карт")
            .setSingleChoiceItems(arrayOf("Онлайн-карта", "Офлайн-карты (в разработке)"), 0) { dialog, which ->
                if (which == 0) statusText.text = "Карта · онлайн"
                else infoDialog("Офлайн-карты", "Загрузка офлайн-карт будет реализована следующим этапом.")
                dialog.dismiss()
                hideDrawer()
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
            addSearchResultMarker(style)
            addRouteLine(style)
            if (navigationActive) lastNavigationLocation?.let { addNavigationMarker(style, it) }
        }
        statusText.text = if (darkTheme) "Тёмная карта · онлайн" else "Карта · онлайн"
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Результаты поиска")
                        .setItems(results.map { it.shortLabel }.toTypedArray()) { _, index ->
                            val item = results[index]
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
        var photonError: Exception? = null
        try {
            val photon = photonSearch(query)
            if (photon.isNotEmpty()) return photon
        } catch (e: Exception) {
            photonError = e
        }

        try {
            return nominatimSearch(query)
        } catch (fallbackError: Exception) {
            throw IllegalStateException("Photon and Nominatim search failed", fallbackError).also {
                photonError?.let { old -> it.addSuppressed(old) }
            }
        }
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
            setRequestProperty("User-Agent", "FreeNavigator/0.2.1 (Android navigation app)")
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
                    val routes = osrmRoutes(origin.latitude, origin.longitude, destination.lat, destination.lon)
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
        statusText.text = "Маршрут: ${String.format(Locale.getDefault(), "%.1f", km)} км · около $minutes мин"
        speak("Маршрут готов. Расстояние ${String.format(Locale.getDefault(), "%.1f", km)} километра. В пути около $minutes минут")
        AlertDialog.Builder(this)
            .setTitle("Маршрут")
            .setMessage("До: ${destination.shortLabel}\nРасстояние: ${String.format(Locale.getDefault(), "%.1f", km)} км\nВ пути: около $minutes мин\n\nПолный маршрут будет показан на карте 5 секунд перед включением навигации.")
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
        navigationDistanceMeters = distanceMeters
        navigationDurationSeconds = durationSeconds
        navigationSteps = route.steps
        nextVoiceStepIndex = 0
        currentRouteIndex = 0
        navigationActive = true
        navigationStartedAt = System.currentTimeMillis()
        arrivalAnnounced = false
        lastNavigationLocation = null
        startForegroundGuidance(route)
        routeProgress?.progress = 0
        val startLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(navigationStartedAt))
        routeStartTime?.text = "Начало $startLabel"
        routeArrivalTime?.text = "Прибытие —:—"
        routeRemainingText?.text = "До конца маршрута: ${String.format(Locale.getDefault(), "%.1f", distanceMeters / 1000.0)} км"
        speak("Маршрут начат")
        navigationPanel?.visibility = View.VISIBLE
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
                    if (navigationActive) navigationInfo?.text = "GPS выключен — включите местоположение"
                }
            }
            navigationLocationListener = listener
            var requested = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 1000L, 2f, listener, mainLooper)
                    requested = true
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
            ContextCompat.startForegroundService(this, intent)
            foregroundGuidanceStarted = true
        } catch (e: Exception) {
            foregroundGuidanceStarted = false
            toast("Не удалось включить фоновое ведение: ${e.message ?: "ошибка службы"}")
        }
    }

    private fun updateNavigation(location: Location) {
        if (!navigationActive) return
        checkRadarProximity(location)
        val style = map?.style ?: return
        addNavigationMarker(style, location)
        val nearestIndex = nearestRoutePointIndex(location.latitude, location.longitude)
        currentRouteIndex = nearestIndex
        announceNextManeuver(location)
        val nearestPoint = navigationPoints[nearestIndex]
        var remaining = distanceBetween(location.latitude, location.longitude,
            nearestPoint.latitude(), nearestPoint.longitude())
        for (i in nearestIndex until navigationPoints.lastIndex) {
            val a = navigationPoints[i]
            val b = navigationPoints[i + 1]
            remaining += distanceBetween(a.latitude(), a.longitude(), b.latitude(), b.longitude())
        }
        val total = navigationDistanceMeters.coerceAtLeast(1.0)
        val fraction = (remaining / total).coerceIn(0.0, 1.0)
        routeProgress?.progress = ((1.0 - fraction) * 1000).toInt().coerceIn(0, 1000)
        val minutes = (navigationDurationSeconds * fraction / 60.0).toInt().coerceAtLeast(1)
        val kmText = if (remaining >= 1000) String.format(java.util.Locale.getDefault(), "%.1f км", remaining / 1000.0)
            else "${remaining.toInt()} м"
        navigationInfo?.text = "В пути · около $minutes мин"
        routeRemainingText?.text = "До конца маршрута: ${String.format(Locale.getDefault(), "%.1f", (remaining / 1000.0).coerceAtLeast(0.0))} км"
        val arrival = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + minutes * 60_000L))
        routeArrivalTime?.text = "Прибытие $arrival"
        val bearing = if (location.hasBearing() && location.speed > 0.5f) location.bearing.toDouble()
            else lastNavigationLocation?.let { it.bearingTo(location).toDouble() } ?: map?.cameraPosition?.bearing ?: 0.0
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(location.latitude, location.longitude)).zoom(17.0).bearing(bearing).tilt(45.0).build()
        ))
        lastNavigationLocation = Location(location)
        if (remaining < 25.0) {
            navigationInfo?.text = "Вы прибыли к месту назначения"
            routeRemainingText?.text = "До конца маршрута: 0,0 км"
            if (!arrivalAnnounced) { speak("Вы прибыли к месту назначения"); arrivalAnnounced = true }
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

    private fun nearestRoutePointIndex(lat: Double, lon: Double): Int {
        if (navigationPoints.isEmpty()) return 0
        var bestIndex = currentRouteIndex.coerceIn(0, navigationPoints.lastIndex)
        var bestDistance = Double.MAX_VALUE
        for (index in bestIndex..navigationPoints.lastIndex) {
            val point = navigationPoints[index]
            val d = distanceBetween(lat, lon, point.latitude(), point.longitude())
            if (d < bestDistance) { bestDistance = d; bestIndex = index }
        }
        return bestIndex
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
        foregroundGuidanceStarted = false
        try { startService(Intent(this, NavigationForegroundService::class.java).setAction(NavigationForegroundService.ACTION_STOP)) } catch (_: Exception) { }
        pendingNavigation = null
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            navigationLocationListener?.let { manager.removeUpdates(it) }
        } catch (_: Exception) {}
        navigationLocationListener = null
        navigationPanel?.visibility = View.GONE
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
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
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
                    if (type != "depart") steps.add(RouteStep(point, instruction, step.optDouble("distance")))
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
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
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

    private fun radarStatusText(): String {
        val updated = getSharedPreferences("free_navigator_settings", MODE_PRIVATE).getLong("radar_updated_at", 0L)
        val date = if (updated > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updated)) else "ещё не загружена"
        return (if (radarEnabled) "Включён" else "Выключен") + " · база: $date"
    }

    private fun scheduleRadarUpdates() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
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
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("center_lat" to latitude, "center_lon" to longitude))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("freenavigator-radar-refresh", ExistingWorkPolicy.KEEP, request)
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
                    checkRadarProximity(location)
                    enqueueRadarRefresh(location.latitude, location.longitude, false)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            radarLocationListener = listener
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) manager.requestLocationUpdates(provider, 5000L, 20f, listener, mainLooper)
            }
            currentDeviceLocation()?.let { checkRadarProximity(it); enqueueRadarRefresh(it.latitude, it.longitude, false) }
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
        if (!radarEnabled || !speechReady) return
        val prefs = getSharedPreferences("free_navigator_settings", MODE_PRIVATE)
        val raw = prefs.getString("radar_cache", null) ?: return
        try {
            val cameras = org.json.JSONArray(raw)
            var nearestId: String? = null
            var nearestDistance = 800.0
            var nearestLimit = ""
            for (i in 0 until cameras.length()) {
                val camera = cameras.optJSONObject(i) ?: continue
                val id = camera.optString("id")
                if (id.isBlank() || id in announcedRadarIds) continue
                val distance = distanceBetween(location.latitude, location.longitude, camera.optDouble("lat"), camera.optDouble("lon"))
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestId = id
                    nearestLimit = camera.optString("maxspeed")
                }
            }
            if (nearestId != null) {
                val limitSpeech = nearestLimit.takeIf { it.isNotBlank() }?.let { " Ограничение $it." }.orEmpty()
                speak("Впереди камера контроля скорости.$limitSpeech")
                announcedRadarIds.add(nearestId)
            }
            // Allow a fresh warning after the vehicle has moved well away and returned.
            val removeIds = mutableListOf<String>()
            for (id in announcedRadarIds) {
                for (i in 0 until cameras.length()) {
                    val camera = cameras.optJSONObject(i) ?: continue
                    if (camera.optString("id") == id) {
                        if (distanceBetween(location.latitude, location.longitude, camera.optDouble("lat"), camera.optDouble("lon")) > 2500.0) removeIds.add(id)
                        break
                    }
                }
            }
            announcedRadarIds.removeAll(removeIds.toSet())
        } catch (_: Exception) {}
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
        speech?.stop(); speech?.shutdown(); screenScope.cancel(); mapView.onDestroy(); super.onDestroy()
    }

    private data class PlaceResult(val label: String, val lat: Double, val lon: Double, val shortLabel: String = label)
    private data class RouteStep(val point: Point, val instruction: String, val distanceMeters: Double, var announced: Boolean = false)
    private data class RouteData(val points: List<Point>, val durationSeconds: Int, val distanceMeters: Double, val steps: List<RouteStep>)

    companion object {
        private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
        private const val LOCATION_SOURCE_ID = "free-navigator-location-source"
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
