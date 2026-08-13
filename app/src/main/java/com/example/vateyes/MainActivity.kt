package com.example.vateyes

import android.content.Context
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.gestures.gestures

data class VatsimData(val general: General?, val pilots: List<Pilot> = emptyList(), val controllers: List<Controller> = emptyList(), val atis: List<Controller> = emptyList())
data class General(@SerializedName("update_timestamp") val updateTimestamp: String? = null, @SerializedName("connected_clients") val connectedClients: Int = 0, @SerializedName("unique_users") val uniqueUsers: Int = 0)
data class Pilot(val cid: Int = 0, val name: String = "", val callsign: String = "", val latitude: Double = 0.0, val longitude: Double = 0.0, val altitude: Int = 0, val groundspeed: Int = 0, val heading: Int = 0, @SerializedName("flight_plan") val flightPlan: FlightPlan? = null)
data class FlightPlan(val departure: String? = null, val arrival: String? = null, val route: String? = null, val altitude: String? = null, val aircraft: String? = null)
data class Controller(val cid: Int = 0, val name: String = "", val callsign: String = "", val frequency: String = "", val facility: Int = 0)
data class Airport(val icao: String = "", val iata: String = "", val name: String = "", val city: String = "", val country: String = "", val lat: Double = 0.0, val lon: Double = 0.0)
data class WeatherResponse(val main: WeatherMain? = null, val weather: List<WeatherDescription> = emptyList(), val wind: WeatherWind? = null)
data class WeatherMain(val temp: Double = 0.0, @SerializedName("feels_like") val feelsLike: Double = 0.0, val humidity: Int = 0, val pressure: Int = 0)
data class WeatherDescription(val description: String = "")
data class WeatherWind(val speed: Double = 0.0)
data class VatsimEvent(val id: Int = 0, val name: String = "", val description: String? = null, @SerializedName("start_time") val startTime: String? = null, @SerializedName("end_time") val endTime: String? = null, val link: String? = null)
data class VatsimEventsResponse(val data: List<VatsimEvent> = emptyList())
data class VatsimCountry(val code: String = "", val name: String = "", val division: String? = null)
data class VatsimMember(val id: Int = 0, val name: String = "", val pilotRating: String? = null, val controllerRating: String? = null, val region: String? = null, val division: String? = null, val subdivision: String? = null)
data class OnlineAtc(val id: Int = 0, val callsign: String = "", val start: String = "", val server: String = "", val rating: Int = 0, val fp: String? = null)
private suspend fun loadAirports(context: Context): Map<String, Airport> = withContext(kotlinx.coroutines.Dispatchers.IO) {
    context.assets.open("airports.json").use { input -> input.reader().use { Gson().fromJson(it, object : TypeToken<Map<String, Airport>>() {}.type) } }
}

private interface VatsimApi { @GET("v3/vatsim-data.json") suspend fun live(): VatsimData }
private val api: VatsimApi by lazy { Retrofit.Builder().baseUrl("https://data.vatsim.net/").client(OkHttpClient.Builder().build()).addConverterFactory(GsonConverterFactory.create()).build().create(VatsimApi::class.java) }
private interface OpenWeatherApi { @GET("data/2.5/weather") suspend fun current(@retrofit2.http.Query("lat") lat: Double, @retrofit2.http.Query("lon") lon: Double, @retrofit2.http.Query("appid") appId: String, @retrofit2.http.Query("units") units: String = "metric"): WeatherResponse }
private val openWeatherApi: OpenWeatherApi by lazy { Retrofit.Builder().baseUrl("https://api.openweathermap.org/").client(OkHttpClient.Builder().build()).addConverterFactory(GsonConverterFactory.create()).build().create(OpenWeatherApi::class.java) }
private interface VatsimMetarApi { @GET("{icao}") suspend fun metar(@retrofit2.http.Path("icao") icao: String, @retrofit2.http.Query("format") format: String = "text"): String }
private val vatsimMetarApi: VatsimMetarApi by lazy { Retrofit.Builder().baseUrl("https://metar.vatsim.net/").client(OkHttpClient.Builder().build()).addConverterFactory(retrofit2.converter.scalars.ScalarsConverterFactory.create()).build().create(VatsimMetarApi::class.java) }
private interface VatsimExtrasApi {
    @GET("https://my.vatsim.net/api/v2/events") suspend fun events(): List<VatsimEvent>
    @GET("https://my.vatsim.net/api/v2/events/view/division/{division}") suspend fun eventsByDivision(@retrofit2.http.Path("division") division: String): VatsimEventsResponse
    @GET("https://my.vatsim.net/api/v1/events/50") suspend fun eventsV1(): VatsimEventsResponse
    @GET("v2/user/{cid}") suspend fun member(@retrofit2.http.Path("cid") cid: Int): VatsimMember
    @GET("v2/atc/online") suspend fun onlineAtc(): List<OnlineAtc>
}
private val vatsimExtrasApi: VatsimExtrasApi by lazy { Retrofit.Builder().baseUrl("https://api.vatsim.net/").client(OkHttpClient.Builder().build()).addConverterFactory(GsonConverterFactory.create()).build().create(VatsimExtrasApi::class.java) }

