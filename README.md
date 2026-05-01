# ScreenToCopy 🚀

**ScreenToCopy** is a high-performance, video-first Android application designed for seamless screen interaction. It allows users to quickly select any part of their screen to copy it to the clipboard or instantly open it in a photo editor.

Inspired by "Circle to Search" style interactions, ScreenToCopy focuses on extreme performance, low latency, and a smooth user experience.

---

## ✨ Key Features

- **Instant Selection**: Drag and select any area on your screen.
- **Copy-First Workflow**: Every selection is automatically saved to your clipboard for immediate use.
- **Drag-to-Edit**: Drag your selection to the center anchor to instantly launch your favorite photo editor.
- **High Performance**: Optimized touch engine with zero-allocation loops and 60 FPS visual feedback.
- **Adaptive Thresholds**: Intelligently adjusts to system performance to ensure responsiveness even on lower-end devices.

---

## 🛠 Technical Highlights

- **Zero-Allocation Rendering**: The selection engine and visual layers use pre-allocated objects to avoid GC spikes during gestures.
- **Asynchronous Execution**: All heavy I/O operations (cropping, saving, intent launching) are offloaded to background threads.
- **Service Watchdog**: Monitors the application's health and adaptively adjusts interaction thresholds.
- **Frame-Proof Logic**: Optimized mathematical calculations (avoiding `sqrt` and `hypot`) for sub-millisecond gesture processing.

---

## ⚠️ Security, Legal & Privacy Disclaimers

### 1. Accessibility Service Usage
ScreenToCopy requires the **Accessibility Service** permission to function. 
- **Purpose**: This permission is used solely to detect screen content and provide the overlay for selection.
- **Privacy**: No user data, passwords, or personal information is ever collected, stored, or transmitted to any external server. All processing happens locally on your device.

### 2. Screen Overlay
The app uses a system overlay to capture gestures. Users should be aware that this overlay is active only during the selection process.

### 3. Legal Disclaimer ("AS-IS")
This software is provided "as is", without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose and noninfringement. In no event shall the authors or copyright holders be liable for any claim, damages or other liability, whether in an action of contract, tort or otherwise, arising from, out of or in connection with the software or the use or other dealings in the software.

### 4. User Responsibility
The user is responsible for ensuring that their use of the application complies with local laws and regulations regarding privacy and screen capture.

---

## 🚀 Getting Started

1. Clone the repository.
2. Open in Android Studio (Koala or newer recommended).
3. Build and install the APK.
4. Enable the **ScreenToCopy** Accessibility Service in your device settings.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) (or your preferred license).
