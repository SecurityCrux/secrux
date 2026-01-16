package com.secrux.service

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service

@Service
class PasswordHashService {
    private val encoder = BCryptPasswordEncoder()

    fun hash(rawPassword: String): String = encoder.encode(rawPassword)

    fun matches(rawPassword: String, passwordHash: String): Boolean =
        encoder.matches(rawPassword, passwordHash)
}