class MainViewModel : ViewModel() {
    var data by mutableStateOf<VatsimData?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var weather by mutableStateOf<WeatherResponse?>(null); private set
    var weatherLoading by mutableStateOf(false); private set
    var weatherError by mutableStateOf<String?>(null); private set
    var metar by mutableStateOf<String?>(null); private set
    var metarLoading by mutableStateOf(false); private set
    var metarError by mutableStateOf<String?>(null); private set
    var events by mutableStateOf<List<VatsimEvent>>(emptyList()); private set
    var member by mutableStateOf<VatsimMember?>(null); private set
    var extrasError by mutableStateOf<String?>(null); private set
    var trackedCids by mutableStateOf<Set<Int>>(emptySet()); private set
    var onlineAtc by mutableStateOf<List<OnlineAtc>>(emptyList()); private set
    var countries by mutableStateOf<List<VatsimCountry>>(emptyList()); private set
    init { refresh(); viewModelScope.launch { while (true) { delay(20_000); refresh() } } }
    fun refresh() = viewModelScope.launch { loading = true; error = null; runCatching { api.live() }.onSuccess { data = it }.onFailure { error = it.message ?: "Unable to reach VATSIM" }; loading = false }
    fun loadWeather(airport: Airport) = viewModelScope.launch {
        val key = BuildConfig.OPENWEATHER_API_KEY
        if (key.isBlank()) { weatherError = "Add OPENWEATHER_API_KEY to Gradle properties first"; return@launch }
        weatherLoading = true; weatherError = null
        runCatching { openWeatherApi.current(airport.lat, airport.lon, key) }.onSuccess { weather = it }.onFailure { weatherError = it.message ?: "Unable to load weather" }
        weatherLoading = false
    }
    fun loadMetar(airport: Airport) = viewModelScope.launch {
        metarLoading = true
        metarError = null
        runCatching { vatsimMetarApi.metar(airport.icao.uppercase()) }
            .onSuccess { metar = it.trim() }
            .onFailure { metarError = it.message ?: "Unable to load METAR" }
        metarLoading = false
    }
    fun loadCountries() = viewModelScope.launch { runCatching { Retrofit.Builder().baseUrl("https://api.vatsim.net/").client(OkHttpClient.Builder().build()).addConverterFactory(GsonConverterFactory.create()).build().create(CountriesApi::class.java).countries() }.onSuccess { countries = it } }
    fun loadEvents(division: String? = null) = viewModelScope.launch {
        extrasError = null
        runCatching { if (division.isNullOrBlank()) vatsimExtrasApi.events() else vatsimExtrasApi.eventsByDivision(division).data }
            .onSuccess { events = it }
            .onFailure { v2Error ->
                // V2 is currently unavailable in some environments; fall back to the V1 feed.
                runCatching { vatsimExtrasApi.eventsV1().data }
                    .onSuccess { events = it }
                    .onFailure { v1Error ->
                        extrasError = "Unable to load events (V2/V1): ${v1Error.message ?: v2Error.message ?: "network error"}"
                    }
            }
    }
    fun loadOnlineAtc() = viewModelScope.launch { runCatching { vatsimExtrasApi.onlineAtc() }.onSuccess { onlineAtc = it }.onFailure { extrasError = it.message ?: "Unable to load online ATC" } }
    fun loadMember(cid: Int) = viewModelScope.launch { member = null; runCatching { vatsimExtrasApi.member(cid) }.onSuccess { member = it }.onFailure { extrasError = it.message ?: "Member not found" } }
    fun toggleTracked(cid: Int) { trackedCids = if (cid in trackedCids) trackedCids - cid else trackedCids + cid }
}

private interface CountriesApi { @GET("api/countries/") suspend fun countries(): List<VatsimCountry> }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VateyesTheme { VateyesApp() } }
    }
}

