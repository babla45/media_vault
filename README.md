# BibVault

BibVault is a secure, offline-first Android application designed to encrypt, store, and seamlessly play multiple media files (videos, audio, images) entirely from within a custom encrypted container (`.biv`). It features on-the-fly decryption with no temporary files ever written to disk, ensuring maximum privacy and security for your sensitive media.

## Core Features

- **Custom `.biv` Container:** Packages multiple media files into a single, highly secure, encrypted `.biv` file.
- **On-the-Fly Streaming Decryption:** Custom `EncryptedDataSource` intercepts ExoPlayer/Media3 read requests to decrypt video and audio chunks instantly in memory. *Media files are never extracted to persistent storage.*
- **True Random Access:** Employs an AES-256-CTR chunked encryption strategy with deterministic IVs. This allows the player to seek/skip to any point in a large 4K video instantly, without having to decrypt preceding data.
- **Robust Cryptography:** 
  - **AES-256-GCM** is used for encrypting the internal metadata/index file.
  - **AES-256-CTR** paired with HMAC-SHA256 ensures chunked data confidentiality and integrity.
  - PBKDF2 (100,000 iterations) derives the primary key from the user's password and a secure random salt.
- **Modern Material 3 UI:** Built 100% in Jetpack Compose featuring a dark-themed, premium aesthetic.
- **Security-First Architecture:** 
  - Auto-lock after 2 minutes of inactivity.
  - Applies `FLAG_SECURE` to block screenshots and screen recording.
  - Leverages Android's Storage Access Framework (SAF) so vaults can reside securely anywhere on the device.
- **Share Integration:** Intercepts `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents. You can select files directly from your gallery or file manager and share them to BibVault for instant encryption.

## Technical Implementation

### Container Structure
A single `.biv` file consists of:
1. **Header (60 bytes):** Magic bytes, Format Version, Salt (32 bytes), GCM IV (12 bytes), and Index Size.
2. **Encrypted Index (AES-GCM):** A JSON array of file entries detailing filenames, MIME types, offsets, sizes, and specific chunk variables (Base IV, chunk counts, HMAC).
3. **Encrypted Data Blocks (AES-CTR):** Sequential encrypted chunks of media files.

### Streaming Decryption Logic
The core hurdle with encrypted video is that media players require random access. If the user scrubs to 1:00:00, decrypting the entire file to reach that point is extremely slow and memory intensive.
BibVault solves this by carving the file into discrete 64KB chunks during encryption. Each chunk is encrypted using AES-CTR with a calculated IV (`BaseIV + ChunkIndex`). 
When ExoPlayer requests data at a specific byte offset, the `EncryptedDataSource`:
1. Calculates exactly which chunk(s) that byte offset falls into.
2. Reads only those encrypted chunks using standard `FileDescriptor` seeks.
3. Decrypts them instantly in memory using the deterministic IV.
4. Returns the requested bytes directly to ExoPlayer.

### Storage & Sharing
The app adheres to modern Android Scoped Storage best practices, operating purely on `content://` URIs and `FileDescriptors` without requiring intrusive `MANAGE_EXTERNAL_STORAGE` permissions. Dual-launcher patterns gracefully handle compatibility on specific OEM operating systems (like MIUI) that exhibit file-access quirks.

## Tech Stack
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose (Material 3)
- **Media Player:** AndroidX Media3 (ExoPlayer)
- **Storage:** Storage Access Framework (SAF), ContentResolver
- **Concurrency:** Kotlin Coroutines
- **Architecture:** MVVM (Model-View-ViewModel)

## Getting Started

1. Clone the repository and open it in Android Studio.
2. Build and run the app on an Android device (API 24+ recommended, tested heavily on API 33+).
3. Tap **Create Vault** to select videos/images and set a strong password.
4. From the **Vault Browser**, tap any item to instantly stream the encrypted media.

> **Note:** Because BibVault enforces `FLAG_SECURE`, you will not be able to take screenshots or screen recordings while the app is actively running.
