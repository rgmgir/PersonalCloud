# Personal Cloud ☁️📱

**Personal Cloud** is a high-performance Android application designed to turn your phone into a blazing-fast local file server. It allows you to seamlessly share, upload, and download files between Android devices over your local Wi-Fi network without relying on the internet, Bluetooth, or third-party cloud services.

## ✨ Features

- **🚀 Extreme Transfer Speeds:** Utilizes direct TCP/OkHttp streaming over local Wi-Fi, completely bypassing battery-throttled download managers and internet caps. Transfer files at up to 100+ MB/s!
- **🎨 Beautiful Material 3 UI:** A vibrant, polished interface featuring liquid green progress animations and real-time interactive transfer dialogs.
- **🖼️ Dynamic Thumbnails:** Automatically generates and displays real visual thumbnails for Images and Videos, alongside specific Material icons for Code, PDFs, and Documents.
- **📁 Multi-File & Folder Support:** Select multiple files or entire folders to download at once; the server will recursively zip and stream them to your client device on the fly.
- **♾️ Unlimited Chunked Uploads:** Engineered to handle massive Gigabyte-sized uploads without crashing or timing out, utilizing advanced chunked transfer encoding.
- **⚙️ Multi-Root Server Management:** Configure and share multiple storage paths simultaneously from the server device.
- **📊 Advanced Transfer Analytics:** Monitor your active uploads and downloads with precise MB/s speedometers, time remaining calculators, and percentage tracking.

## 🛠️ Installation

1. Go to the [Releases](https://github.com/rgmgir/PersonalCloud/releases) page of this repository.
2. Download the latest `PersonalCloud.apk`.
3. Install the APK on **at least two** Android devices (one to act as the Server, and one as the Client).

## 📖 How to Use

### Setting up the Server Device
1. Open the **Personal Cloud** app.
2. Navigate to the **Server Mode** tab (using the bottom navigation bar).
3. The server will automatically start on your default storage path. You can add more paths by clicking the **Add Path** button.
4. Look at the top of the screen and note down the **IP Address** (e.g., `192.168.0.110`). Keep the app open or running in the background.

### Connecting the Client Device
1. Open the app on your second phone (it opens in **Client Mode** by default).
2. Tap the **Settings (Ethernet)** icon at the top right corner.
3. Enter the **IP Address** displayed on your Server device and tap **Connect**.
4. You will instantly see all the files and folders hosted by the server!

### Transferring Files
- **To Download:** Tap any file to open/play it instantly over the network. Long-press files or folders to enter selection mode, then tap the **Download** button to save them directly to your phone.
- **To Upload:** Tap the floating **Upload (File)** button at the bottom right to browse your local device and send files directly to the server's current folder.
- **Background Transfers:** You can hit **Hide** on any active transfer to send it to the background. Tap the Cloud icon at the top right of the screen to bring the transfer dialog back up at any time!

## 🧑‍💻 Technical Architecture
- **Server:** Built entirely in Kotlin using the asynchronous **Ktor Netty** engine running in a Foreground Service.
- **Client:** Built natively using **Jetpack Compose** (Material 3), powered by **OkHttp** for proxy-bypassing network requests and **Coil** for image rendering.

---
*Developed by Dastgir Siddiq*
