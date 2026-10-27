# Saathi v3.4 — video test ke baad ke fixes

Version: versionName "3.4", versionCode 12. Base v3.3 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi liye gaye
hain — call dialer par khulta hai, user call button dabata hai; WhatsApp
chat khulti hai, Accessibility on ho toh send dab sakta hai, warna user
send dabata hai.

## Video mein jo dikha tha, aur v3.4 mein kya badla

### 1. "What's the business open karo" samajh nahi aaya
Mic "WhatsApp" ko "what's" sun leta hai. Ab app kholne wali baat mein
"what's" / "whats app" ko WhatsApp mein badal diya jata hai, parse se
pehle. Message ki body mein yeh badlao NAHI hota, taaki "what's up"
likhna kharab na ho.

### 2. WhatsApp Business app hote hue bhi nahi mila
Panel kehta tha "whatsapp business exactly nahi mili" aur sirf personal
WhatsApp offer karta tha. Wajah: Business ka label app list mein exact
nahi mil raha tha. Ab "business" bolne par label dhoondhna band kar
diya hai — seedha package `com.whatsapp.w4b` khola jata hai, chahe
label kuch bhi likha ho. Yeh teen jagah hai: CommandParser (open
shortcut), AppIndex.best/matches (package pin), VoiceService (choices
mat poochho, seedha kholo). Business install hi nahi hai toh pehle
jaisa honest message aayega.

### 3. "Ammi new number per ... message bhejo" timeout ho gaya
Purana WhatsApp rule sirf "ko" se milta tha. "per" wali baat Gemini
par ja kar 22 second mein timeout ho jati thi. Ab naya rule hai:
`[contact] per [message] ka message bhejo` — test kiya gaya:
- "Ammi new number per Ek how are you ka message bhejo"
  -> (Ammi new number, "Ek how are you")
- "Ammi per hello message bhejo" -> (Ammi, "hello")
- "Ammi per hello bhejo" / "Ammi per likho ki hello" -> (Ammi, "hello")

### 4. Folder ban gaya, lekin open nahi hua
App mein folder kholne ka action hi nahi tha, sirf list dikhane ka
tha. Ab naya action OPEN_FOLDER hai:
- "Download mein test folder open karo" -> Download ke andar test
- "test folder kholo" -> test folder dhoondh kar kholo
- "download folder open karo" -> Download folder khud
- "jo folder banaya hai use open karo" -> Gemini ko prompt mein
  OPEN_FOLDER diya gaya hai, woh pichhle turn ke folder ka naam
  history se le sakta hai.
Files app (jo bhi file manager installed ho) khulti hai aur folder
ki list panel par dikhti hai — list hi saboot hai ki sahi folder
khula hai. Android kisi folder ko seedha ek intent se Files app mein
nahi kholta, isliye dono kaam ek saath hote hain.

### 5. "Shaheen syster" ko "Shaheen sis" nahi mila
Contact matching bahut strict tha. Ab do fallback hain:
- Title hatana: sis / syster / sister / bhai / didi jaise shabd
  naam ka hissa nahi hote, isliye "Shaheen syster" -> "Shaheen"
  ban kar "Shaheen sis" se mil jata hai.
- Fuzzy match: har shabd ke liye prefix ya zyada se zyada 2 letters
  ka farq chalta hai ("shahin" -> "shaheen" 2 edits hai).
  Fuzzy guess kabhi exact match ko hara nahi sakta, sirf "nahi
  mila" ko rokta hai.
- Contact na mile toh panel par hint bhi dikhega: "Sirf pehla
  naam bolkar dobara try karein, jaise 'Shaheen ko call karo'."
Tab tak ka workaround wahi hai: call ke liye sirf pehla naam bolein.

### 6. Panel itna bada ki peeche ka nahi dikhta
Panel 45% se 32% height par kar diya gaya hai, aur doosri app
khulne par panel 4 second ki jagah 2.5 second mein khud hat jayega.
OPEN_FOLDER bhi isi list mein hai, kyunki woh Files app kholta hai.

### 7. Gemini slow ho toh panel 22 second mein timeout
Ab timeout 45 second ka hai, aur 15 second par panel par likha
aayega: "Gemini se pooch raha hoon, thoda rukiye…" — taaki beech
mein khamoshi na lage ki app ruk gaya hai.

## Badli hui files (7)
- Command.kt — OPEN_FOLDER action
- CommandParser.kt — what's normalisation (sirf open wali baat),
  WHATSAPP_PER, FOLDER_OPEN_PARENT, Business open shortcut,
  Gemini prompt mein OPEN_FOLDER
- AppIndex.kt — Business package pin
- VoiceService.kt — Business choices skip, panel 32%, auto-dismiss
  2.5s, watchdog 45s + 15s progress, contact not-found hint
- ContactIndex.kt — title strip + fuzzy match
- ActionRunner.kt — openFolder (Files app + list)
- app/build.gradle.kts — versionCode 12, versionName "3.4"

## Push / build (v3.3 wala hi tareeqa)
1. Is zip ke contents ko SAATHI folder mein replace karein, phir
   `git add -A`, `git commit -m "Saathi v3.4"`, `git push`.
   Agar push phir "fetch first" kahe toh pehle `git pull --no-edit
   origin main` karein — force push kabhi mat karein.
2. GitHub Actions mein build hone dein, `app-debug-apk` download
   karke purani app ke upar install karein (wahi debug keystore
   hai, isliye overwrite hoga).
3. App ka title "Saathi · v3.4" dikhna chahiye — wahi pakka
   saboot hai ki nayi build install hui hai.

## Test order (is order mein, ek ek karke)
1. Title check: "Saathi · v3.4".
2. "WhatsApp Business open karo" — seedha Business khulna chahiye,
   personal WhatsApp offer nahi aana chahiye. Agar Business
   install nahi hai toh honest message aana chahiye.
3. "what's the business open karo" (jaanboojh kar galat bolkar)
   — Business khulna chahiye.
4. "Shaheen ko call karo" — pehla naam hi kaafi hai; phir
   "Shaheen syster ko call karo" bhi try karein, ab milna chahiye.
5. "Download mein test folder open karo" — Files app khule aur
   panel par test folder ki list dikhe. Agar test folder abhi
   nahi hai toh pehle "Downloads mein Bills naam ka folder banao"
   karke phir usko open karein.
6. "Ammi new number per hello ka message bhejo" — chat khule,
   message likha ho, 22 second ka timeout nahi aana chahiye.
   (Contact ka naam apne phone ke hisaab se badal lein.)
7. Lambi Gemini wali baat, jaise "jo folder banaya hai use open
   karo" — 15 second par "Gemini se pooch raha hoon" dikhna
   chahiye, 22 second par timeout nahi.
8. Call / WhatsApp khulne ke baad panel jaldi hatna chahiye
   (2.5 second) aur peeche ka button dikhna chahiye.

## Abhi bhi locally build nahi hua
VM mein Gradle daemon fail hota hai (v3.3 mein bhi yahi tha).
Isliye GitHub Actions wala build hi asli check hai. Agar build
fail ho toh Actions ke "Build errors" wale summary ka screenshot
bhej dein, wahi se fix hoga.

## Ek packaging galti jo v3.4 mein theek hai
v3.3 wale zip mein galti se `app/build.gradle.kts` file hi nahi thi,
isliye agar aapne sirf zip copy kiya tha toh app ka title shayad
"v3.3" ki jagah purana hi dikha hoga. v3.4 wale zip mein yeh file
zaroor hai (versionCode 12, versionName "3.4") — isko copy karna
mat bhoolna, warna title nahi badlega.
