package com.example.voice_assistant

import com.example.voice_assistant.actions.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WeatherSearchTest {
    @Test fun recognizesWeatherAndRejectsForecastAsCurrent() {
        for (query in listOf("weather in Bangalore", "wheather of bangalore", "what is the weather in Bangalore today?"))
            assertEquals("Bangalore".lowercase(), WeatherSearch.cityFrom(query)?.lowercase())
        assertNull(WeatherSearch.cityFrom("Mount Everest"))
        assertEquals("GOOGLE_SEARCH", LocalIntentRouter().decide("weather in Bangalore").proposedAction?.name)
        assertNull(LocalIntentRouter().decide("do not search for weather in Bangalore").proposedAction)
    }
    @Test fun readsActualValuesAndResolvedCityWithoutModel() = runBlocking {
        val urls = mutableListOf<String>()
        val weather = WeatherSearch { url ->
            urls.add(url)
            JsonParser.parseString(if (url.contains("geocoding-api"))
                """{"results":[{"name":"Bangalore Town","country_code":"PK","country":"Pakistan","latitude":24.8,"longitude":67.0},{"name":"Bengaluru","country_code":"IN","admin1":"Karnataka","country":"India","latitude":12.97,"longitude":77.59}]}"""
            else """{"timezone":"Asia/Kolkata","current":{"time":"2026-10-08T12:00","temperature_2m":24.5,"apparent_temperature":25.2,"weather_code":3}}""").asJsonObject
        }
        val result = weather.search("weather of Bangalore").single()
        assertTrue(result.text.contains("Bengaluru, India"))
        assertTrue(result.text.contains("24.5 degrees Celsius, overcast"))
        assertTrue(result.text.contains("25.2 degrees"))
        assertTrue(result.text.contains("Asia/Kolkata"))
        assertTrue(urls[1].contains("latitude=12.97&longitude=77.59"))
        assertTrue(urls[0].contains("name=Bengaluru"))
        assertTrue(urls[0].contains("countryCode=IN"))
        assertTrue(result.directAnswer)
        assertEquals("OPEN_METEO", result.provider)
    }
    @Test fun unknownCityDoesNotFetchWeather() = runBlocking {
        var requests = 0
        val weather = WeatherSearch { requests++; JsonParser.parseString("{}").asJsonObject }
        assertTrue(weather.search("weather in nowhere").isEmpty())
        assertEquals(1, requests)
    }
    @Test fun bengaluruRejectsWrongCountryInsteadOfFetchingWeather() = runBlocking {
        var requests = 0
        val weather = WeatherSearch { requests++; JsonParser.parseString(
            """{"results":[{"name":"Bangalore Town","country_code":"PK","admin1":"Sindh"}]}""").asJsonObject }
        assertTrue(weather.search("weather in Bengaluru").isEmpty())
        assertEquals(1, requests)
    }
    @Test fun futureWeatherIsNeverReplacedWithCurrentConditions() = runBlocking {
        val weather = WeatherSearch { error("Must not fetch current conditions for tomorrow") }
        assertTrue(weather.search("weather in Bangalore tomorrow").isEmpty())
    }
    @Test fun factualSummaryKeepsCompleteSentences() {
        assertEquals("One fact. Second fact. Third fact.", spokenSummary("One fact. Second fact. Third fact. Fourth fact."))
    }
}
