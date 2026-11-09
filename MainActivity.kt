package ru.freenavigator

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
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

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) centerOnMyLocation()
        else toast("Разрешение на местоположение не предоставлено")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)
        super.onCreate(savedInstanceState)
        buildScreen(savedInstanceState)
    }

    private fun buildScreen(savedInstanceState: Bundle?) {
        val root = FrameLayout(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
            setBackgroundColor(0xEEFFFFFF.toInt())
            elevation = dp(6).toFloat()
        }
        val title = TextView(this).apply {
            text = "FREE NAVIGATOR"
            textSize = 18f
            setTextColor(0xFF173D6B.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchInput = EditText(this).apply {
            hint = "Поиск адреса или места"
            singleLine = true
            textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), 0, dp(8), 0)
        }
        val searchButton = Button(this).apply { text = "Найти"; setOnClickListener { searchPlace() } }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(92), dp(52)))
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { searchPlace(); true } else false
        }
        topPanel.addView(title)
        topPanel.addView(searchRow)
        root.addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(12)
        })

        val rightButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val locateButton = Button(this).apply {
            text = "◎"
            textSize = 24f
            contentDescription = "Моё местоположение"
            setOnClickListener { requestLocationOrCenter() }
        }
        val themeButton = Button(this).apply {
            text = "☾"
            textSize = 22f
            contentDescription = "Переключить тему карты"
            setOnClickListener { toggleMapTheme(this) }
        }
        rightButtons.addView(locateButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        rightButtons.addView(themeButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(8) })
        root.addView(rightButtons, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(12)
        })

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10),dp(10),dp(10),dp(10))
            setBackgroundColor(0xEEFFFFFF.toInt())
            elevation = dp(5).toFloat()
        }
        statusText = TextView(this).apply {
            text = "Загрузка карты…"
            textSize = 12f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
        }
        val attribution = TextView(this).apply {
            text = "© OpenStreetMap contributors  ·  © OpenFreeMap"
            textSize = 10f
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
        }
        bottomPanel.addView(statusText)
        bottomPanel.addView(attribution)
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(12)
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

    private fun loadMapStyle() {
        val styleUrl = if (darkTheme) DARK_STYLE else LIGHT_STYLE
        map?.setStyle(Style.Builder().fromUri(styleUrl))
        statusText.text = if (darkTheme) "Тёмная карта · онлайн" else "Карта · онлайн"
    }

    private fun toggleMapTheme(button: Button) {
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
                val results = withContext(Dispatchers.IO) { photonSearch(query) }
                if (results.isEmpty()) {
                    statusText.text = "Ничего не найдено"
                    toast("По запросу ничего не найдено")
                } else {
                    statusText.text = "Найдено: ${results.size}"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Результаты поиска")
                        .setItems(results.map { it.label }.toTypedArray()) { _, index ->
                            val item = results[index]
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
                statusText.text = "Поиск временно недоступен"
                toast("Не удалось выполнить поиск. Проверьте интернет")
            }
        }
    }

    private fun photonSearch(query: String): List<PlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://photon.komoot.io/api/?q=$encoded&lang=ru&limit=6")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "FreeNavigator/0.2.0 Android")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
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
        } finally { connection.disconnect() }
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
            map?.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude), 15.0
                )
            )
            statusText.text = "Ваше местоположение"
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
    override fun onDestroy() { screenScope.cancel(); mapView.onDestroy(); super.onDestroy() }

    private data class PlaceResult(val label: String, val lat: Double, val lon: Double)

    companion object {
        private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
    }
}
