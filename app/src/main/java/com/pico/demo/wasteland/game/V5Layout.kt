package com.pico.demo.wasteland.game

/** New landmarks sit off the main route. Supply points are outside their measured fit envelopes. */
object V5Layout {
    data class Landmark(val id: String, val model: String, val x: Float, val z: Float,
        val width: Float, val height: Float, val depth: Float, val yaw: Float = 0f)
    val landmarks = listOf(
        Landmark("SalvageKiosk", "SalvageKioskV5", -11f, -4f, 5.4f, 3.5f, 4.5f),
        Landmark("QuarryPump", "PumpStationV5", -24f, -45f, 4.5f, 2.8f, 3.2f),
        Landmark("WaterRadioTower", "WaterCommsTowerV5", 27f, -29f, 7f, 9f, 7f),
    )
    val obstacles = landmarks.map {
        V3Layout.Obstacle(it.id, it.x, it.z, it.width, it.depth, it.height)
    }
    data class Supply(val id: String, val label: String, val x: Float, val z: Float, val healing: Int)
    val supplies = listOf(
        Supply("salvage", "废品商亭补给", -11f, -0.4f, 35),
        Supply("pump", "泵站维修补给", -24f, -42.3f, 25),
        Supply("water", "水塔急救补给", 27f, -24f, 40),
    )
}
