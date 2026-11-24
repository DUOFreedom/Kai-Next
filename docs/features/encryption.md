# Encryption & Secure Storage

**Last verified:** 2026-11-18

All app settings — including sensitive ones such as API keys and email passwords — are stored in a single encrypted settings store backed by the Android Keystore.

## Sensitive Data

The following data is stored in secure settings:
- Service API keys (per-instance)
- Email account passwords
- Splinterlands posting key
- MCP server request headers (e.g. authorization tokens), stored with the server list
- Encryption key for legacy conversation migration

Conversation history itself is not in the secure settings store: it lives in a local SQLite database in app-private storage, unencrypted at the application layer and protected by the OS app sandbox and any device full-disk/file-based encryption. See [chat.md](chat.md) for the storage layout and migration chain.

## Platform Implementation

### Android
- **Mechanism:** `EncryptedSharedPreferences` via `dev.spght:encryptedprefs-ktx` (community fork of deprecated `androidx.security:security-crypto`)
- **Key encryption:** AES-256-SIV
- **Value encryption:** AES-256-GCM
- **Key management:** Android Keystore (`MasterKey` with `AES256_GCM` scheme)
- **Size limit:** ~2 MB per value (SharedPreferences limit)
- **File location:** App-private `kai_secure_prefs` SharedPreferences
- **Restore recovery:** If the encrypted file cannot be opened (typically after Android Auto Backup restores it onto a device whose hardware-bound Keystore key did not transfer), the file is deleted and a fresh empty store is created — settings and keys must be re-entered.
- **Legacy migration:** On first run, values from the old plaintext `com.kainext.duo_preferences` (selected service, model ids, API keys, base URL, app-open counter) are copied into the encrypted store once. The legacy file name carries the applicationId, so the app rename to `com.kainext.duo` produced a separate install with its own sandbox — in practice there is nothing to copy on a fresh install, and the read is harmless when the file is absent.

## Legacy Conversation Storage

Prior to the current database-backed storage, conversations were stored in an encrypted file (`conversations.enc`) using XOR encryption with a 32-byte random key, and later as a JSON blob in secure settings. Both are migrated automatically on first load:

1. Read `conversations.enc` from the app files directory
2. Decrypt using XOR with the key from `encryption_key` in settings
3. Persist to the current conversation storage
4. Delete the legacy file

A conversation blob found in secure settings is likewise imported into the database and removed from settings on first load.

The XOR encryption key is retained in settings for any devices that haven't migrated yet.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../Platform.kt` | `expect fun createSecureSettings()` declaration |
| `composeApp/src/androidMain/.../Platform.android.kt` | Android EncryptedSharedPreferences setup, restore recovery, and the legacy plaintext store |
| `composeApp/src/commonMain/.../data/ConversationStorage.kt` | Legacy XOR migration logic |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Settings access layer for all app data |
| `composeApp/src/commonMain/.../data/AppSettingsMigrations.kt` | One-time copies from the legacy plaintext settings store |
| `composeApp/src/commonMain/.../data/ConversationPersistence.kt` | Database vs. settings-blob conversation storage; imports a settings blob into the database |
