# Google-Form-registration-bot — สรุปสถานะโปรเจกต์

## ภาพรวม
โปรเจกต์นี้เป็น Java + Maven โปรเจกต์ที่ตั้งใจจะทำ **bot สำหรับกรอก/ลงทะเบียน Google Form อัตโนมัติ** โดยใช้ **Selenium WebDriver** ในการควบคุมเบราว์เซอร์ (ดูจากชื่อ repo และ dependency ที่ประกาศไว้)

ปัจจุบันโปรเจกต์อยู่ในสถานะ **โครงเริ่มต้น (skeleton)** — ยังไม่มีโค้ดที่ทำหน้าที่จริงตามชื่อโปรเจกต์

## โครงสร้างไฟล์
```
Google-Form-registration-bot/
├── .vscode/settings.json
└── botGoogleFormMvn/
    ├── pom.xml
    └── src/main/java/org/example/Main.java
```

## รายละเอียดที่พบ

### `pom.xml`
- Java 21 (source/target)
- Dependency เดียว: `selenium-java` เวอร์ชัน 4.18.1
- ตั้งค่า `exec-maven-plugin` ให้รัน mainClass ชื่อ **`SeleniumFormReader`**
  - ⚠️ **ไฟล์/คลาสนี้ยังไม่มีอยู่จริงในซอร์สโค้ด** — pom.xml อ้างถึงคลาสที่ยังไม่ได้สร้าง ดังนั้นการรันผ่าน `mvn exec:java` จะ fail

### `Main.java`
- เป็นโค้ด **template เริ่มต้นจาก IntelliJ IDEA** (มีคอมเมนต์ TIP ของ IntelliJ ทั้งหมด)
- แค่ print `"Hello and welcome!"` และวนลูปพิมพ์เลข 1-5
- ยังไม่มี logic เกี่ยวกับ Selenium, Google Form หรือการลงทะเบียนใดๆ ทั้งสิ้น

### Git history
- มีเพียง 1 commit: `Add files via upload` — เป็นการอัปโหลดไฟล์ครั้งแรก ยังไม่มีการพัฒนาเพิ่มเติม

## สรุปสถานะ
โปรเจกต์นี้**ยังไม่ได้เริ่มพัฒนาฟังก์ชันจริง** มีเพียง:
1. โครง Maven + dependency Selenium ที่เตรียมไว้
2. โค้ด Hello World จาก template
3. การอ้างอิงถึงคลาส `SeleniumFormReader` ใน pom.xml ที่ยังไม่มีไฟล์จริง (บ่งชี้ว่าน่าจะเคยตั้งใจจะสร้างไฟล์นี้เป็นจุดเริ่มต้นของ bot)

## สิ่งที่ต้องทำต่อ (ข้อเสนอแนะ)
- [ ] สร้างคลาส `SeleniumFormReader` (หรือปรับ `mainClass` ใน pom.xml ให้ตรงกับคลาสที่จะสร้างจริง)
- [ ] กำหนด flow การทำงาน เช่น เปิดฟอร์ม → หา element ของแต่ละคำถาม → กรอกค่า → กด Submit
- [ ] ตัดสินใจเรื่อง WebDriver: ใช้ Selenium Manager (มากับ 4.18.1 อยู่แล้ว) หรือระบุ path ของ chromedriver เอง
- [ ] พิจารณาว่าจะอ่านข้อมูลที่จะกรอกจากไหน (CSV, config file, ค่าคงที่ในโค้ด ฯลฯ)
- [ ] เพิ่ม error handling และ logging สำหรับกรณีที่ฟอร์มมีการเปลี่ยนโครงสร้าง
