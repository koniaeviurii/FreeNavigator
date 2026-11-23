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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
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
    private var navigationLocationListener: LocationListener? = null
    private var navigationSource: GeoJsonSource? = null
    private var navigationPoints: List<Point> = emptyList()
    private var navigationDistanceMeters = 0.0
    private var navigationDurationSeconds = 0
    private var navigationInfo: TextView? = null
    private var navigationPanel: LinearLayout? = null
    private var bottomControlsView: LinearLayout? = null
    private var lastNavigationLocation: Location? = null
    private var pendingNavigation: Triple<List<Point>, Double, Int>? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            val pending = pendingNavigation
            if (pending != null) {
                pendingNavigation = null
                beginNavigation(pending.first, pending.second, pending.third)
            } else centerOnMyLocation()
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
        buildScreen(savedInstanceState)
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
            contentDescription = "Очистить строку поиска"
            setOnClickListener {
                searchInput.text.clear()
                searchInput.requestFocus()
                statusText.text = ""
                val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
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

        // Search button stays on the left; the search field expands to its right.
        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val findButton = TextView(this).apply {
            text = "⌕  НАЙТИ"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF1769AA.toInt())
            setOnClickListener { toggleSearch() }
        }
        bottomControls.addView(findButton, LinearLayout.LayoutParams(dp(104), dp(44)))
        bottomControls.addView(searchPanel, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(8)
        })
        bottomControlsView = bottomControls
        root.addView(bottomControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(14)
        })

        val navPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            setBackgroundColor(0xF7FFFFFF.toInt())
            elevation = dp(9).toFloat()
            visibility = View.GONE
        }
        val navInfo = TextView(this).apply {
            text = "Ожидание GPS…"
            textSize = 15f
            setTextColor(0xFF173B5E.toInt())
        }
        navigationInfo = navInfo
        navPanel.addView(navInfo, LinearLayout.LayoutParams(0, -2, 1f))
        val stopNavigation = TextView(this).apply {
            text = "✕"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFB3261E.toInt())
            contentDescription = "Завершить маршрут"
            setOnClickListener { stopNavigation() }
        }
        navPanel.addView(stopNavigation, LinearLayout.LayoutParams(dp(48), dp(44)))
        navigationPanel = navPanel
        root.addView(navPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(70)
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
        drawerItem(panel, "➤", "Проложить маршрут", "От моего местоположения до адреса") {
            hideDrawer()
            startRoutePlanning()
        }
        drawerItem(panel, "☆", "Избранное", "Сохранённые места") {
            infoDialog("Избранное", "Сохранение избранных мест будет добавлено позже.")
        }
        drawerDivider(panel)
        drawerItem(panel, "◉", "Антирадар", if (radarEnabled) "Включён" else "Выключен") {
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
            toast(if (radarEnabled) "Антирадар включён" else "Антирадар выключен")
        }
        drawerItem(panel, "⚙", "Настройки", "Параметры навигатора") {
            infoDialog("Настройки", "Дополнительные настройки добавим по мере подключения функций.")
        }
        val spacer = View(this)
        panel.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        val version = TextView(this).apply {
            text = "FreeNavigator · версия 0.3.0"
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
                        .setItems(results.map { it.label }.toTypedArray()) { _, index ->
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
            PlaceResult(label, coords.getDouble(1), coords.getDouble(0))
        }
    }

    private fun nominatimSearch(query: String): List<PlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=6&accept-language=ru")
        val results = org.json.JSONArray(body)
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val label = item.optString("display_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlaceResult(label, lat, lon)
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
                    val route = osrmRoute(origin.latitude, origin.longitude, destination.lat, destination.lon)
                    Pair(destination, route)
                }
                val destination = result.first
                val route = result.second
                routeLine = LineString.fromLngLats(route.first)
                map?.style?.let { addRouteLine(it) }
                // Fit the complete route instead of guessing a fixed zoom level.
                val boundsBuilder = LatLngBounds.Builder()
                route.first.forEach { boundsBuilder.include(LatLng(it.latitude(), it.longitude())) }
                val bounds = boundsBuilder.build()
                map?.let { currentMap ->
                    currentMap.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, dp(56))
                    )
                }
                val km = route.third / 1000.0
                val minutes = route.second / 60
                statusText.text = "Маршрут: ${"%.1f".format(java.util.Locale.getDefault(), km)} км · около $minutes мин"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Маршрут построен")
                    .setMessage("До: ${destination.label}\nРасстояние: ${"%.1f".format(java.util.Locale.getDefault(), km)} км\nВ пути: около $minutes мин")
                    .setPositiveButton("Начать маршрут") { _, _ ->
                        beginNavigation(route.first, route.third, route.second)
                    }
                    .show()
            } catch (e: Exception) {
                statusText.text = "Не удалось построить маршрут"
                toast("Маршрут не построен: ${e.message ?: "проверьте интернет"}")
            }
        }
    }

    private fun beginNavigation(points: List<Point>, distanceMeters: Double, durationSeconds: Int) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            pendingNavigation = Triple(points, distanceMeters, durationSeconds)
            toast("Разрешите доступ к местоположению для начала ведения")
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        navigationPoints = points
        navigationDistanceMeters = distanceMeters
        navigationDurationSeconds = durationSeconds
        navigationActive = true
        lastNavigationLocation = null
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

    private fun updateNavigation(location: Location) {
        if (!navigationActive) return
        val style = map?.style ?: return
        addNavigationMarker(style, location)
        val nearestIndex = nearestRoutePointIndex(location.latitude, location.longitude)
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
        val minutes = (navigationDurationSeconds * fraction / 60.0).toInt().coerceAtLeast(1)
        val kmText = if (remaining >= 1000) String.format(java.util.Locale.getDefault(), "%.1f км", remaining / 1000.0)
            else "${remaining.toInt()} м"
        navigationInfo?.text = "Осталось $kmText · около $minutes мин"
        val bearing = if (location.hasBearing() && location.speed > 0.5f) location.bearing.toDouble()
            else lastNavigationLocation?.let { it.bearingTo(location).toDouble() } ?: map?.cameraPosition?.bearing ?: 0.0
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(location.latitude, location.longitude)).zoom(17.0).bearing(bearing).tilt(45.0).build()
        ))
        lastNavigationLocation = Location(location)
        if (remaining < 25.0) {
            navigationInfo?.text = "Вы прибыли к месту назначения"
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
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        navigationPoints.forEachIndexed { index, point ->
            val d = distanceBetween(lat, lon, point.latitude(), point.longitude())
            if (d < bestDistance) { bestDistance = d; bestIndex = index }
        }
        return bestIndex
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun stopNavigation() {
        navigationActive = false
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

    private fun osrmRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Triple<List<Point>, Int, Double> {
        val address = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
        val body = httpGet(address)
        val routes = JSONObject(body).optJSONArray("routes") ?: throw IllegalStateException("Сервис маршрутов не вернул путь")
        if (routes.length() == 0) throw IllegalStateException("Маршрут не найден")
        val route = routes.getJSONObject(0)
        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coordinates.length()).map { i ->
            val pair = coordinates.getJSONArray(i)
            Point.fromLngLat(pair.getDouble(0), pair.getDouble(1))
        }
        if (points.size < 2) throw IllegalStateException("Недостаточно данных маршрута")
        return Triple(points, route.optDouble("duration").toInt(), route.optDouble("distance"))
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { stopNavigation(); screenScope.cancel(); mapView.onDestroy(); super.onDestroy() }

    private data class PlaceResult(val label: String, val lat: Double, val lon: Double)

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
