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
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.Credentials
import timber.log.Timber
import javax.inject.Inject

const val DEFAULT_HOME_SERVER_URL = "https://kela-synapse-matrix.taildf47cb.ts.net"
const val MAX_PROVISIONING_RETRIES = 3
const val PROVISIONING_RETRY_DELAY_MS = 2000L

data class ProvisionResponse(val accessToken: String, val userId: String, val deviceId: String)

sealed interface ProvisioningResult {
    data class Success(val credentialsSetup: Boolean) : ProvisioningResult
    data class Failure(val error: String, val isRetryable: Boolean) : ProvisioningResult
}

class AutoProvisioningUseCase @Inject constructor(
        @ApplicationContext private val applicationContext: Context,
        private val authenticationService: AuthenticationService,
        private val activeSessionHolder: ActiveSessionHolder,
        private val homeServerConnectionConfigFactory: HomeServerConnectionConfigFactory,
        private val mdmService: MdmService,
        private val vectorPreferences: VectorPreferences,
        ) {
    suspend fun executeAutoProvisioning(): ProvisioningResult {
        return try {
            val defaultHomeserverUrl = mdmService.getData(MdmData.DefaultHomeserverUrl, DEFAULT_HOME_SERVER_URL)

            // Get the Tailscale device name from MDM config (required for provisioning)
            Timber.d("Attempting to retrieve Tailscale device name from MDM config...")
            val deviceId = mdmService.getData(MdmData.TailscaleDeviceName)
            if (deviceId == null) {
                Timber.e("Tailscale device name not found in MDM restrictions")
                throw Exception("Tailscale device name not configured. Please ensure the device owner app has set the Tailscale hostname.")
            }
            Timber.d("Using device ID for provisioning: $deviceId")

            Timber.d("Starting automatic device provisioning with max retries: $MAX_PROVISIONING_RETRIES")

            var lastError: Exception? = null
            var success = false

            for (attemptNumber in 0 until MAX_PROVISIONING_RETRIES) {
                if (success) break // Exit loop if already successful

                try {
                    Timber.d("Provisioning attempt ${attemptNumber + 1}/$MAX_PROVISIONING_RETRIES")
                    val provisionResponse = provisionDevice("${defaultHomeserverUrl}:5552", deviceId)
                    Timber.d("Successfully provisioned device: userId=${provisionResponse.userId}")

                    // Create session from provision token
                    val homeServerConnectionConfig = homeServerConnectionConfigFactory.create(defaultHomeserverUrl)
                            ?: throw Exception("Unable to create HomeServerConnectionConfig")

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

                    // Automatically set to simplified (easy) mode for newly provisioned devices
                    vectorPreferences.setSimplifiedMode(true)
                    Timber.d("Automatically enabled easy mode for provisioned device")

                    success = true
                    lastError = null
                } catch (e: Exception) {
                    lastError = e
                    val isLastAttempt = attemptNumber == MAX_PROVISIONING_RETRIES - 1
                    if (isLastAttempt) {
                        Timber.e(e, "Automatic provisioning failed after $MAX_PROVISIONING_RETRIES attempts")
                    } else {
                        Timber.w(e, "Provisioning attempt ${attemptNumber + 1} failed, retrying in ${PROVISIONING_RETRY_DELAY_MS}ms...")
                        delay(PROVISIONING_RETRY_DELAY_MS)
                    }
                }
            }

            // Check if we succeeded
            if (success && lastError == null) {
                ProvisioningResult.Success(credentialsSetup = true)
            } else {
                Timber.e("Provisioning failed: $lastError")
                val errorMessage = lastError?.message ?: "Unknown error occurred during provisioning"
                val isRetryable = isRetryableError(lastError ?: Exception())
                ProvisioningResult.Failure(error = errorMessage, isRetryable = isRetryable)
            }
        } catch (e: Exception) {
            // Catch any unexpected errors outside the retry loop
            Timber.e(e, "Unexpected error in executeAutoProvisioning")
            ProvisioningResult.Failure(
                error = e.message ?: "Unexpected error occurred during provisioning",
                isRetryable = isRetryableError(e)
            )
        }
    }

    private fun isRetryableError(e: Exception): Boolean {
        return when {
            e.message?.contains("not configured") == true -> false // Configuration errors are not retryable
            e is java.net.ConnectException -> true
            e is java.net.SocketTimeoutException -> true
            e is java.io.IOException -> true
            e.message?.contains("503") == true -> true // Service Unavailable
            e.message?.contains("502") == true -> true // Bad Gateway
            e.message?.contains("timeout") == true -> true
            e.message?.contains("Connection") == true -> true
            else -> false
        }
    }

    private fun provisionDevice(registrationURL: String, deviceName: String): ProvisionResponse {
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
            val requestBody = """{"user_id":"$deviceName"}""".toByteArray()
            Timber.d("Sending provision request with device_id: $deviceName")
            connection.outputStream.use { os ->
                os.write(requestBody)
            }

            // Check response code
            val responseCode = connection.responseCode
            Timber.d("Provision response code: $responseCode")

            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.e("Provision failed with status $responseCode: $errorStream")
                throw Exception("Provision failed with status $responseCode: $errorStream")
            }

            // Read response body
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            Timber.d("Provision response body: $responseBody")

            // Parse JSON response
            val jsonResponse = JSONObject(responseBody)
            val accessToken = jsonResponse.optString("access_token").takeIf { it.isNotEmpty() }
                    ?: throw Exception("Missing access_token in provision response")
            val userId = jsonResponse.optString("user_id").takeIf { it.isNotEmpty() }
                    ?: throw Exception("Missing user_id in provision response")
            val deviceId = jsonResponse.optString("device_id").takeIf { it.isNotEmpty() }
                    ?: throw Exception("Missing device_id in provision response")

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
