package com.feathertv.launcher.wiz

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Low-level UDP communication layer for WiZ devices (port 38899).
 */
object WizProtocol {

    const val WIZ_PORT = 38899
    const val BROADCAST_ADDR = "255.255.255.255"

    fun buildSetRgb(r: Int, g: Int, b: Int, dimming: Int): String {
        return """{"method":"setPilot","params":{"state":true,"r":$r,"g":$g,"b":$b,"dimming":$dimming}}"""
    }

    fun buildSetKelvin(temp: Int, dimming: Int): String {
        return """{"method":"setPilot","params":{"state":true,"temp":$temp,"dimming":$dimming}}"""
    }

    fun buildSetPower(state: Boolean): String {
        return """{"method":"setPilot","params":{"state":$state}}"""
    }

    fun buildGetConfig(): String {
        return """{"method":"getSystemConfig","params":{}}"""
    }

    fun sendUdp(ip: String, jsonPayload: String, repeatCount: Int = 1, repeatDelayMs: Long = 25) {
        DatagramSocket().use { socket ->
            val data = jsonPayload.toByteArray(Charsets.UTF_8)
            val targetAddr = InetAddress.getByName(ip)
            val packet = DatagramPacket(data, data.size, targetAddr, WIZ_PORT)
            for (i in 0 until repeatCount) {
                socket.send(packet)
                if (i < repeatCount - 1 && repeatDelayMs > 0) {
                    try {
                        Thread.sleep(repeatDelayMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    fun broadcastDiscovery(targetMacClean: String, timeoutMs: Int = 1500): String? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            val payload = buildGetConfig().toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName(BROADCAST_ADDR)
            val packet = DatagramPacket(payload, payload.size, broadcastAddr, WIZ_PORT)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = (System.currentTimeMillis() - startTime).toInt()
                val remaining = timeoutMs - elapsed
                if (remaining <= 0) break

                try {
                    socket.soTimeout = remaining
                    val respPacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(respPacket)

                    val jsonStr = String(respPacket.data, 0, respPacket.length, Charsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    val result = json.optJSONObject("result")
                    val mac = result?.optString("mac")?.replace(":", "")?.lowercase()

                    if (mac == targetMacClean.lowercase()) {
                        return respPacket.address.hostAddress
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    // Non-WiZ or unexpected packet received: continue listening
                    continue
                }
            }
        }
        return null
    }
}
