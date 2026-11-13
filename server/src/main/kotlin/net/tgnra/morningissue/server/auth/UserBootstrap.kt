package net.tgnra.morningissue.server.auth

import androidx.room3.withWriteTransaction
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.UUID
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.UserEntity
import net.tgnra.morningissue.server.service.ApiFailure
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Accounts from the config (section 7.2). For each configured name without a row: a user with a
 * random `db_id` and a generated password that must be changed, logged at WARN and written to
 * `<dataDir>/initial-passwords/<name>.txt` (mode 0600). Names in the database but not in the
 * config are disabled; listing a name again re-enables it.
 */
class UserBootstrap(
    private val db: ServerDatabase,
    private val passwords: Passwords,
    private val initialPasswordsDir: File,
    private val clock: Clock,
    private val log: Logger = LoggerFactory.getLogger(UserBootstrap::class.java),
) {
    private val accounts = db.accountDao()

    suspend fun bootstrap(usernames: List<String>) {
        val wanted = usernames.toSet()
        val existing = accounts.allUsers().associateBy { it.username }
        for (name in usernames) {
            val user = existing[name]
            when {
                user == null -> create(name)
                user.disabled -> {
                    accounts.setDisabled(user.id, false)
                    log.info("Re-enabled user \"{}\"", name)
                }
            }
        }
        for (user in existing.values) {
            if (user.username !in wanted && !user.disabled) {
                accounts.setDisabled(user.id, true)
                log.warn("Disabled user \"{}\": no longer in the config. Their data is kept; list them again to re-enable.", user.username)
            }
        }
    }

    /**
     * The CLI's `reset-password <user>`: a new generated password, as on first start, and every
     * phone signed out. Returns the password.
     */
    suspend fun resetPassword(username: String): String {
        val user = accounts.userByName(username) ?: throw ApiFailure.notFound("No user \"$username\"")
        val password = passwords.generate()
        val hash = passwords.hash(password)
        db.withWriteTransaction {
            accounts.setPassword(user.id, hash, mustChange = true)
            db.deviceDao().revokePhones(user.id, clock.instant())
        }
        writeInitialPassword(username, password)
        log.warn("Reset the password of user \"{}\" to \"{}\". Log in from the app and change it.", username, password)
        return password
    }

    /** Deletes the initial password file, once the user has chosen their own. */
    fun forgetInitialPassword(username: String) {
        File(initialPasswordsDir, "$username.txt").delete()
    }

    private suspend fun create(name: String) {
        val password = passwords.generate()
        accounts.insertUser(
            UserEntity(
                username = name,
                passwordHash = passwords.hash(password),
                mustChangePassword = true,
                dbId = UUID.randomUUID().toString(),
                createdAt = clock.instant(),
            ),
        )
        writeInitialPassword(name, password)
        log.warn("Created user \"{}\" with password \"{}\". Log in from the app and change it.", name, password)
    }

    /** Writes the file with mode 0600 from the start (a temporary file renamed into place), in a 0700 directory. */
    private fun writeInitialPassword(name: String, password: String) {
        val dir = initialPasswordsDir.toPath()
        val posix = runCatching { Files.getFileStore(dir.parent ?: dir.toAbsolutePath().parent).supportsFileAttributeView("posix") }.getOrDefault(true)
        if (!Files.isDirectory(dir)) {
            if (posix) {
                try {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                } catch (_: FileAlreadyExistsException) {
                }
            } else {
                Files.createDirectories(dir)
            }
        }
        val target = dir.resolve("$name.txt")
        val tmp = if (posix) {
            Files.createTempFile(dir, ".$name", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(dir, ".$name", ".tmp")
        }
        Files.writeString(tmp, password + "\n")
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
