# Google Form Registration Bot

บอทกรอก Google Form อัตโนมัติ เขียนด้วย Java + Selenium

เปิดฟอร์มด้วย Chrome อ่านคำถามทั้งหมดออกมา กรอกคำตอบจากไฟล์ แล้วกดส่ง (ถ้าสั่ง)

---

## สิ่งที่ต้องมีก่อน

| อย่าง | เวอร์ชันขั้นต่ำ | เช็กด้วยคำสั่ง |
|---|---|---|
| JDK | 21 | `java -version` |
| Apache Maven | 3.9 | `mvn -v` |
| Google Chrome | เวอร์ชันไหนก็ได้ | เปิดโปรแกรมดู |

> **ไม่ต้องโหลด chromedriver เอง** Selenium Manager จะหาเวอร์ชันที่ตรงกับ Chrome ในเครื่องมาให้อัตโนมัติตอนรันครั้งแรก

---

## ติดตั้ง

### 1. ติดตั้ง JDK

โหลด JDK 21 ขึ้นไปจาก [Adoptium](https://adoptium.net/) หรือ [Oracle](https://www.oracle.com/java/technologies/downloads/)

เช็ก:
```
java -version
```

### 2. ติดตั้ง Maven

Maven ไม่มี installer เป็นแค่ zip ที่แตกแล้วชี้ PATH

**Windows**

```powershell
New-Item -ItemType Directory -Force "$HOME\tools" | Out-Null
Invoke-WebRequest -Uri "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip" -OutFile "$env:TEMP\maven.zip"
Expand-Archive -Path "$env:TEMP\maven.zip" -DestinationPath "$HOME\tools" -Force

$mvnBin = "$HOME\tools\apache-maven-3.9.16\bin"
$old = [Environment]::GetEnvironmentVariable("Path", "User")
if ($old -notlike "*$mvnBin*") {
    [Environment]::SetEnvironmentVariable("Path", "$old;$mvnBin", "User")
}
```

ปิด PowerShell แล้วเปิดใหม่ (PATH อ่านตอนเปิด process เท่านั้น) แล้วเช็ก `mvn -v`

**macOS**
```bash
brew install maven
```

**Linux (Debian/Ubuntu)**
```bash
sudo apt install maven
```

### 3. Clone แล้ว build

```bash
git clone https://github.com/<user>/Google-Form-registration-bot.git
cd Google-Form-registration-bot
mvn compile
```

ครั้งแรกจะช้าเพราะโหลด Selenium (~50MB) ลง `~/.m2/repository` ครั้งต่อไปจะเร็ว

---

## วิธีใช้

### ขั้นที่ 1 — อ่านฟอร์มก่อน

รันแบบนี้เพื่อดูว่าบอทมองเห็นคำถามอะไรบ้าง **ยังไม่กรอก ยังไม่ส่ง**

```bash
mvn -q compile exec:java "-Dform.url=https://docs.google.com/forms/d/e/XXXX/viewform"
```

ผลลัพธ์:

```
กำลังเปิดฟอร์ม...
ฟอร์ม: ลงทะเบียนกิจกรรม

พบคำถาม 4 ข้อ
  [0] ชื่อ-นามสกุล * (SHORT_TEXT)
  [1] อีเมล * (SHORT_TEXT)
  [2] รอบที่ต้องการ (RADIO)
      ตัวเลือก: รอบเช้า | รอบบ่าย
  [3] ความสนใจ (CHECKBOX)
      ตัวเลือก: กีฬา | ดนตรี | ศิลปะ

ยังไม่มีไฟล์คำตอบ (answers.txt) — อ่านอย่างเดียว ไม่ได้กรอกอะไร
```

`*` = ข้อที่บังคับตอบ

### ขั้นที่ 2 — สร้างไฟล์คำตอบ

สร้าง `answers.txt` ที่ root ของโปรเจกต์ เอาชื่อคำถามจากขั้นที่ 1 มาใส่

```
# บรรทัดขึ้นต้นด้วย # คือคอมเมนต์
ชื่อ-นามสกุล = สมชาย ใจดี
อีเมล = somchai@example.com

# ช้อยส์ต้องพิมพ์ให้ตรงกับตัวเลือกในฟอร์ม
รอบที่ต้องการ = รอบเช้า

# ติ๊กหลายข้อ คั่นด้วยจุลภาค
ความสนใจ = กีฬา, ดนตรี
```

**ไม่ต้องพิมพ์ชื่อคำถามเต็มก็ได้** ขอแค่เป็นส่วนหนึ่งของคำถามจริง เช่นพิมพ์ `อีเมล` ก็จับคู่กับ `อีเมลของท่าน` ได้

### ขั้นที่ 3 — ให้บอทกรอก

```bash
mvn -q compile exec:java "-Dform.url=https://docs.google.com/forms/d/e/XXXX/viewform"
```

คำสั่งเดิม แต่คราวนี้มี `answers.txt` แล้ว บอทจะกรอกให้ **แต่ยังไม่กดส่ง**

```
กรอกแล้ว: ชื่อ-นามสกุล = สมชาย ใจดี
กรอกแล้ว: อีเมล = somchai@example.com
กรอกแล้ว: รอบที่ต้องการ = รอบเช้า
ข้าม: ความสนใจ

กรอกครบแล้วแต่ยังไม่ส่ง (ใส่ "-Dsubmit=true" ถ้าต้องการส่งจริง)
```

อยากเห็นหน้าจอตอนมันกรอก ใส่ `-Dheadless=false` เพิ่ม

### ขั้นที่ 4 — ส่งจริง

```bash
mvn -q compile exec:java "-Dform.url=..." "-Dsubmit=true"
```

⚠️ ส่งแล้วส่งเลย ยกเลิกไม่ได้ — แนะนำให้ผ่านขั้นที่ 3 จนพอใจก่อน

---

## ตัวเลือกทั้งหมด

| ตัวเลือก | ค่าเริ่มต้น | ความหมาย |
|---|---|---|
| `-Dform.url=<url>` | *(จำเป็น)* | ลิงก์ฟอร์ม ต้องลงท้ายด้วย `/viewform` |
| `-Dheadless=false` | `true` | เปิด Chrome ให้เห็นหน้าจอ |
| `-Dsubmit=true` | `false` | กดส่งฟอร์มจริง |
| `-Danswers=<path>` | `answers.txt` | เปลี่ยนไฟล์คำตอบ |

ใช้ไฟล์คำตอบหลายชุดสลับกันได้:

```bash
mvn -q compile exec:java "-Dform.url=..." "-Danswers=data/somchai.txt"
```

> **บน PowerShell ต้องครอบ `"-Dxxx=yyy"` ด้วย double quote ทุกตัว** ไม่งั้น PowerShell จะแย่ง `-D` ไปตีความเป็น parameter ของตัวเอง บน bash/cmd ไม่ต้องครอบ

---

## ภาษาไทยออกมาเป็น `????`

ฝั่ง JVM จัดการให้แล้วผ่าน `.mvn/jvm.config` เหลือแค่ตั้งค่า console

**Windows** — สั่งก่อนรัน 1 ครั้งต่อการเปิด terminal:
```powershell
chcp 65001
```

macOS/Linux ปกติเป็น UTF-8 อยู่แล้ว ไม่ต้องทำอะไร

---

## รองรับคำถามแบบไหนบ้าง

| ชนิด | สถานะ |
|---|---|
| ข้อความสั้น (Short answer) | ✅ |
| ข้อความยาว (Paragraph) | ✅ |
| ช้อยส์เดียว (Multiple choice) | ✅ |
| ติ๊กหลายข้อ (Checkboxes) | ✅ |
| Dropdown | ✅ |
| วันที่ / เวลา | ✅ |
| คำถามแบบตาราง (Grid) | ❌ |
| อัปโหลดไฟล์ | ❌ |

**ข้อจำกัดอื่น**

- ฟอร์มหลายหน้า (มีปุ่ม "ถัดไป") ยังไม่รองรับ — เห็นแค่หน้าแรก
- ฟอร์มที่บังคับล็อกอิน Google ใช้ไม่ได้
- ฟอร์มที่เปิด reCAPTCHA ใช้ไม่ได้

---

## โครงสร้างโปรเจกต์

```
.
├── pom.xml                  ค่า build + dependency
├── .mvn/jvm.config          บังคับ UTF-8 ให้ JVM
├── answers.txt              ไฟล์คำตอบ (สร้างเอง ไม่ได้อยู่ใน git)
└── src/main/java/com/example/
    ├── App.java             ตัว main — รับตัวเลือก อ่านไฟล์คำตอบ สั่งงานบอท
    ├── GoogleFormBot.java   คุม Chrome — เปิด อ่าน กรอก ส่ง
    └── FormField.java       ข้อมูลคำถาม 1 ข้อ
```

---

## แก้ปัญหาที่เจอบ่อย

**`'mvn' is not recognized`**
Maven ยังไม่อยู่ใน PATH หรือยังไม่ได้เปิด terminal ใหม่หลังติดตั้ง

**`Source option 7 is no longer supported`**
`pom.xml` ตั้ง compiler เป็นเวอร์ชันเก่าเกินไป ต้องเป็น `<maven.compiler.release>21</maven.compiler.release>`

**`ไม่พบคำถามในหน้านี้`**
ลิงก์ไม่ใช่หน้าฟอร์ม ต้องเป็นลิงก์ที่ลงท้าย `/viewform` (ไม่ใช่ลิงก์แก้ไขฟอร์มที่ลงท้าย `/edit`) หรือฟอร์มบังคับล็อกอิน

**`ไม่ได้กรอกอะไรเลย`**
ชื่อคำถามใน `answers.txt` ไม่ตรงกับในฟอร์ม — รันขั้นที่ 1 ดูชื่อจริงแล้วก๊อปมาใส่

**`คำถาม "..." ไม่มีตัวเลือก "..."`**
พิมพ์ช้อยส์ไม่ตรง ระวังช่องว่างเกินหรือวรรณยุกต์ ดูรายการตัวเลือกที่บอทบอกมาแล้วก๊อปไปวาง
