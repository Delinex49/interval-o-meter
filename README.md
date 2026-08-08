<div align="center">

# 📷 Canon EOS M50 BLE Remote & Intervalometer

*A lightning-fast, native Android application to control your Canon mirrorless camera via Bluetooth Low Energy (BLE) — fully emulating the official **Canon BR-E1** remote.*

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Java-007396?style=flat-square&logo=java&logoColor=white)](https://www.java.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Status](https://img.shields.io/badge/Status-v1.0%20%7C%20Stable-brightgreen?style=flat-square)]()

</div>

---

## 🎯 Project Goal

To create a lightweight, highly responsive, and battery-efficient native Android application written purely in **Java** that interacts directly with Canon cameras over BLE. By completely bypassing heavy cross-platform frameworks (like React Native or Flutter), this app ensures minimal latency and maximum reliability — serving as an instant remote shutter and a custom **intervalometer**.

---

## 🛠 Tech Stack & Constraints

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Platform** | Android (Native) | Written purely for Android SDK |
| **Language** | **Java** | Strict adherence to pure Java for performance and BLE stability |
| **UI Design** | Standard Android XML | Clean, native, and lightweight layouts |
| **Communication** | Standard Android BLE API | Direct GATT, Bonding, and UUID filtering (*No PTP/IP or EDSDK required*) |

---

## 🧠 Core Features (BR-E1 Emulation)

The app connects to the camera acting as an official remote control and executes low-level BLE commands:

* 🔋 **BLE Auto-Wake:** Automatically sends a BLE payload packet to wake up a sleeping camera.
* 📸 **Remote Shutter:** Dispatches precise byte commands via the BLE GATT service to trigger immediate snapshots.
* ⏱ **Intervalometer:** Custom timelapse mode allowing you to sequence automated shutter triggers with customizable intervals.

---

## 📷 Canon Compatibility

Because the app uses the standard Canon BLE protocol (UUID `0005...` and the `0x8C/0x0C` bitmask), it is designed to work "out of the box" with most Canon cameras supporting the BR-E1 remote:

* **EOS R Series:** Virtually all models (*EOS R, RP, R5, R6, R7, R10, R50, etc.*).
* **EOS M Series:** M6 Mark II, M200.
* **DSLRs:** 90D, 850D, 250D, 200D.
* **PowerShots:** G5 X Mark II, G7 X Mark III.

> [!IMPORTANT]  
> **Camera Drive Mode Setting:**  
> For the remote shutter to trigger photos, **you must set your camera's Drive Mode to "Self-Timer: 10sec/Remote" or "Remote Control"** (indicated by a timer icon with a small remote control next to it). If left on "Single Shooting", the camera will ignore BLE shutter commands.

> [!NOTE]  
> **Tested Device:** Tested and verified **strictly on the Canon EOS M50**. Other supported models use the same protocol structure, but hardware testing on them has not been performed.

> [!WARNING]  
> **Unsupported Models:**  
> Older models like the **EOS M5** and **EOS M100** will **not** work because their Bluetooth hardware only supports smartphone pairing (for initial Wi-Fi setup) and lacks the built-in "Remote Control" protocol in their firmware.

---

## 🔗 References & Acknowledgments

* Special thanks to open-source protocol research projects like **[ArthurFDLR/BR-M5](https://github.com/ArthurFDLR/BR-M5)** for decoding the Canon BLE command structure.

---

<p align="center">
  <i>Maintained by <a href="https://github.com/Delinex49">Delinex49</a></i>
</p>