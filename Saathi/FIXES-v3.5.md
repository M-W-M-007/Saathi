# Saathi v3.5 — TEST-FIRST build (user ki shart par)

Version: versionName "3.5", versionCode 13. Base v3.4 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi
liye gaye hain.

## Pehle wali galti, saaf shabdon mein
v3.3 aur v3.4 maine sirf apni andazi wali phrasing par test karke
diye the, user ki asli boli par nahi. Isliye video mein wahi
commands phir timeout hue. v3.5 us tareeqe se NAHI diya ja raha
hai: neeche wala test pehle chalaya gaya hai, aur tabhi zip bana
hai. Test mein Kotlin file ke ASLI regex hi use hue hain
(dobara type karke nahi), taaki test aur shipped code mein farq
na ho.

## Is video (19:03 IST) mein jo fail hua tha, aur kyun
1. "WhatsApp per Wajid ko ek message send karo how r u"
   - v3.4 ka WHATSAPP pattern "ek" se pehle aur "message send
     karo" par toot jata tha (verb list mein sirf "message kar"
     tha). WHATSAPP_PER pattern galat who "whatsapp" bana deta
     tha. Dono ki wajah se baat Gemini par gayi aur timeout hui.
   - v3.5 fix: WHATSAPP verb mein "message send kar / message
     send" add, "ek" optional, aur TAIL mein "per" add (warna
     "per wajid" ka contact kabhi nahi milta). Ab yeh offline
     parse hota hai: (Wajid, "how r u").
2. "download mein ek test naam se folder hai use open karo"
   - v3.4 ka FOLDER_OPEN sirf "naam ka" janta tha, "naam se"
     nahi, aur "hai use" bhi nahi. Isliye yeh bhi Gemini par
     gaya aur timeout hua.
   - v3.5 fix: "naam ka" ke saath "naam se" bhi, "ek" optional,
     aur folder ke baad "hai" + "use/usko/isko" optional. Ab
     offline parse hota hai: (download, test).
3. Gemini ka der se aaya jawab phenk diya jata tha
   - Pehle timeout ke baad worker ka jawab discard ho jata tha.
   - v3.5 fix: timeout sirf ek message hai; uske baad aaya
     jawab bhi panel par aa jayega aur chalega.
4. Ek chhoti galti jo test mein pakdi gayi
   - "Ammi ko WhatsApp par likho ki test message hai" ki body
     "ki test message hai" ban rahi thi. Ab "ki " shuru se
     hataya jata hai, body "test message hai" hoti hai.

## Test results — 19/19 PASS, offline, bina Gemini ke
Har case mein action aur dono slots sahi hone par hi PASS hai.

| Spoken command | Result |
|---|---|
| WhatsApp per Wajid ko ek message send karo how r u | WHATSAPP, Wajid, "how r u" |
| download mein ek test naam se folder hai use open karo | OPEN_FOLDER, download, test |
| Wajid ko ek message send karo how r u | WHATSAPP, Wajid, "how r u" |
| Wajid ko message send karo hello | WHATSAPP, Wajid, "hello" |
| Ammi ko WhatsApp par likho ki test message hai | WHATSAPP, Ammi, "test message hai" |
| download mein test folder open karo | OPEN_FOLDER, download, test |
| test folder kholo | OPEN_FOLDER, (root), test |
| download folder open karo | OPEN_FOLDER, (root), download |
| download mein test folder hai use open karo | OPEN_FOLDER, download, test |
| downloads mein bills folder kholo | OPEN_FOLDER, downloads, bills |
| Ammi new number per Ek how are you ka message bhejo | WHATSAPP, Ammi new number, "Ek how are you" |
| Ammi per hello message bhejo | WHATSAPP, Ammi, "hello" |
| Ammi per hello bhejo | WHATSAPP, Ammi, "hello" |
| Ammi per likho ki hello | WHATSAPP, Ammi, "hello" |
| Downloads mein Bills naam ka folder banao | CREATE_FOLDER, downloads, Bills |
| Bills naam ka folder banao Downloads mein | CREATE_FOLDER, downloads, Bills |
| resume wali file ko Documents mein move karo | MOVE_FILE, resume, Documents |
| resume wali file Documents mein move karo | MOVE_FILE, resume, Documents |
| resume file ko Documents mein move karo | MOVE_FILE, resume, Documents |

Brace/paren count sab badli hui Kotlin files mein balanced hai.
Conflict markers kisi bhi .kt file mein nahi hain.

## Jo test abhi bhi nahi hua hai (imandari se)
- Local Gradle build nahi hua (VM mein Gradle daemon fail hota
  hai, v3.3 se yahi hai). GitHub Actions wala build hi asli
  compile check hai.
- Aapke phone, aapke contacts aur aapki Gemini key par device
  test main nahi kar sakta — woh aapko hi karna hai. Lekin
  is baar parser test pehle ho chuka hai, isliye upar wali 19
  commands ko Gemini par jaane ki zaroorat hi nahi padni
  chahiye; panel par "offline" tag dikhna chahiye, "Gemini"
  nahi.

## Badli hui files (3)
- CommandParser.kt — WHATSAPP verb + "ek", TAIL mein "per",
  body se "ki " hatana, FOLDER_OPEN mein "naam se" + "hai use"
- VoiceService.kt — Gemini late result discard band
- app/build.gradle.kts — versionCode 13, versionName "3.5"

## Push / build — PEHLE VERSION CHECK KAREIN
1. Is zip ko SAATHI folder mein copy karein. Dhyan rahe:
   `app/build.gradle.kts` zaroor copy ho (v3.3 wale zip mein
   yeh file missing thi).
2. `git add -A`, `git commit -m "Saathi v3.5"`, `git push`.
   "fetch first" aaye toh pehle `git pull --no-edit origin
   main`, force push kabhi mat karein.
3. Actions mein build hone dein, `app-debug-apk` download
   karke install karein.
4. **Pehla test version ka hai, command ka nahi:** app ka
   title "Saathi · v3.5" dikhna chahiye. Agar title purana
   dikhe toh matlab nayi build install hi nahi hui hai — us
   haal mein commands test karne ka koi matlab nahi hai, pehle
   install theek karein aur title bata dein.
5. Uske baad upar wali table ke pehle 2 commands test karein:
   "WhatsApp per Wajid ko ek message send karo how r u" aur
   "download mein ek test naam se folder hai use open karo".
   Dono par panel par "offline" tag aana chahiye, aur koi
   timeout nahi aana chahiye.
