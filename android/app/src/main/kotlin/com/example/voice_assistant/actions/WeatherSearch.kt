package com.example.voice_assistant.actions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** No device location access: resolve only the city spoken by the user. */
class WeatherSearch(private val fetch: suspend (String) -> JsonObject = ::fetchWeatherJson) {
    suspend fun search(query: String): List<WebEvidence> {
        if (Regex("\\b(tomorrow|yesterday|next|last|forecast)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query))
            return emptyList() // Never substitute current conditions for another date.
        val city = cityFrom(query) ?: return emptyList()
        val bengaluru = Regex("^(bangalore|bengaluru)(?:,? (?:india|karnataka))?$", RegexOption.IGNORE_CASE).matches(city)
        val lookup = if (bengaluru) "Bengaluru" else city
        val locations = fetch("https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(lookup, "UTF-8") + "&count=10&language=en&format=json" +
            if (bengaluru) "&countryCode=IN" else "").getAsJsonArray("results")
        if (locations == null || locations.isEmpty) return emptyList()
        val place = if (bengaluru) locations.map { it.asJsonObject }.firstOrNull {
            it.get("country_code")?.asString == "IN" &&
                it.get("admin1")?.asString.equals("Karnataka", ignoreCase = true) &&
                it.get("name")?.asString?.lowercase(Locale.ROOT) in listOf("bangalore", "bengaluru")
        } ?: return emptyList() else locations[0].asJsonObject
        val latitude = place.get("latitude").asDouble
        val longitude = place.get("longitude").asDouble
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        val weather = fetch("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code&temperature_unit=celsius&timezone=auto")
        val current = weather.getAsJsonObject("current")
        val temperature = current.get("temperature_2m").asDouble
        val feelsLike = current.get("apparent_temperature").asDouble
        require(temperature.isFinite() && feelsLike.isFinite())
        val time = current.get("time").asString.replace('T', ' ')
        val zone = weather.get("timezone")?.asString.orEmpty()
        val name = place.get("name").asString
        val country = place.get("country")?.asString.orEmpty()
        val text = "Weather for $name${if (country.isBlank()) "" else ", $country"}: " +
            "${String.format(Locale.US, "%.1f", temperature)} degrees Celsius, ${condition(current.get("weather_code").asInt)}. " +
            "Feels like ${String.format(Locale.US, "%.1f", feelsLike)} degrees. Updated $time $zone."
        return listOf(WebEvidence(text, "https://open-meteo.com/", true, "OPEN_METEO"))
    }

    companion object {
        fun cityFrom(query: String): String? = Regex(
            "^(?:(?:what(?:'s| is) (?:the )?)|(?:tell me (?:the )?))?(?:current |today(?:'s)? )?(?:weather|wheather)(?: today)? (?:in|of|for|at) (.+?)(?: today| right now)?[?.!]*$",
            RegexOption.IGNORE_CASE).matchEntire(query.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..100 }

        fun condition(code: Int): String = when (code) {
            0 -> "clear skies"
            1 -> "mainly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "foggy"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61, 63, 65 -> "rain"
            66, 67 -> "freezing rain"
            71, 73, 75, 77 -> "snow"
            80, 81, 82 -> "rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorms"
            96, 99 -> "thunderstorms with hail"
            else -> "conditions unavailable"
        }
    }
}

private suspend fun fetchWeatherJson(url: String): JsonObject = withContext(Dispatchers.IO) {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8000; connection.readTimeout = 8000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "AikoAssistant/1.0")
        check(connection.responseCode == 200)
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 262144)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
    } finally { connection.disconnect() }
}

object SpokenWebSearch {
    suspend fun search(query: String): List<WebEvidence> = if (WeatherSearch.cityFrom(query) != null)
        WeatherSearch().search(query) else InstantAnswerSearch.search(query)
}
