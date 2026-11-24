package io.github.swieoei.fiets

import android.annotation.TargetApi
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent

/**
 * Android meldt hier wanneer de gekoppelde fiets in de buurt komt of weggaat, ook als de app dicht is.
 * Fiets aan = in de buurt: de achtergrondservice start, verbindt en meet de rit.
 * (Oude en nieuwe Android-meldingen worden allebei afgehandeld; dubbel melden is geen probleem.)
 */
class BikePresenceService : CompanionDeviceService() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) = present(true)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) = present(false)

    @TargetApi(36) // wordt alleen door Android 16 en nieuwer aangeroepen
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> present(true)
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> present(false)
        }
    }

    private fun present(yes: Boolean) {
        Store(this).setPresent(yes, System.currentTimeMillis())
        if (yes || Live.running) RideService.send(this, if (yes) RideService.ACT_PRESENT else RideService.ACT_GONE)
    }
}
