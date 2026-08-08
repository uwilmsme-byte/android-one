package com.seoulprime.huboneagent

import android.net.Uri
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.CompletionService
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/** Android port of the existing HUBONE desktop ServerDiscovery rules. */
object ServerDiscovery {
    private val preferredPorts = intArrayOf(8001, 8000)

    fun discover(savedUrl: String): String? {
        val normalizedSaved = normalize(savedUrl)
        if (normalizedSaved != null && probe(normalizedSaved) != null) return normalizedSaved

        val candidates = buildCandidates(savedUrl)
            .filterNot { it.equals(normalizedSaved, ignoreCase = true) }
        if (candidates.isEmpty()) return null

        val executor = Executors.newFixedThreadPool(16)
        val completion: CompletionService<String?> = ExecutorCompletionService(executor)
        val futures = candidates.map { candidate ->
            completion.submit(Callable<String?> { if (probe(candidate) != null) candidate else null })
        }
        try {
            repeat(futures.size) {
                val found = completion.take().get()
                if (found != null) return found
            }
        } catch (_: Exception) {
            // A failed candidate must not terminate the Agent.
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
        return null
    }

    private fun buildCandidates(savedUrl: String): List<String> {
        val seen = linkedSetOf<String>()
        fun add(url: String?) { normalize(url)?.let { seen.add(it) } }
        add(savedUrl)
        val savedPort = Uri.parse(savedUrl).port.takeIf { it == 8001 || it == 8000 }
        val ports = buildList {
            savedPort?.let { add(it) }
            preferredPorts.forEach { if (!contains(it)) add(it) }
        }
        ports.forEach { port ->
            add(buildUrl("http", "127.0.0.1", port))
            add(buildUrl("http", "localhost", port))
        }
        privateIpv4Addresses().forEach { address ->
            val octets = address.split('.')
            if (octets.size != 4) return@forEach
            val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
            for (host in 1..254) {
                ports.forEach { port ->
                    add(buildUrl("http", "$prefix.$host", port))
                }
            }
        }
        return seen.toList()
    }

    private fun probe(baseUrl: String): Boolean {
        val connection = try {
            URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection
        } catch (_: Exception) { return false }
        return try {
            connection.connectTimeout = 1_200
            connection.readTimeout = 1_200
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .contains("macai-deskchat")
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun privateIpv4Addresses(): List<String> {
        val result = linkedSetOf<String>()
        val interfaces = try { Collections.list(NetworkInterface.getNetworkInterfaces()) } catch (_: Exception) { emptyList() }
        interfaces.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .forEach { networkInterface ->
                Collections.list(networkInterface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress ?: "" }
                    .filter { isPrivateIpv4(it) }
                    .forEach { result.add(it) }
            }
        return result.toList()
    }

    private fun isPrivateIpv4(address: String): Boolean {
        val p = address.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    private fun normalize(raw: String?): String? {
        val uri = runCatching { Uri.parse(raw?.trim()?.trimEnd('/')) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        return if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }

    private fun buildUrl(scheme: String, host: String, port: Int): String {
        return "$scheme://$host:$port"
    }
}
