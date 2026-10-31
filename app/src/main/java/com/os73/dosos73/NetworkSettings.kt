package com.os73.dosos73

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

internal object NetworkSettings {
    private fun prefs(context: Context) =
        context.getSharedPreferences("network", Context.MODE_PRIVATE)

    fun parallelConnections(context: Context) = prefs(context).getBoolean("parallel_connections", false)
    fun setParallelConnections(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("parallel_connections", value).commit())
    }

    fun allowCleartext(context: Context) = prefs(context).getBoolean("allow_cleartext", false)
    fun setAllowCleartext(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("allow_cleartext", value).commit())
    }
    fun requireSource(context: Context, url: String, stage: Stage = Stage.TRANSFER) {
        if (!url.startsWith("https:", true) && !allowCleartext(context))
            throw PipelineException(stage, FailureCode.CLEARTEXT_DISABLED)
    }

    fun wifiOnly(context: Context) = prefs(context).getBoolean("wifi_only", false)

    fun unmeteredOnly(context: Context) = prefs(context).getBoolean("unmetered_only", false)

    fun setWifiOnly(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("wifi_only", value).commit())
    }

    fun setUnmeteredOnly(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("unmetered_only", value).commit())
    }

    fun requireNetwork(context: Context, stage: Stage = Stage.TRANSFER): Network {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: throw PipelineException(stage, FailureCode.NETWORK_UNAVAILABLE)
        if (!permitted(context, network)) throw PipelineException(stage, FailureCode.NETWORK_UNAVAILABLE)
        return network
    }

    fun permitted(context: Context, network: Network? = context.getSystemService(ConnectivityManager::class.java).activeNetwork): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!wifiOnly(context) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) &&
            (!unmeteredOnly(context) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    fun client(context: Context, network: Network, stage: Stage = Stage.TRANSFER): okhttp3.OkHttpClient =
        DirectDownloader.secureClient { allowCleartext(context) }.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (!permitted(context, network)) throw java.net.UnknownHostException("Network policy unavailable")
                return Safety.checkedAddresses(network.getAllByName(hostname).toList())
                }
            })
            .addInterceptor { chain ->
                if (!permitted(context, network)) throw PipelineException(stage, FailureCode.NETWORK_UNAVAILABLE)
                chain.proceed(chain.request())
            }
            .build()

    fun changes(context: Context) = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(permitted(context))
                }

                override fun onLost(network: Network) {
                    trySend(permitted(context))
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    trySend(permitted(context))
                }
            }
        manager.registerDefaultNetworkCallback(callback)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(permitted(context))
            }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        trySend(permitted(context))
        awaitClose {
            manager.unregisterNetworkCallback(callback)
            prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    suspend fun awaitPermitted(context: Context) {
        changes(context).first { it }
    }
}