@Composable private fun VateyesTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xff3b82f6),
        primaryContainer = Color(0xff1e3a8a),
        secondary = Color(0xff06b6d4),
        secondaryContainer = Color(0xff0e7490),
        tertiary = Color(0xff8b5cf6),
        background = Color(0xfff8fafc),
        surface = Color.White,
        surfaceVariant = Color(0xffeef2f7),
        error = Color(0xffef4444),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xff0f172a),
        onSurface = Color(0xff0f172a),
        onSurfaceVariant = Color(0xff64748b),
        onPrimaryContainer = Color(0xff1e3a8a),
        onSecondaryContainer = Color(0xff164e63),
        onTertiaryContainer = Color(0xff4c1d95),
        errorContainer = Color(0xffffe4e6),
        onErrorContainer = Color(0xff9f1239)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable fun VateyesApp(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pilot?>(null) }
    var airports by remember { mutableStateOf<Map<String, Airport>>(emptyMap()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { airports = loadAirports(context) }

    val titles = listOf("Live", "Aircraft", "ATC", "Weather", "Events", "Track")
    val icons = listOf(
        Icons.Default.Dashboard,
        Icons.Default.Air,
        Icons.Default.HeadsetMic,
        Icons.Default.Cloud,
        Icons.Default.Event,
        Icons.Default.PersonSearch
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (tab == 0) {
            MapOverview(vm, airports, Modifier.fillMaxSize(), onPilot = { selected = it })
        } else {
            Column(Modifier.fillMaxSize()) {
                ModernTopBar(titles[tab], onRefresh = { vm.refresh() })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        1 -> PilotList(vm.data?.pilots ?: emptyList(), Modifier.fillMaxSize(), onPilot = { selected = it })
                        2 -> ControllerList(vm, Modifier.fillMaxSize())
                        3 -> Weather(vm, airports, Modifier.fillMaxSize())
                        4 -> Events(vm, Modifier.fillMaxSize())
                        else -> Tracking(vm, Modifier.fillMaxSize(), onPilot = { selected = it })
                    }
                }
            }
        }

        GlassBottomNavigation(
            selectedTab = tab,
            onTabSelected = { tab = it },
            titles = titles,
            icons = icons,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    selected?.let { pilot ->
        ModernPilotDialog(pilot = pilot, onDismiss = { selected = null })
    }
}

@Composable
private fun GlassBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    titles: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        shape = RoundedCornerShape(32.dp),
        color = Color.White.copy(alpha = 0.96f),
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icons.forEachIndexed { index, icon ->
                GlassNavItem(
                    icon = icon,
                    label = titles[index],
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun GlassNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ModernTopBar(title: String, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "VATEyes",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Refresh,
                    "Refresh",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ModernPilotDialog(pilot: Pilot, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    pilot.callsign,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    pilot.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        InfoChip("CID", "${pilot.cid}")
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoCard(
                        modifier = Modifier.weight(1f),
                        label = "Altitude",
                        value = "${pilot.altitude}",
                        unit = "ft"
                    )
                    InfoCard(
                        modifier = Modifier.weight(1f),
                        label = "Speed",
                        value = "${pilot.groundspeed}",
                        unit = "kt"
                    )
                    InfoCard(
                        modifier = Modifier.weight(1f),
                        label = "Heading",
                        value = "${pilot.heading}",
                        unit = "°"
                    )
                }

                pilot.flightPlan?.let { plan ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Flight Plan",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    plan.departure ?: "???",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    Icons.Default.ArrowForward,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    plan.arrival ?: "???",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            plan.aircraft?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Aircraft: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            plan.route?.takeIf { it.isNotBlank() }?.let { route ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Route",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    route,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Text("Close", color = Color.White)
            }
        }
    )
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InfoCard(modifier: Modifier, label: String, value: String, unit: String) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun MapOverview(
    vm: MainViewModel,
    airports: Map<String, Airport>,
    modifier: Modifier,
    onPilot: (Pilot) -> Unit
) {
    val pilots = vm.data?.pilots.orEmpty()
    val routeAirports = pilots.flatMap {
        listOfNotNull(it.flightPlan?.departure, it.flightPlan?.arrival)
    }.mapNotNull { airports[it.uppercase()] }.distinctBy { it.icao }

    Box(modifier.fillMaxSize()) {
        MapboxAirspaceMap(pilots, routeAirports, onPilot)

        // Top status bar
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            color = Color.White.copy(alpha = 0.96f),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Air,
                    null,
                    tint = Color(0xff60a5fa),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "${pilots.size} aircraft",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Icon(
                    Icons.Default.HeadsetMic,
                    null,
                    tint = Color(0xff06b6d4),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "${vm.data?.controllers?.size ?: 0} ATC",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Refresh button
        IconButton(
            onClick = { vm.refresh() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(48.dp)
                .background(
                    Color.White.copy(alpha = 0.96f),
                    CircleShape
                )
        ) {
            Icon(
                Icons.Default.Refresh,
                "Refresh",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        vm.error?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .padding(bottom = 80.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PilotList(
    pilots: List<Pilot>,
    modifier: Modifier,
    onPilot: (Pilot) -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            Text(
                "Active Flights",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (pilots.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No aircraft online",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(pilots) { pilot ->
                ModernPilotCard(pilot, onPilot)
            }
        }
    }
}

@Composable
private fun ModernPilotCard(pilot: Pilot, onClick: (Pilot) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(pilot) },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pilot.callsign,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        pilot.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.Air,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FlightInfoChip(
                    icon = Icons.Default.Height,
                    label = "${pilot.altitude} ft",
                    modifier = Modifier.weight(1f)
                )
                FlightInfoChip(
                    icon = Icons.Default.Speed,
                    label = "${pilot.groundspeed} kt",
                    modifier = Modifier.weight(1f)
                )
                FlightInfoChip(
                    icon = Icons.Default.Explore,
                    label = "${pilot.heading}°",
                    modifier = Modifier.weight(1f)
                )
            }

            pilot.flightPlan?.let { plan ->
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        plan.departure ?: "???",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Icon(
                        Icons.Default.ArrowForward,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        plan.arrival ?: "???",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun FlightInfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable private fun MapboxAirspaceMap(pilots: List<Pilot>, airports: List<Airport>, onPilot: (Pilot) -> Unit) {
    val token = BuildConfig.MAPBOX_ACCESS_TOKEN
    if (token.isBlank() || !token.startsWith("pk.")) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Configure a public Mapbox token (pk.*) in gradle.properties") }; return }
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView?.onStart()
                Lifecycle.Event.ON_STOP -> mapView?.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); mapView?.onDestroy() }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        try {
            MapView(context).also { view ->
                mapView = view
                view.onStart()
                view.getMapboxMap().loadStyleUri(Style.LIGHT) {
                    runCatching {
                        val pointManager = view.annotations.createPointAnnotationManager()
                        airports.take(250).forEach { airport -> pointManager.create(PointAnnotationOptions().withPoint(Point.fromLngLat(airport.lon, airport.lat)).withTextField(airport.icao).withTextColor(AndroidColor.DKGRAY).withTextSize(10.0)) }
                        pilots.forEach { pilot -> pointManager.create(PointAnnotationOptions().withPoint(Point.fromLngLat(pilot.longitude, pilot.latitude)).withTextField("PLN ${pilot.callsign}").withTextColor(AndroidColor.BLUE).withTextSize(11.0)) }
                        val lines = view.annotations.createPolylineAnnotationManager()
                        pilots.forEach { pilot -> val route = listOfNotNull(pilot.flightPlan?.departure, pilot.flightPlan?.arrival).mapNotNull { code -> airports.firstOrNull { it.icao.equals(code, true) } }; if (route.size == 2) lines.create(PolylineAnnotationOptions().withPoints(route.map { Point.fromLngLat(it.lon, it.lat) }).withLineColor("#38BDF8").withLineWidth(2.0)) }
                        pointManager.addClickListener { annotation -> pilots.firstOrNull { "PLN ${it.callsign}" == annotation.textField }?.let(onPilot); true }
                    }
                }
            }
        } catch (_: Throwable) {
            android.widget.FrameLayout(context)
        }
    }, update = {})
}

@Composable
private fun ControllerList(vm: MainViewModel, modifier: Modifier) {
    LaunchedEffect(Unit) { vm.loadOnlineAtc() }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            Text(
                "Active ATC",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        vm.extrasError?.let {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        val controllers = vm.data?.controllers.orEmpty()
        if (controllers.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No controllers online",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(controllers) { controller ->
                ModernControllerCard(controller)
            }
        }
    }
}

@Composable
private fun ModernControllerCard(controller: Controller) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.HeadsetMic,
                        null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    controller.callsign,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    controller.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    controller.frequency,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
@Composable private fun ListRow(title: String, subtitle: String, detail: String, modifier: Modifier = Modifier) { Row(modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(Color(0xff69d2c2), CircleShape)); Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(detail, style = MaterialTheme.typography.labelMedium) } }
@Composable
private fun Events(vm: MainViewModel, modifier: Modifier) {
    var selectedCountry by remember { mutableStateOf<VatsimCountry?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadCountries() }
    LaunchedEffect(selectedCountry?.division) { vm.loadEvents(selectedCountry?.division) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            Text(
                "VATSIM Events",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Box {
                OutlinedButton(onClick = { menuExpanded = true }) {
                    Text(selectedCountry?.name ?: "All countries")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("All countries") }, onClick = { selectedCountry = null; menuExpanded = false })
                    vm.countries.filter { !it.division.isNullOrBlank() }.forEach { country ->
                        DropdownMenuItem(text = { Text(country.name) }, onClick = { selectedCountry = country; menuExpanded = false })
                    }
                }
            }
        }

        vm.extrasError?.let {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (vm.events.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Event,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No upcoming events",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(vm.events) { event ->
                ModernEventCard(event)
            }
        }
    }
}

@Composable
private fun ModernEventCard(event: VatsimEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        event.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    event.description?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.Event,
                        null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Schedule,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    event.startTime ?: "Date to be announced",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            event.endTime?.let {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AccessTime,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
@Composable
private fun Tracking(vm: MainViewModel, modifier: Modifier, onPilot: (Pilot) -> Unit) {
    var cidQuery by remember { mutableStateOf("") }
    var memberQuery by remember { mutableStateOf("") }
    val tracked = vm.data?.pilots.orEmpty().filter { it.cid in vm.trackedCids }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            Text(
                "Track Pilots",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Monitor your friends and favorite pilots",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Add by CID",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = cidQuery,
                            onValueChange = { cidQuery = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Enter CID") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        IconButton(
                            onClick = { cidQuery.toIntOrNull()?.let { vm.toggleTracked(it) } },
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Add,
                                "Track CID",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Member Lookup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = memberQuery,
                            onValueChange = { memberQuery = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("VATSIM member CID") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        IconButton(
                            onClick = {
                                memberQuery.toIntOrNull()?.let { vm.loadMember(it) }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Badge,
                                "Lookup member",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        vm.member?.let { member ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    member.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    "CID ${member.id}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                                shape = CircleShape
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(12.dp).size(24.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RatingChip(
                                modifier = Modifier.weight(1f),
                                label = "Pilot",
                                rating = member.pilotRating ?: "-"
                            )
                            RatingChip(
                                modifier = Modifier.weight(1f),
                                label = "Controller",
                                rating = member.controllerRating ?: "-"
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "${member.region ?: ""} ${member.division ?: ""}".trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Tracked Pilots Online",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (tracked.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PersonSearch,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No tracked pilots currently online",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(tracked) { pilot ->
                ModernPilotCard(pilot, onPilot)
            }
        }
    }
}

@Composable
private fun RatingChip(modifier: Modifier, label: String, rating: String) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.2f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                rating,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
@Composable
private fun Weather(vm: MainViewModel, airports: Map<String, Airport>, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var selectedAirport by remember { mutableStateOf<Airport?>(null) }
    val matches = if (query.length >= 2) {
        airports.values.filter {
            it.icao.contains(query, true) || it.iata.contains(query, true) || it.name.contains(query, true)
        }.take(6)
    } else emptyList()

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            Text(
                "Airport Weather",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Search by ICAO or IATA code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            TextField(
                value = query,
                onValueChange = { query = it.uppercase() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. ZBAA or PEK") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        items(matches) { airport ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedAirport = airport
                        vm.loadWeather(airport)
                        vm.loadMetar(airport)
                    },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.FlightTakeoff,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            airport.icao,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            airport.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        airport.city,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        selectedAirport?.let { airport ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${airport.icao} - ${airport.name}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "${airport.city}, ${airport.country}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            item {
                Text(
                    "VATSIM METAR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (vm.metarLoading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            vm.metarError?.let {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            vm.metar?.takeIf { it.isNotBlank() }?.let { report ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            report,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            if (vm.weatherLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            vm.weatherError?.let {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            vm.weather?.let { report ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 4.dp
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column {
                                    Text(
                                        "${report.main?.temp?.toInt() ?: 0}°C",
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        report.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() }
                                            ?: "Unknown",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = CircleShape
                                ) {
                                    Icon(
                                        Icons.Default.Cloud,
                                        null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(12.dp).size(32.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(20.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WeatherInfoCard(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.Thermostat,
                                    label = "Feels Like",
                                    value = "${report.main?.feelsLike?.toInt() ?: 0}°C"
                                )
                                WeatherInfoCard(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.WaterDrop,
                                    label = "Humidity",
                                    value = "${report.main?.humidity ?: 0}%"
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WeatherInfoCard(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.Air,
                                    label = "Wind",
                                    value = "${report.wind?.speed ?: 0.0} m/s"
                                )
                                WeatherInfoCard(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.Compress,
                                    label = "Pressure",
                                    value = "${report.main?.pressure ?: 0} hPa"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherInfoCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
