package com.example.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet6Address
import java.net.NetworkInterface

data class NetworkStatusSnapshot(
  val hasInternet: Boolean,
  val hasGlobalIpv6: Boolean,
)

interface NetworkCapabilityChecker {
  fun checkNetworkStatus(): NetworkStatusSnapshot
}

class AndroidNetworkCapabilityChecker(private val context: Context) : NetworkCapabilityChecker {
  override fun checkNetworkStatus(): NetworkStatusSnapshot {
    val cm =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = detectInterfaceIpv6())

    val activeNetwork = cm.activeNetwork
    if (activeNetwork == null) {
      return NetworkStatusSnapshot(hasInternet = false, hasGlobalIpv6 = false)
    }
    val caps = cm.getNetworkCapabilities(activeNetwork)
    val hasInternet =
      caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    val linkProps = cm.getLinkProperties(activeNetwork)
    val hasLinkIpv6 =
      linkProps?.linkAddresses?.any { linkAddr ->
        val addr = linkAddr.address
        addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
      } == true

    val hasGlobalIpv6 = hasLinkIpv6 || detectInterfaceIpv6()
    return NetworkStatusSnapshot(
      hasInternet = hasInternet || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
      hasGlobalIpv6 = hasGlobalIpv6,
    )
  }

  private fun detectInterfaceIpv6(): Boolean {
    return try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
      while (interfaces.hasMoreElements()) {
        val nif = interfaces.nextElement()
        if (!nif.isUp || nif.isLoopback) continue
        val addrs = nif.inetAddresses
        while (addrs.hasMoreElements()) {
          val addr = addrs.nextElement()
          if (
            addr is Inet6Address &&
              !addr.isLoopbackAddress &&
              !addr.isLinkLocalAddress &&
              !addr.isSiteLocalAddress
          ) {
            return true
          }
        }
      }
      false
    } catch (_: Exception) {
      false
    }
  }
}
