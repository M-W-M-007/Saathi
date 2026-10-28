# Saathi v3.10 — number ki jaanch + Files ke andar dobara bolna

Version: versionName "3.10", versionCode 18. Base v3.9 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi
liye gaye hain. UI mein koi badlao nahi hai.

## v3.9 video mein jo galat tha
1. Sunna wapas aa gaya tha, Ammi ko message aur pehli file move
   kaam kar gaye the, lekin Wazid naam nahi pehchana gaya, isliye
   user ne number bola, "Indian number" aur +91 bhi bola, phir
   bhi WhatsApp ne invalid kaha.
2. Files app khulne ke baad bubble/panel gayab ho gaya, user ko
   bahar aakar notification se dobara bolna pada.
3. Doosri Word wali file move nahi hui / dikhi nahi — iska
   sabse zyada sambhav reason wahi bubble/panel ka gayab hona
   hai, move ka logic nahi, kyunki pehli move kaam kar gayi thi.

## Wajah (code mein mili)
1. Number ki koi jaanch nahi thi. ContactIndex kisi bhi 7 digit
   se zyada wale number ko contact maan leta tha, ActionRunner
   sirf 10 digit par hi 91 lagata tha, baaki number waise hi
   wa.me par de diya jata tha. Video mein jo number panel mein
   dikha woh 9 digit ka tha, isliye WhatsApp ne usko invalid
   kaha — app ko pehle hi rokna chahiye tha, WhatsApp tak
   jaana hi nahi chahiye tha.
2. OPEN_FOLDER_IN_FILES auto-dismiss list se bahar nahi tha —
   panel 4 second mein hat jata tha. Agar us waqt bubble bhi
   nahi dikh raha ho toh user ke paas dobara bolne ka koi rasta
   nahi bachta tha.
3. Move ka result message pehle se file ka naam dikhata tha
   ("X ko Y mein move kar diya"), lekin panel hat jane se woh
   message bhi dikhta nahi tha.

## v3.10 mein kya badla
1. Number validation, teen parat mein:
   - ContactIndex: naya helper normalizedIndianNumber. Sahi
     hai: 10 digit, ya 91 + 10 digit (kul 12). Space, dash,
     "+91", "Indian number" jaise shabd digit nahi badalte.
     Galat digit wala number ab contact hi nahi banega.
   - VoiceService: number bolne par confirmation se pehle hi
     jaanch. Galat number par panel mein digit count dikhega
     aur message hoga ki number itne digit ka hai, 10 digit ka
     Indian number boliye, WhatsApp nahi khola. "Haan, karo"
     tak pahunchega hi nahi.
   - ActionRunner: doosri parat. Agar number kisi tarah
     confirmation ya Gemini se bhi aa jaye toh bhi galat number
     par wa.me kabhi nahi khulega, wahi digit-count wala
     message panel mein aayega. Sahi number par seedha
     +91 + 10 digit wala wa.me link banega, contact lookup
     ki zaroorat nahi.
   - Gemini prompt: naya rule — "Indian number" / "+91" /
     "plus ninety one" number ka hissa nahi hain, inko hatayo.
     10 digit se kam wala number kabhi return mat karo, missing
     digit par UNKNOWN do, taaki app phir se number maang sake.
2. Files ke andar dobara bolna:
   - OPEN_FOLDER_IN_FILES, MOVE_FILE aur COPY_FILE ab
     auto-dismiss se bahar hain, jaise pehle OPEN_FOLDER,
     LIST_FILES, SEARCH_FILE the. Panel tab tak rahega jab tak
     user band na kare, taaki Files ke andar se hi "Phir se
     boliye" dabakar dobara bola ja sake, bahar aana na pade.
   - Bubble ka setting wahi hai, panel hatne se bubble nahi
     hat-ta tha aur ab bhi nahi hat-ta — panel ka rehna hi
     iska asli fix hai, bubble ka alag se koi code nahi badla.
