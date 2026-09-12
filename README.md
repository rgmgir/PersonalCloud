# Personal Cloud ☁️📱

**Personal Cloud** is a high-performance Android application designed to turn your phone into a blazing-fast local file server. It allows you to seamlessly share, upload, and download files between multiple Android devices over your local Wi-Fi network without relying on the internet, Bluetooth, or third-party cloud services.

## ✨ Features

- **🚀 Extreme Transfer Speeds:** Utilizes direct TCP/OkHttp streaming over local Wi-Fi, completely bypassing battery-throttled download managers and internet caps. Transfer files at up to 100+ MB/s!
- **🌐 The "My Network" Hub:** The Client tab acts as a permanent network hub. Connect up to 3 separate Server devices simultaneously and instantly switch between browsing their files without ever needing to disconnect!
- **🔒 Secure Pairing & Authentication:** Protect your files using a custom PIN. Devices pair securely and exchange cryptographic tokens, ensuring only authorized clients can access your filesystem or stream media.
- **🖼️ Dynamic Media Thumbnails:** Automatically generates and streams real visual thumbnails for Images, Videos, and APKs securely over the network.
- **🎬 Seamless Video Streaming:** Stream large `.mp4`, `.mkv`, and `.webm` video files directly to third-party media players (like MX Player) using authenticated secure intents without needing to download them first.
- **♾️ Unlimited Chunked Uploads:** Engineered to handle massive Gigabyte-sized uploads without crashing or timing out, utilizing advanced chunked transfer encoding.
- **🤖 Always-On Auto-Start:** The server runs robustly in a foreground service and automatically boots up the moment your Android device restarts, ensuring your files are always accessible when you need them.
- **🎨 Beautiful Material 3 UI:** A vibrant, polished interface featuring liquid green progress animations and real-time interactive transfer dialogs.

## 🛠️ Installation

1. Go to the [Releases](https://github.com/Dastgir-Siddiq/PersonalCloud/releases) page of this repository.
2. Download the latest `PersonalCloud.apk`.
3. Install the APK on **at least two** Android devices (one to act as the Server, and one as the Client).

## 📖 How to Use

### Setting up the Server Device
1. Open the **Personal Cloud** app and grant it the required "All Files Access" storage permission.
2. Navigate to the **Server** tab (using the bottom navigation bar).
3. The server will automatically start and share your main device storage. You can configure up to 3 different specific storage paths if you prefer.
4. You can also optionally set a **4-to-8 digit PIN** to secure your server. 
5. Leave the app running; the server operates as a foreground service and will even auto-start when you reboot the device!

### Connecting your Client Hub
1. Open the app on your second phone (it opens on the **Client** tab by default).
2. You will see your **"My Network / CONNECTED DEVICES"** dashboard. Tap **Add Device**.
3. Enter the **IP Address** displayed on your Server device, provide the PIN (if you set one), and give the device a friendly name (like "Living Room PC").
4. The device is now permanently saved to your network! Tap it to instantly browse its files.

### Transferring Files
- **To Download:** Tap any file to open/play it instantly over the network. Long-press files or folders to enter selection mode, then tap the **Download** button to save them directly to your phone.
- **To Upload:** Tap the floating **Upload** button at the bottom right to browse your local device and send files directly to the server's current folder.
- **Background Transfers:** You can hit **Hide** on any active transfer to send it to the background. Tap the Cloud icon at the top right of the screen to bring the transfer dialog back up at any time!

## 🧑‍💻 Technical Architecture
- **Server:** Built natively in Kotlin using the asynchronous **Ktor Netty** engine running in a Foreground Service. Supports concurrent multi-client connections.
- **Client:** Built beautifully with **Jetpack Compose** (Material 3), powered by **OkHttp** for proxy-bypassing authenticated network requests and **Coil** for image rendering.

---
*Developed by Dastgir Siddiq*
