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

data class UserData(
    val id: String,
    val username: String
)