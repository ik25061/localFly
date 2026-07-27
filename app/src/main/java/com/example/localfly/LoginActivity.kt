package com.example.localfly

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.localfly.network.LoginRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import kotlinx.coroutines.launch
import java.io.IOException
import android.content.Intent

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)

        // Si ya hay sesión guardada, saltar directo a la app
        if (sessionManager.isLoggedIn()) {
            goToMain()
            return
        }

        btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()

        hideError()

        if (username.length < 3 || password.length < 3) {
            showError("Usuario y contraseña deben tener al menos 3 caracteres")
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.login(LoginRequest(username, password))

                if (response.isSuccessful && response.body()?.success == true) {
                    val body = response.body()!!
                    val token = body.token
                    val user = body.user
                    if (token != null && user != null) {
                        sessionManager.saveSession(token, user.id, user.username)
                        goToMain()
                    } else {
                        showError("Respuesta inesperada del servidor")
                    }
                } else {
                    val errorMsg = response.errorBody()?.string()
                    showError(parseErrorMessage(errorMsg) ?: "Usuario o contraseña incorrectos")
                }
            } catch (e: IOException) {
                showError("No se pudo conectar con el servidor. Revisa la IP y que mirepo esté corriendo.")
            } catch (e: Exception) {
                showError("Error inesperado: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun parseErrorMessage(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val regex = "\"error\"\\s*:\\s*\"(.*?)\"".toRegex()
            regex.find(rawJson)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        tvError.visibility = View.GONE
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}