<div align="center">

# 📷 Canon EOS M50 BLE Remote & Intervalometer

*A lightning-fast, native Android application to control your Canon mirrorless camera via Bluetooth Low Energy (BLE) — fully emulating the official **Canon BR-E1** remote.*

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Language-Java-007396?style=flat-square&logo=java&logoColor=white)](https://www.java.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Status: In Development](https://img.shields.io/badge/Status-Active%20Development-orange?style=flat-square)]()

</div>

---

## 🎯 Project Goal

To create a lightweight, highly responsive, and battery-efficient native Android application written purely in **Java** that interacts directly with the **Canon EOS M50** camera over BLE. By completely bypassing heavy cross-platform frameworks (like React Native or Flutter), this app ensures minimal latency and maximum reliability — serving as the core foundation for a custom **intervalometer**.

---

## 🛠 Tech Stack & Constraints

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Platform** | Android (Native) | Written purely for Android SDK |
| **Language** | **Java** | Strict adherence to pure Java for performance and BLE stability |
| **UI Design** | Standard Android XML | Clean, native, and lightweight layouts |
| **Communication** | Standard Android BLE API | Direct GATT, Bonding, and UUID filtering (*No PTP/IP or EDSDK*) |

---

## 🧠 Core Features (BR-E1 Emulation)

The app connects to the camera acting as an official remote control and executes two main low-level commands:

1. 🔋 **BLE Wake-up:** Sends a custom BLE payload packet to wake up a sleeping Canon camera (initializing Wi-Fi/readiness state).
2. 📸 **Shutter Press:** Dispatches a precise byte command via the BLE GATT service to instantly trigger a snapshot.

---

## 🔗 References & Acknowledgments

* Special thanks to open-source protocol research projects like **[ArthurFDLR/BR-M5](https://github.com/ArthurFDLR/BR-M5)** for decoding the Canon BLE command structure.

---

<p align="center">
  <i>Maintained by <a href="https://github.com/Delinex49">Delinex49</a></i>
</p>
