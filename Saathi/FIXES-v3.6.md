# Saathi v3.6 — TEST-FIRST build, teesri koshish

Version: versionName "3.6", versionCode 14. Base v3.5 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi
liye gaye hain.

## 19:33 IST ki report mein jo fail hua tha
1. WhatsApp Business abhi bhi nahi samjha, sirf WhatsApp wala
   option diya.
2. Download mein test folder open nahi kiya.
3. Call list nikal kar Shaheen sis ko call karne ko kaha, lekin
   late ho gaya ya samjha nahi.

## Test-first: pehle v3.5 par nayi phrasing chalayi, phir fix kiya
v3.5 ke asli regex par yeh nayi phrasing test ki gayi, aur teeno
fail hue — isliye yeh andaza nahi, verified fail hai:

| Spoken command | v3.5 mein kya hua |
|---|---|
| download mein test folder KO open karo | FAIL — pattern mein folder ke baad "ko" tha hi nahi |
| test folder KO kholo | FAIL — wahi "ko" wali kami |
| call list SE shaheen sis KO call karo | CALL target "call list se shaheen sis" (5 shabd) ban gaya, confidence 0.30, Gemini par gaya |
| call list nikal KAR shaheen sis KO call karo | CALL target "call list nikal kar shaheen sis" ban gaya, wahi haal |
| contacts MEIN SE shaheen sis KO call karo | CALL target "contacts mein se shaheen sis" ban gaya, wahi haal |

## v3.6 mein kya badla
1. FOLDER_OPEN mein folder ke baad "ko" optional kar diya hai.
   Ab "folder open karo", "folder ko open karo" aur "folder ko
   kholo" teeno offline parse hote hain.
2. CALL ke liye naya cleanCallTarget hai: "call", "list",
   "contacts", "contact", "history", "nikal", "karke", "kar"
   jaise shabd naam se hata diye jate hain, aur slot() shuru
   ka "se / mein" bhi hata deta hai. Ab "call list se shaheen
   sis ko call karo" ka target seedha "shaheen sis" banta hai,
   aur ContactIndex ka fuzzy/title-strip usko "shaheen sis"
   contact se mila deta hai.
3. WhatsApp Business ke liye teesra rasta add kiya hai:
   - Pehla: package `com.whatsapp.w4b` (v3.4 se hai)
   - Doosra (naya, v3.6): agar w4b package nahi mila, toh
     label mein "business" wali app hi Business hai —
     personal WhatsApp kabhi nahi. Pehle yeh fallback nahi
     tha, isliye sirf WhatsApp wala option dikhta tha.
   - "whats business open karo / ko open karo / kholo" ki
     normalisation bhi test ki gayi hai: teeno mein business
     shortcut lagta hai.
   - IMPORTANT: agar aapke phone par Business app na toh w4b
     package se hai, na hi label mein "business" likha hai
     (jaise dual-app clone), toh yeh fix bhi kaam nahi karega.
     Us haal mein mujhe yeh chahiye: app drawer mein Business
     icon ka EXACT naam kya likha hai, aur kya woh alag app
     hai ya WhatsApp ke andar business account hai. Us naam
     ya package ke bina main usko dhoondh hi nahi sakta.

## Test results — 27/27 PASS, offline, bina Gemini ke
Test mein Kotlin file ke ASLI regex hi use hue hain. Purane 19
cases (v3.5 wale) sab pass hain, aur naye 8 bhi:

| Spoken command | Result |
|---|---|
| download mein test folder ko open karo | OPEN_FOLDER, download, test |
| test folder ko kholo | OPEN_FOLDER, (root), test |
| download mein test folder ko kholo | OPEN_FOLDER, download, test |
| call list se shaheen sis ko call karo | CALL, shaheen sis |
| call list nikal kar shaheen sis ko call karo | CALL, shaheen sis |
| contacts mein se shaheen sis ko call karo | CALL, shaheen sis |
| shaheen sis ko call karo | CALL, shaheen sis |
| shaheen ko call karo | CALL, shaheen |

Brace/paren count sab badli hui Kotlin files mein balanced hai.
Conflict markers kisi bhi .kt file mein nahi hain.

## Jo test abhi bhi nahi hua hai (imandari se)
- Local Gradle build nahi hua (VM mein Gradle daemon fail hota
  hai). GitHub Actions wala build hi asli compile check hai.
- Business app ka asli package/label aapke phone par hi pata
  chalega — upar wale teeno raste test kiye gaye hain, lekin
  agar aapka Business install in teeno mein se kisi mein nahi
  aata toh mujhe uska exact naam/package chahiye.
- Aapke phone, contacts aur Gemini key par device test main
  nahi kar sakta.

## Badli hui files (3)
- CommandParser.kt — FOLDER_OPEN mein "ko", cleanCallTarget
- AppIndex.kt — Business label fallback (best/matches ka naya
  exact result pehle se hi VoiceService mein choices skip kar
  deta hai, isliye VoiceService mein badlao ki zaroorat nahi
  padi)
- app/build.gradle.kts — versionCode 14, versionName "3.6"

## Push / build — PEHLE VERSION CHECK KAREIN
1. Zip ko SAATHI folder mein copy karein. `app/build.gradle.kts`
   zaroor copy ho.
2. `git add -A`, `git commit -m "Saathi v3.6"`, `git push`.
   "fetch first" aaye toh pehle `git pull --no-edit origin
   main`, force push kabhi mat karein.
3. Actions mein build hone dein, `app-debug-apk` download
   karke install karein.
4. **Pehla test version ka hai:** app ka title "Saathi · v3.6"
   dikhna chahiye. Agar title purana dikhe toh matlab nayi
   build install hi nahi hui hai — commands test karne ka
   koi matlab nahi hai, pehle title bata dein.
5. Uske baad yeh teeno test karein, aur har command ke baad
   panel par jo heard text + tag (offline / Gemini) dikhe,
   woh bata dein agar fail ho:
   - "WhatsApp Business open karo"
   - "download mein test folder ko open karo"
   - "call list se shaheen sis ko call karo"
