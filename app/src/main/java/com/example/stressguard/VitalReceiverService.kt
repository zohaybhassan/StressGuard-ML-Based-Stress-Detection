package com.example.stressguard

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class VitalReceiverService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (messageEvent.path == "/stress_vitals") {
            try {
                // 1. THE HACKER's VIEW: Print the raw, encrypted bytes as a string
                // We use Base64 just so the unreadable bytes can be printed to the screen
                val rawEncryptedString = android.util.Base64.encodeToString(messageEvent.data, android.util.Base64.NO_WRAP)
                android.util.Log.w("SECURITY_AUDIT", "INTERCEPTED PAYLOAD (AES-128 Encrypted): $rawEncryptedString")

                // 2. Decrypt the AES bytes back into our readable string
                val decryptedData = EncryptionUtil.decrypt(messageEvent.data)

                // 3. THE SYSTEM'S VIEW: Print the clean data
                android.util.Log.i("SECURITY_AUDIT", "SUCCESSFUL DECRYPTION (Plaintext): $decryptedData")

                // Now split the string as normal and broadcast to the UI
                val dataParts = decryptedData.split("|")
                if (dataParts.size >= 2) {
                    val broadcastIntent = Intent("STRESS_DATA_UPDATE")
                    broadcastIntent.setPackage(packageName)
                    broadcastIntent.putExtra("hr_value", dataParts[0])
                    broadcastIntent.putExtra("steps_value", dataParts[1])
                    sendBroadcast(broadcastIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}