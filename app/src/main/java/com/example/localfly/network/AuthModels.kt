package com.example.localfly.network

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val success: Boolean,
    val user: UserData?,
    val token: String?,
    val error: String? = null
)

data class VerifyTokenRequest(
    val token: String
)

data class UserData(
    val id: String,
    val username: String,
    val role: String? = "USER"
)