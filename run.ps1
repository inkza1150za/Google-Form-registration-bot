# ตัวช่วยรันบอท — ตั้งค่า UTF-8 ให้ก่อน แล้วค่อยเรียก Maven
#
#   .\run.ps1 "-Dform.url=https://docs.google.com/forms/d/e/XXXX/viewform"
#   .\run.ps1 "-Dform.url=..." "-Dpeople=people.csv" "-Dsubmit=true"

chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

mvn -q compile exec:java @args
