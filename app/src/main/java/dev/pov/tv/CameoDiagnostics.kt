package dev.pov.tv

import android.content.Context
import android.os.Build
import dev.pov.tv.data.ProviderHub
import dev.pov.tv.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/** A deliberate allowlist: no media addresses, file names/paths, account names, credentials or headers. */
internal object CameoDiagnostics {
    fun report(context:Context):String {
        val store=SecureStore(context);val preferences=PlaybackPreferences(context)
        val result=JSONObject().put("application","Cameo").put("version",BuildConfig.VERSION_NAME)
            .put("package",context.packageName).put("createdUtc",java.time.Instant.now().toString())
            .put("androidApi",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("interface",if(CameoDevice.isTelevision(context))"television" else "mobile")
            .put("enginePreference",preferences.selectedEngine.name).put("selectedProvider",preferences.providerId)
            .put("embeddedVlc","3.7.7").put("media3","1.8.0")
            .put("activePlayback",InAppPlaybackSession.hasActive())
        result.put("providers",JSONArray().apply{ProviderHub.SPECS.forEach{spec->put(JSONObject().put("id",spec.id).put("connected",runCatching{store.has("${spec.id}.token")}.getOrDefault(false)))}})
        result.put("trackingConnections",JSONObject().apply{listOf("trakt","simkl","mdblist").forEach{key->put(key,runCatching{store.has("$key.token")||store.has("$key.api_key")||store.has("$key.access_token")}.getOrDefault(false))}})
        result.put("privacy","Only allowlisted runtime information and connection booleans. No report is uploaded automatically.")
        return result.toString(2)
    }
}
