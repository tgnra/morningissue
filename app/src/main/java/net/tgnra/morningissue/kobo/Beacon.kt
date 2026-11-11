package net.tgnra.morningissue.kobo

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The phone's address on the local network, and its subnet's broadcast address when it has one. */
data class LanAddress(val interfaceName: String, val address: Inet4Address, val broadcast: Inet4Address?) {
    /** "192.168.1.20". */
    val host: String get() = address.hostAddress.orEmpty()

    companion object {
        /**
         * Which of [candidates] a Kobo can reach: an IPv4 private address on Wi-Fi first, then
         * Ethernet, then the phone's own hotspot. Mobile data, VPN tunnels and loopback never.
         * Null when there is none, which the Sync screen reports as not on Wi-Fi.
         */
        fun choose(candidates: List<LanAddress>): LanAddress? =
            candidates.filter { it.address.isSiteLocalAddress && rank(it.interfaceName) != null }
                .minWithOrNull(compareBy({ rank(it.interfaceName) }, { it.interfaceName }))

        private fun rank(name: String): Int? = when {
            name.startsWith("wlan") -> 0
            name.startsWith("eth") -> 1
            name.startsWith("ap") || name.startsWith("swlan") -> 2
            else -> null
        }

        /**
         * The interfaces' addresses now. Reading interfaces needs no permission, so neither
         * `ACCESS_WIFI_STATE` nor `ACCESS_NETWORK_STATE` is asked for.
         */
        fun current(): LanAddress? {
            val found = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { network ->
                        network.interfaceAddresses.mapNotNull { address ->
                            (address.address as? Inet4Address)?.let { LanAddress(network.name, it, address.broadcast as? Inet4Address) }
                        }
                    }
            } catch (_: IOException) {
                emptyList()
            }
            return choose(found)
        }
    }
}

/**
 * The discovery beacon: once a second, while it runs, a UDP datagram of [payload] to
 * `255.255.255.255` and to the subnet's own broadcast address (some routers pass only one of
 * the two) on [port]. Sending a broadcast needs only `INTERNET`; it is receiving one that
 * would need a multicast lock.
 */
class BeaconSender(
    private val port: Int = KoboProtocol.BEACON_PORT,
    private val intervalMillis: Long = 1_000,
    private val address: () -> LanAddress? = LanAddress::current,
) {
    /** Sends until cancelled; [onAddress] hears the current address before every send. */
    suspend fun run(payload: () -> ByteArray, onAddress: (LanAddress?) -> Unit): Unit = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            while (true) {
                val lan = address()
                onAddress(lan)
                if (lan != null) {
                    val bytes = payload()
                    val targets = listOfNotNull(InetAddress.getByName("255.255.255.255"), lan.broadcast).distinct()
                    for (target in targets) {
                        // A network that refuses broadcasts is reported by the Kobo not finding the phone.
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, port)) }
                    }
                }
                delay(intervalMillis)
            }
        }
    }
}
