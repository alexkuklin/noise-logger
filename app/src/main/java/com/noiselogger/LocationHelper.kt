package com.noiselogger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val timestamp: Long
) {
    fun toHeaderString(): String {
        return "# Location: lat=${"%.6f".format(latitude)}, lon=${"%.6f".format(longitude)}, alt=${"%.1f".format(altitude)}m, accuracy=${"%.1f".format(accuracy)}m"
    }

    fun toCsvString(): String {
        return "${"%.6f".format(latitude)},${"%.6f".format(longitude)},${"%.1f".format(altitude)}"
    }
}

class LocationHelper(private val context: Context) {

    private var locationManager: LocationManager? = null
    private var currentLocation: LocationData? = null
    private var locationListener: LocationListener? = null

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getCurrentLocation(callback: (LocationData?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try to get last known location first
        try {
            val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (lastKnown != null && isLocationFresh(lastKnown)) {
                currentLocation = locationToData(lastKnown)
                callback(currentLocation)
                return
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        // Request fresh location
        requestFreshLocation(callback)
    }

    private fun requestFreshLocation(callback: (LocationData?) -> Unit) {
        try {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = locationToData(location)
                    callback(currentLocation)
                    stopLocationUpdates()
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            // Try GPS first, then network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var requested = false

            for (provider in providers) {
                if (locationManager?.isProviderEnabled(provider) == true) {
                    locationManager?.requestSingleUpdate(
                        provider,
                        locationListener!!,
                        Looper.getMainLooper()
                    )
                    requested = true
                    break
                }
            }

            if (!requested) {
                callback(null)
            }

            // Timeout after 10 seconds
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (currentLocation == null) {
                    stopLocationUpdates()
                    callback(null)
                }
            }, 10000)

        } catch (e: SecurityException) {
            e.printStackTrace()
            callback(null)
        }
    }

    private fun stopLocationUpdates() {
        locationListener?.let {
            try {
                locationManager?.removeUpdates(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        locationListener = null
    }

    private fun locationToData(location: Location): LocationData {
        return LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
            timestamp = location.time
        )
    }

    private fun isLocationFresh(location: Location): Boolean {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return location.time > fiveMinutesAgo
    }

    fun getLastLocation(): LocationData? = currentLocation
}
