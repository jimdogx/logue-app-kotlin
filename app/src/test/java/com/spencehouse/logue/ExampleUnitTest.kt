package com.spencehouse.logue

import com.spencehouse.logue.ui.model.DashboardViewModel
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testClarityChargeStatusAndVoltage() {
        // Clarity PHEV on 240V Level 2 (chargeMode = "1")
        val (status240, voltage240) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "charging",
            plugStatus = "plugged",
            chargeMode = "1"
        )
        assertEquals("Charging", status240)
        assertEquals("240V", voltage240)

        // Clarity PHEV on 120V Level 1 (chargeMode = "0")
        val (status120, voltage120) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "1",
            plugStatus = "1",
            chargeMode = "0"
        )
        assertEquals("Charging", status120)
        assertEquals("120V", voltage120)

        // Clarity PHEV Rapid / DCFC (chargeMode = "2")
        val (statusRapid, voltageRapid) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "charging",
            plugStatus = "plugged",
            chargeMode = "2"
        )
        assertEquals("Charging", statusRapid)
        assertEquals("Rapid", voltageRapid)

        // Ultium EV literal 120 / 240
        val (_, voltUltium120) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "charging",
            plugStatus = "plugged",
            chargeMode = "120"
        )
        assertEquals("120V", voltUltium120)

        val (_, voltUltium240) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "charging",
            plugStatus = "plugged",
            chargeMode = "240"
        )
        assertEquals("240V", voltUltium240)

        // Unplugged state -> null voltage
        val (statusUnplugged, voltageUnplugged) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "0",
            plugStatus = "0",
            chargeMode = "1"
        )
        assertEquals("Unplugged", statusUnplugged)
        assertNull(voltageUnplugged)

        // Plugged in but complete
        val (statusComplete, voltageComplete) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "complete",
            plugStatus = "plugged",
            chargeMode = "1"
        )
        assertEquals("Complete", statusComplete)
        assertEquals("240V", voltageComplete)

        // Actual Clarity logcat state: plugStatus="2" (locked/connected), chargeStatus="0", soc=100, chargeMode1="1"
        val (statusFinished, voltageFinished) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "0",
            plugStatus = "2",
            chargeMode = "1",
            batteryPercentage = 100
        )
        assertEquals("Complete", statusFinished)
        assertEquals("240V", voltageFinished)

        // Clarity finished charging with chargeMode empty, but previous cachedVoltage=240
        val (statusCached, voltageCached) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "0",
            plugStatus = "2",
            chargeMode = null,
            batteryPercentage = 100,
            cachedVoltage = 240
        )
        assertEquals("Complete", statusCached)
        assertEquals("240V", voltageCached)

        // plugStatus = "3"
        val (statusPlug3, _) = DashboardViewModel.formatChargeStatus(
            chargeStatus = "0",
            plugStatus = "3",
            chargeMode = null,
            batteryPercentage = 80
        )
        assertEquals("Plugged In", statusPlug3)
    }

    @Test
    fun testLocationParsing() {
        val payload = """
            {"state":{"reported":{"cigServiceRequestId":"location2276458971781663039345","status":"SUCCESS","responseBody":{"ignition":"unknown","gpsData":{"coordinate":{"latitude":42.757122,"longitude":-73.94447,"datum":"na","format":""},"dtTime":"2026-06-17T02:24:09Z","velocity":{"value":0.0,"unit":"na"},"courseHeading":0.0,"accuracy":{"pdop":0,"hdop":0}}}}}}
        """.trimIndent()

        val data = JSONObject(payload)
        val reported = data.optJSONObject("state")?.optJSONObject("reported")
        assertNotNull("reported object should not be null", reported)
        
        val rb = reported!!.optJSONObject("responseBody")
        assertNotNull("responseBody object should not be null", rb)

        val gpsData = rb!!.optJSONObject("gpsData")
        assertNotNull("gpsData object should not be null", gpsData)
        
        val coordinate = gpsData!!.optJSONObject("coordinate")
        assertNotNull("coordinate object should not be null", coordinate)

        val latitude = coordinate!!.optDouble("latitude")
        val longitude = coordinate!!.optDouble("longitude")

        assertEquals(42.757122, latitude, 0.000001)
        assertEquals(-73.94447, longitude, 0.000001)
    }

    @Test
    fun testDateParsing() {
        val startTimeStr = "2026-04-22T13:23:11.969+0000"
        try {
            val sf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
            val date = sf.parse(startTimeStr)
            assertNotNull("Parsed date should not be null", date)
            println("Successfully parsed date: $date, millis: ${date?.time}")
        } catch (e: Exception) {
            fail("Failed to parse date: ${e.message}")
        }
    }

    @Test
    fun testClarityChargeCommandPayloads() {
        val vin = "JHMZC5F14JC016163"
        val startPayload = """{"VIN":"$vin","rmt_request":{"req_type":"start_charge"}}"""
        val stopPayload = """{"VIN":"$vin","rmt_request":{"req_type":"stop_charge"}}"""

        val startJson = JSONObject(startPayload)
        assertEquals(vin, startJson.getString("VIN"))
        val startRmt = startJson.getJSONObject("rmt_request")
        assertEquals("start_charge", startRmt.getString("req_type"))

        val stopJson = JSONObject(stopPayload)
        assertEquals(vin, stopJson.getString("VIN"))
        val stopRmt = stopJson.getJSONObject("rmt_request")
        assertEquals("stop_charge", stopRmt.getString("req_type"))
    }

    @Test
    fun testClarityClimateCommandPayloads() {
        val vin = "JHMZC5F14JC016163"
        val startPayload = """{"VIN":"$vin","rmt_request":{"req_type":"start_acon","set_start_acon":{"acon_type":"force"}}}"""
        val stopPayload = """{"VIN":"$vin","rmt_request":{"req_type":"stop_acon"}}"""

        val startJson = JSONObject(startPayload)
        assertEquals(vin, startJson.getString("VIN"))
        val startRmt = startJson.getJSONObject("rmt_request")
        assertEquals("start_acon", startRmt.getString("req_type"))
        val setStartAcon = startRmt.getJSONObject("set_start_acon")
        assertEquals("force", setStartAcon.getString("acon_type"))

        val stopJson = JSONObject(stopPayload)
        assertEquals(vin, stopJson.getString("VIN"))
        val stopRmt = stopJson.getJSONObject("rmt_request")
        assertEquals("stop_acon", stopRmt.getString("req_type"))
    }

    @Test
    fun testClarityVinServiceAuthHash() {
        val serviceSalt = "DL2mBuVQsT7d54c2xaDf94jYe8D35c2p"
        val dateGmt = "20260927"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((serviceSalt + dateGmt).toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }

        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun testClarityCookieParsing() {
        val setCookies = listOf(
            "HICSESSIONKEY=8091B742F644404258448BD2594C8E120AF04E1C-n2; Expires=Mon, 28 Sep 2026 16:11:49 GMT; Path=/",
            "AWSELB=7729135504B3FFFA88FB484F9633F147C1077EC2AE7664;PATH=/;EXPIRES=Mon, 28 Sep 2026 16:11:49 GMT"
        )
        val cookieHeader = setCookies.map { it.substringBefore(";") }.joinToString("; ")
        assertEquals(
            "HICSESSIONKEY=8091B742F644404258448BD2594C8E120AF04E1C-n2; AWSELB=7729135504B3FFFA88FB484F9633F147C1077EC2AE7664",
            cookieHeader
        )
    }

    @Test
    fun testClarityPetRangeParsing() {
        val payload = """
            {
              "status": "success",
              "responseBody": {
                "evStatus": {
                  "vehicleInfo": {
                    "soc": {"unit": "%", "valid": true, "value": "100.0"},
                    "evRange": {"unit": "mile", "valid": true, "value": "49"},
                    "petRange": {"unit": "mile", "valid": true, "value": "62"}
                  }
                },
                "fuelLevel": {
                  "currentLevel": {"value": "30", "unit": "%"}
                }
              }
            }
        """.trimIndent()

        val json = JSONObject(payload)
        val rb = json.getJSONObject("responseBody")
        val vehicleInfo = rb.getJSONObject("evStatus").getJSONObject("vehicleInfo")

        val battery = vehicleInfo.optJSONObject("soc")?.optString("value")?.toDoubleOrNull()?.toInt()
        val evRange = vehicleInfo.optJSONObject("evRange")?.optString("value")?.toDoubleOrNull()?.toInt()
        val gasRange = vehicleInfo.optJSONObject("petRange")?.optString("value")?.toDoubleOrNull()?.toInt()

        assertEquals(100, battery)
        assertEquals(49, evRange)
        assertEquals(62, gasRange)
        assertEquals(111, (evRange ?: 0) + (gasRange ?: 0))
    }
}