3. Move ki visibility:
   - Move ka result message pehle se file ka naam dikhata hai,
     aur ab panel hat-ta nahi hai, isliye woh message dikhega.
     Agar file nahi mili toh "X naam ki file nahi mili" wala
     message bhi panel par rahega, chhupa nahi rahega.

## Test results
Actual v3.10 code par check:
- 9 digit -> invalid (null), WhatsApp nahi khulega — PASS
- 10 digit -> +91 + 10 digit — PASS
- +91 + 10 digit -> wahi +91 + 10 digit — PASS
- 91 + 10 digit, space ke saath -> wahi — PASS
- 12 digit jo 91 se shuru nahi hota -> invalid — PASS
- VoiceService mein invalid-number wala early check maujood
  hai, ActionRunner mein doosri parat maujood hai, Gemini
  prompt mein Indian number wala rule maujood hai.
- OPEN_FOLDER_IN_FILES, MOVE_FILE, COPY_FILE auto-dismiss
  exempt list mein maujood hain.
- v3.9 ke fix waise hi hain: WhatsApp open shortcut, hi-IN
  revert, language fallback, dynamic panel.
- Brace/paren count badli hui Kotlin files mein balanced hai.
- Conflict markers kisi bhi .kt file mein nahi hain.

## Jo test abhi bhi nahi hua hai (imandari se)
- Local Gradle build nahi hua (VM mein Gradle daemon fail hota
  hai). GitHub Actions wala build hi asli compile check hai.
- Device par test nahi hua. Number validation aur Files ke
  andar dobara bolna, dono aapke phone par hi confirm honge.
- Wazid naam ka exact issue abhi bhi khula hai: agar Wazid
  contacts mein kisi alag naam se saved hai, ya apna hi chat
  hai, toh naam wala match uske bina theek nahi hoga. Is
  video mein naam wala test nahi hua, number wala test hua
  tha, isliye v3.10 mein number wala fix pakka hai, naam wala
  fix uske liye aapke jawab par nirbhar hai ki Wazid contacts
  mein kis naam se saved hai.

## Badli hui files
- ContactIndex.kt — normalizedIndianNumber, digitCount,
  isDigitTarget, galat number par empty return
- VoiceService.kt — invalid number ka early panel message,
  OPEN_FOLDER_IN_FILES / MOVE_FILE / COPY_FILE auto-dismiss
  se bahar
- ActionRunner.kt — WhatsApp mein number ki doosri jaanch,
  sahi number par seedha wa.me
- CommandParser.kt — Gemini prompt mein Indian number rule
- app/build.gradle.kts — versionCode 18, versionName "3.10"

## Push / build — PEHLE VERSION CHECK KAREIN
1. Zip ko SAATHI folder mein copy karein, nested
   SAATHI\Saathi\... na banne dein, `app/build.gradle.kts`
   zaroor copy ho, Replace all chunna.
2. `git add -A`, `git commit -m "Saathi v3.10"`, `git push`.
   "fetch first" aaye toh pehle `git pull --no-edit origin
   main`, force push kabhi mat karein.
3. Actions mein build hone dein, `app-debug-apk` download
   karke install karein. WhatsApp wali purani APK nahi.
4. **Pehla test version ka hai:** title "Saathi · v3.10"
   dikhna chahiye.
5. Uske baad yeh test karein, isi order mein:
   - "WhatsApp open karo" — WhatsApp khulna chahiye
   - Number wala test: jaanboojh kar ek digit kam wala
     number bolkar WhatsApp message bhejne ko kahein —
     WhatsApp nahi khulna chahiye, panel mein digit-count
     wala message aana chahiye
   - Sahi 10 digit wala number bolkar WhatsApp message —
     chat khulna chahiye, invalid nahi aana chahiye
   - Download/test folder kholkar Files mein jaayein, panel
     se "Phir se boliye" dabayein — bahar aaye bina dobara
     bolna chahiye, aur ek Word file ko test mein move
     karke dekhein — panel mein file ka naam dikhna chahiye
