package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Account
import com.sikorokoro44.messengerpro.model.Session

/**
 * Account registration, login, session and recovery logic. Pure Kotlin so it can
 * be exercised by JVM unit tests without an Android device.
 */
class AuthEngine(private val clock: () -> Long = System::currentTimeMillis) {

    data class Result<out T>(val ok: Boolean, val value: T? = null, val error: String? = null) {
        companion object {
            fun <T> success(value: T): Result<T> = Result(true, value, null)
            fun failure(error: String): Result<Nothing> = Result(false, null, error)
        }
    }

    data class Verification(val code: String, val expiresAt: Long)

    private val accountsByUsername = LinkedHashMap<String, Account>()
    private val accountsById = LinkedHashMap<String, Account>()
    private val sessions = LinkedHashMap<String, Session>()
    private val verifications = LinkedHashMap<String, Verification>()
    private val failedAttempts = LinkedHashMap<String, MutableList<Long>>()

    private companion object {
        val USERNAME = Regex("^[a-zA-Z0-9._]{3,24}$")
        const val MAX_FAILED = 5
        const val LOCK_WINDOW_MS = 60_000L
        const val VERIFICATION_TTL_MS = 5 * 60_000L
    }

    fun accountCount(): Int = accountsById.size

    fun findByUsername(username: String): Account? = accountsByUsername[username.lowercase()]

    fun findById(id: String): Account? = accountsById[id]

    fun usernameAvailable(username: String): Boolean {
        val normalised = username.lowercase()
        return USERNAME.matches(username) && !accountsByUsername.containsKey(normalised)
    }

    fun register(
        username: String,
        displayName: String,
        password: String,
        phone: String? = null,
        email: String? = null
    ): Result<Account> {
        if (!USERNAME.matches(username)) {
            return Result.failure("Username must be 3-24 characters: letters, digits, dot or underscore")
        }
        if (accountsByUsername.containsKey(username.lowercase())) {
            return Result.failure("An account with that username already exists")
        }
        if (password.length < 8) {
            return Result.failure("Password must be at least 8 characters")
        }
        if (displayName.isBlank()) {
            return Result.failure("Display name is required")
        }
        val salt = Hashing.randomHex(16)
        val account = Account(
            id = "acct-" + Hashing.randomHex(8),
            username = username,
            displayName = displayName.trim(),
            passwordHash = Hashing.hashPassword(password, salt),
            salt = salt,
            createdAt = clock(),
            phone = phone,
            email = email
        )
        accountsByUsername[username.lowercase()] = account
        accountsById[account.id] = account
        return Result.success(account)
    }

    fun beginVerification(accountId: String): Result<Verification> {
        if (!accountsById.containsKey(accountId)) return Result.failure("No such account")
        val verification = Verification(
            code = (100000 + (0..899999).random()).toString(),
            expiresAt = clock() + VERIFICATION_TTL_MS
        )
        verifications[accountId] = verification
        return Result.success(verification)
    }

    fun verify(accountId: String, code: String): Result<Boolean> {
        val stored = verifications[accountId] ?: return Result.failure("No verification requested")
        if (clock() > stored.expiresAt) return Result.failure("Verification code expired")
        if (!Hashing.constantTimeEquals(stored.code, code)) return Result.failure("Incorrect verification code")
        verifications.remove(accountId)
        return Result.success(true)
    }

    fun login(username: String, password: String): Result<Session> {
        val normalised = username.lowercase()
        val now = clock()
        val attempts = failedAttempts.getOrPut(normalised) { mutableListOf() }
        attempts.removeAll { now - it > LOCK_WINDOW_MS }
        if (attempts.size >= MAX_FAILED) {
            return Result.failure("Too many failed attempts; try again in a minute")
        }
        val account = accountsByUsername[normalised]
        if (account == null) {
            attempts.add(now)
            return Result.failure("Invalid username or password")
        }
        val candidate = Hashing.hashPassword(password, account.salt)
        if (!Hashing.constantTimeEquals(candidate, account.passwordHash)) {
            attempts.add(now)
            return Result.failure("Invalid username or password")
        }
        attempts.clear()
        val session = Session(
            token = Hashing.randomHex(24),
            accountId = account.id,
            createdAt = now
        )
        sessions[session.token] = session
        return Result.success(session)
    }

    fun session(token: String): Session? = sessions[token]?.takeUnless { it.revoked }

    fun logout(token: String): Boolean {
        val session = sessions[token] ?: return false
        sessions[token] = session.copy(revoked = true)
        return true
    }

    fun revokeAllSessions(accountId: String) {
        sessions.replaceAll { _, s -> if (s.accountId == accountId) s.copy(revoked = true) else s }
    }

    fun changePassword(accountId: String, oldPassword: String, newPassword: String): Result<Boolean> {
        val account = accountsById[accountId] ?: return Result.failure("No such account")
        if (!Hashing.constantTimeEquals(Hashing.hashPassword(oldPassword, account.salt), account.passwordHash)) {
            return Result.failure("Current password is incorrect")
        }
        if (newPassword.length < 8) return Result.failure("Password must be at least 8 characters")
        val salt = Hashing.randomHex(16)
        val updated = account.copy(salt = salt, passwordHash = Hashing.hashPassword(newPassword, salt))
        accountsById[accountId] = updated
        accountsByUsername[account.username.lowercase()] = updated
        revokeAllSessions(accountId)
        return Result.success(true)
    }

    fun resetPassword(accountId: String, verificationCode: String, newPassword: String): Result<Boolean> {
        val verified = verify(accountId, verificationCode)
        if (!verified.ok) return Result.failure(verified.error ?: "Verification failed")
        if (newPassword.length < 8) return Result.failure("Password must be at least 8 characters")
        val account = accountsById[accountId] ?: return Result.failure("No such account")
        val salt = Hashing.randomHex(16)
        val updated = account.copy(salt = salt, passwordHash = Hashing.hashPassword(newPassword, salt))
        accountsById[accountId] = updated
        accountsByUsername[account.username.lowercase()] = updated
        revokeAllSessions(accountId)
        return Result.success(true)
    }

    fun requestDeletion(accountId: String): Result<String> {
        val account = accountsById[accountId] ?: return Result.failure("No such account")
        return Result.success("delete-token-" + Hashing.sha256(account.id).take(12))
    }

    fun confirmDeletion(accountId: String, token: String): Result<Boolean> {
        val account = accountsById[accountId] ?: return Result.failure("No such account")
        if (token != "delete-token-" + Hashing.sha256(account.id).take(12)) {
            return Result.failure("Invalid deletion confirmation")
        }
        accountsById.remove(accountId)
        accountsByUsername.remove(account.username.lowercase())
        revokeAllSessions(accountId)
        return Result.success(true)
    }

    fun exportAccountData(accountId: String): Result<String> {
        val account = accountsById[accountId] ?: return Result.failure("No such account")
        return Result.success(
            "username=${account.username}\ndisplayName=${account.displayName}\n" +
                "createdAt=${account.createdAt}\nstatus=${account.status}\n"
        )
    }
    fun exportAccounts(): List<Account> = accountsById.values.toList()

    fun importAccount(account: Account) {
        accountsById[account.id] = account
        accountsByUsername[account.username.lowercase()] = account
    }
}
