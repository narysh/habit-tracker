package dev.habittracker.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable data class OAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Int = 3600,
)
