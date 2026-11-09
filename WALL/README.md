# WALL (The Wall) — Street Stencil Android Launcher (Xiaomi Pad 5 Pro Edition)

> *"The screen is not an icon grid over a wallpaper — the screen is a real concrete wall, and icons are spray-painted stencils directly on that surface."*

**WALL** คือ Android Launcher สาย Minimalist ที่มีเอกลักษณ์ทางศิลปะแนวสตรีทสเตนซิลบนผนังปูนจริง ปรับแต่งสถาปัตยกรรมมาเป็นพิเศษสำหรับ **Xiaomi Pad 5 Pro** (รองรับจอ 16:10 ทั้งแนวนอนและแนวตั้ง, 120Hz, คีย์บอร์ด Pogo-pin และดูแลผู้ใช้ **Termux** อย่างเต็มรูปแบบโดยไม่ฆ่าโปรเซสและไม่ขอสิทธิ์อินเทอร์เน็ตใด ๆ)

---

## 1. วิธี Build โปรเจกต์ (How to Build)

### ข้อกำหนดเบื้องต้น
- **Android Studio** Ladybug / Koala หรือใหม่กว่า
- **JDK 17** (มาพร้อมกับ Android Studio)
- **Android SDK 35** (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`)

### ขั้นตอนการ Build ด้วย Command Line
1. เปิด Terminal ในโฟลเดอร์รากของโปรเจกต์ `WALL/`
2. รันคำสั่ง:
   ```bash
   ./gradlew assembleDebug
   ```
3. ไฟล์ APK ที่พร้อมติดตั้งจะอยู่ที่:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```
   *(ขนาด APK ประมาณ 1.8 – 2.4 MB และไม่ต้องพึ่งพาไฟล์รูปภาพภายนอกใด ๆ เพราะพื้นผิวปูนและสเตนซิลถูกสังเคราะห์ด้วย Procedural Engine ทั้งหมด)*

---

## 2. วิธีติดตั้งลงบนเครื่อง Xiaomi Pad 5 Pro (How to Install)

### วิธีที่ 1: ติดตั้งผ่าน ADB (แนะนำ)
1. บน Mi Pad 5 Pro ไปที่ **Settings -> About tablet -> แตะที่ MIUI/HyperOS version 7 ครั้ง** เพื่อเปิดโหมดนักพัฒนา
2. ไปที่ **Settings -> Additional settings -> Developer options** แล้วเปิด **USB debugging** และ **Install via USB**
3. เสียบสาย USB เข้ากับคอมพิวเตอร์ แล้วรันคำสั่ง:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### วิธีที่ 2: ติดตั้งจากใน Termux หรือไฟล์ APK โดยตรง
- คัดลอกไฟล์ `app-debug.apk` ลงในเครื่องแท็บเล็ต แล้วแตะเพื่อติดตั้งผ่าน Package Installer ได้ทันที

---

## 3. วิธีตั้งเป็น Default Launcher บน Xiaomi Pad 5 Pro (MIUI for Pad / HyperOS)

ระบบ MIUI / HyperOS ของ Xiaomi จะซ่อนการเปลี่ยน Home Launcher ไว้ลึกกว่า Android ปกติ สามารถตั้งค่าได้ 2 วิธีดังนี้:

1. **ตั้งค่าจากภายในแอป WALL:**
   - แตะปุ่มสเตนซิล **`[ WALL CONFIG ]`** ที่มุมขวาบนของกำแพง
   - แตะ **`[ SET DEFAULT HOME ]`**
   - เลือก **WALL** เป็นแอปหน้าจอหลักเริ่มต้น
2. **ตั้งค่าผ่านเมนู Settings ของ MIUI / HyperOS:**
   - เปิดแอป **Settings (การตั้งค่า)** ของเครื่อง
   - ไปที่ **Apps (แอป) -> Manage apps (จัดการแอป)**
   - แตะไอคอน **จุด 3 จุด (มุมขวาบน) -> Default apps (แอปเริ่มต้น)** *(หรือพิมพ์ค้นหาคำว่า "Home screen" ใน Settings)*
   - แตะ **Home screen (หน้าจอโฮม)** แล้วเปลี่ยนจาก *System launcher* เป็น **WALL**

> **หมายเหตุสำหรับ MIUI Gesture Navigation:** บน MIUI บางเวอร์ชัน เมื่อเปลี่ยนเป็น 3rd-party Launcher ระบบอาจสลับการควบคุมกลับเป็นปุ่ม 3 ปุ่มอัตโนมัติ (ข้อจำกัดของ ROM Xiaomi) ซึ่งช่วยให้การสลับเข้า-ออก Termux รวดเร็วและแม่นยำขึ้น

---

## 4. ฟีเจอร์พิเศษสำหรับผู้ใช้ Xiaomi Pad 5 Pro & Termux
- **พิมพ์ค้นหาทันทีผ่านเคสคีย์บอร์ด:** เมื่อต่อคีย์บอร์ดจริง สามารถพิมพ์ชื่อแอปได้ทันทีโดยไม่ต้องเอานิ้วแตะช่องค้นหา กด `Enter` เพื่อเปิดแอปแรก หรือกดปุ่ม `/` เพื่อเปิด **Termux** ทันที
- **ลากแบบลอกสเตนซิล (Stencil Peel):** กดค้างแล้วลากไอคอนเพื่อย้ายไปปักหมุดบนริมฟุตบาท (Curb Dock) หรือวางทับไอคอนอื่นเพื่อสร้างโฟลเดอร์กระดาษคราฟต์แปะผนัง (**Wheatpaste Folder**) โดยตำแหน่งเดิมบนกำแพงจะทิ้งรอยสีจาง ๆ ไว้ ("The Wall Remembers")
- **คู่มือป้องกัน Termux ตาย (`[ TERMUX CARE ]`):** กดปุ่ม `[ TERMUX CARE ]` บนหัวกำแพงเพื่อดูสถานะ RAM ที่ว่างอยู่จริง และกดลัดไปยังหน้าตั้งค่า Battery Saver ของ Termux ได้ในแตะเดียว
