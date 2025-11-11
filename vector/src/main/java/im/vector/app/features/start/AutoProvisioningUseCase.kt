/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.start

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.login.HomeServerConnectionConfigFactory
import im.vector.app.features.mdm.MdmData
import im.vector.app.features.mdm.MdmService
import org.json.JSONObject
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.Credentials
import timber.log.Timber
import java.net.URL
import javax.inject.Inject

const val PROVISION_DEVICE_ID = "blackberry-001"
const val DEFAULT_HOME_SERVER_URL = "https://kela-synapse-matrix.taildf47cb.ts.net"

data class ProvisionResponse(val accessToken: String, val userId: String, val deviceId: String)

class AutoProvisioningUseCase @Inject constructor(
        @ApplicationContext private val applicationContext: Context,
        private val authenticationService: AuthenticationService,
        private val activeSessionHolder: ActiveSessionHolder,
        private val homeServerConnectionConfigFactory: HomeServerConnectionConfigFactory,
        private val mdmService: MdmService,
        ) {
    suspend fun executeAutoProvisioning(): Boolean {
        return try {
            val defaultHomeserverUrl = mdmService.getData(MdmData.DefaultHomeserverUrl, DEFAULT_HOME_SERVER_URL)

            Timber.d("Starting automatic device provisioning...")
            val provisionResponse = provisionDevice("${defaultHomeserverUrl}:5552")
            Timber.d("Successfully provisioned device: userId=${provisionResponse.userId}")

            // Create session from provision token
            val homeServerConnectionConfig = homeServerConnectionConfigFactory.create(defaultHomeserverUrl)
                    ?: throw Throwable("Unable to create HomeServerConnectionConfig")

            val credentials = Credentials(
                    userId = provisionResponse.userId,
                    accessToken = provisionResponse.accessToken,
                    deviceId = provisionResponse.deviceId,
                    homeServer = defaultHomeserverUrl,
                    refreshToken = null,
            )

            Timber.d("Creating session from provision credentials...")
            val session = authenticationService.createSessionFromSso(
                    homeServerConnectionConfig = homeServerConnectionConfig,
                    credentials = credentials
            )

            activeSessionHolder.setActiveSession(session)
            Timber.d("Session created successfully from provisioning")
            true
        } catch (e: Exception) {
            Timber.w(e, "Automatic provisioning failed, will show login screen")
            false
        }
    }

    private fun provisionDevice(registrationURL: String): ProvisionResponse {
        val provisionUrl = "$registrationURL/provision"
        Timber.d("Provisioning URL: $provisionUrl")

        val url = java.net.URL(provisionUrl)
        val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10000 // 10 second timeout
            readTimeout = 10000
        }

        try {
            // Send request body
            val requestBody = """{"user_id":"$PROVISION_DEVICE_ID"}""".toByteArray()
            Timber.d("Sending provision request with device_id: $PROVISION_DEVICE_ID")
            connection.outputStream.use { os ->
                os.write(requestBody)
            }

            // Check response code
            val responseCode = connection.responseCode
            Timber.d("Provision response code: $responseCode")

            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.e("Provision failed with status $responseCode: $errorStream")
                throw Throwable("Provision failed with status $responseCode: $errorStream")
            }

            // Read response body
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            Timber.d("Provision response body: $responseBody")

            // Parse JSON response
            val jsonResponse = JSONObject(responseBody)
            val accessToken = jsonResponse.optString("access_token").takeIf { it.isNotEmpty() }
                    ?: throw Throwable("Missing access_token in provision response")
            val userId = jsonResponse.optString("user_id").takeIf { it.isNotEmpty() }
                    ?: throw Throwable("Missing user_id in provision response")
            val deviceId = jsonResponse.optString("device_id").takeIf { it.isNotEmpty() }
                    ?: throw Throwable("Missing device_id in provision response")

            Timber.d("Extracted credentials: userId=$userId, deviceId=$deviceId")
            return ProvisionResponse(accessToken = accessToken, userId = userId, deviceId = deviceId)
        } catch (e: Exception) {
            Timber.e(e, "Exception during device provisioning")
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
