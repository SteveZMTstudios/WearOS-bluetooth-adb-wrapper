package top.stevezmt.wearos.bluetoothadb.daemon

import java.net.NetworkInterface
import java.util.Collections

object NetworkAddressHelper {
    fun ipv4Addresses(): List<String> {
        return runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()).sortedBy { it.name }
            interfaces
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    Collections.list(networkInterface.inetAddresses)
                        .asSequence()
                        .filter { address -> !address.isLoopbackAddress && address.hostAddress?.contains(':') == false }
                        .map { address -> address.hostAddress.orEmpty() }
                }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
    }
}
