package com.os73.dosos73

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        return permitted(context, caps)
    }

    private fun permitted(context: Context, caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!wifiOnly(context) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) &&
            (!unmeteredOnly(context) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))

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

    fun changes(context: Context, watchedNetwork: Network? = null): kotlinx.coroutines.flow.Flow<Boolean> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val registrations = mutableListOf<NetworkChangeRegistration>()
        registrations += { changed ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changed(false) }
                override fun onLost(network: Network) { changed(false) }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    changed(false)
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            val unregister: () -> Unit = { manager.unregisterNetworkCallback(callback) }
            unregister
        }
        if (watchedNetwork != null) registrations += { changed ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    // The default callback stops reporting the old network after a handover.
                    if (network == watchedNetwork) changed(true)
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (network == watchedNetwork) changed(!permitted(context, capabilities))
                }
            }
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    // Builder defaults exclude VPNs; remove that filter on API 29 too.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            val unregister: () -> Unit = { manager.unregisterNetworkCallback(callback) }
            unregister
        }
        registrations += { changed ->
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                changed(false)
            }
            prefs(context).registerOnSharedPreferenceChangeListener(listener)
            val unregister: () -> Unit = { prefs(context).unregisterOnSharedPreferenceChangeListener(listener) }
            unregister
        }
        return observeNetworkPolicy({ permitted(context) }, registrations)
    }

    suspend fun awaitPermitted(context: Context) {
        changes(context).first { it }
    }
}
