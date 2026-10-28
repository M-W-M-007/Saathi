# Saathi v3.9 — sunna wapas lao + WhatsApp open pakka

Version: versionName "3.9", versionCode 17. Base v3.8 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi
liye gaye hain. UI mein is baar koi badlao nahi hai — user ne
kaha hai UI ok hai, baad mein batayenge, isliye sirf functionality
par kaam hua hai.

## v3.8 video mein jo galat tha
1. Panel chhota ho gaya tha (UI ok), lekin sunna band ho gaya:
   panel "Boliye..." par atak jata tha, koi partial text nahi aata tha.
2. "WhatsApp open karo" offline khulne ki jagah "Yeh samajh nahi
   aaya · Gemini: pata nahi" ho gaya tha.

## Wajah (code mein mili)
1. v3.8 mein speech ki default language hi-IN kar di gayi thi.
   SpeechInput.kt mein pehle se likha hua tha ki hi-IN force
   karna galat hai: jis phone par Hindi voice model nahi hai,
   wahan recognizer network/no-match error dekar fail ho jata
   hai. v3.7 mein language phone default thi, tab sunai de raha
   tha. v3.8 mein hi-IN aate hi sunna band ho gaya.
2. "WhatsApp open karo" AppIndex list par nirbhar tha. Agar list
   mein kuch gadbad ho toh command Gemini par ja kar fail ho
   jata tha, jabki WhatsApp kholna sabse simple kaam hai.

## v3.9 mein kya badla
1. Speech default REVERT:
   - Default phir se phone default (empty) hai, jaise v3.7 mein
     tha. Settings mein jo Hindi/English chun chuka hai, uska
     chunav hi rahega. MainActivity mein "Default" option
     pehle se maujood hai, wahi select dikhega.
   - Hindi chahiye toh Settings mein Hindi chunna hoga, lekin
     uske liye phone par Hindi voice model hona zaroori hai —
     warna recognizer phir fail hoga, ye Android ki limit hai,
     code ki nahi.
2. Recognizer language fallback (sirf ek baar):
   - Agar set language (jaise Hindi) par network/server/no-match
     aaye toh recognizer apne aap phone default par dobara try
     karega, uske baad hi normal retry ya error message aayega.
     Isse recognizer atak kar band nahi hoga.
   - Ye fallback sirf ek baar hota hai, loop nahi banega.
3. WhatsApp open ka teen parat wala pakka rasta:
   - Parser: naam sirf "whatsapp" ho toh AppIndex dekhe bina
     seedha OPEN_APP WhatsApp, confidence 0.95.
   - VoiceService: hint "whatsapp" ho toh AppIndex check skip
     karke seedha execute karega, file/folder choices nahi dega.
   - ActionRunner: pehle package "com.whatsapp" se seedha
     launch intent try karega, list par nirbhar nahi rahega.
   - Isse "WhatsApp open karo / kholo / open" kabhi Gemini
     par nahi jayega.
4. v3.8 ke baaki fix waise hi hain: dynamic panel height,
   chips, filler strip, WHATSAPP_PER gate, contact prefix fix,
   auto-dismiss, Files specific folder URI.

## Test results
Kotlin file ke ASLI code par check:
- Parser shortcut maujood hai, aur OPEN regex se:
  "whatsapp open karo" -> naam "whatsapp", shortcut fire
  "whatsapp kholo" -> naam "whatsapp", shortcut fire
  "whatsapp open" -> naam "whatsapp", shortcut fire
- Settings mein hi-IN default ab nahi hai (revert confirm).
- SpeechInput mein languageFallbackDone + forcePhoneDefault
  maujood hai.
- VoiceService mein hint "whatsapp" ka skip maujood hai.
- ActionRunner mein com.whatsapp package fallback maujood hai.
- v3.8 ke features (stripLeadingFiller, WHATSAPP_PER gate,
  dynamic panel) abhi bhi maujood hain.

Brace/paren count badli hui Kotlin files mein balanced hai.
Conflict markers kisi bhi .kt file mein nahi hain.

## Jo test abhi bhi nahi hua hai (imandari se)
- Local Gradle build nahi hua (VM mein Gradle daemon fail hota
  hai). GitHub Actions wala build hi asli compile check hai.
- Sunna wapas aaya ya nahi, ye aapke phone par hi pata chalega,
  main device par test nahi kar sakta. Agar v3.9 mein bhi nahi
  sunta toh Settings mein Language English karke batayein, aur
  panel par jo error message aaye (jaise "Kuch sunai nahi diya"
  ya network wala message), woh bhej dein — usse pata chalega
  ki recognizer fail ho raha hai ya mic hi nahi pahunch raha.

## Badli hui files
- Settings.kt — speechLanguage default revert (hi-IN hataya)
- SpeechInput.kt — language fallback (ek baar, phone default)
- CommandParser.kt — WhatsApp open shortcut (AppIndex se pehle)
- VoiceService.kt — WhatsApp hint ka AppIndex check skip
- ActionRunner.kt — WhatsApp package direct launch
- app/build.gradle.kts — versionCode 17, versionName "3.9"

## Push / build — PEHLE VERSION CHECK KAREIN
1. Zip ko SAATHI folder mein copy karein, nested
   SAATHI\Saathi\... na banne dein, `app/build.gradle.kts`
   zaroor copy ho, Replace all chunna.
2. `git add -A`, `git commit -m "Saathi v3.9"`, `git push`.
   "fetch first" aaye toh pehle `git pull --no-edit origin
   main`, force push kabhi mat karein.
3. Actions mein build hone dein, `app-debug-apk` download
   karke install karein. WhatsApp wali purani APK nahi.
4. **Pehla test version ka hai:** title "Saathi · v3.9"
   dikhna chahiye.
5. Uske baad yeh test karein, isi order mein:
   - Bubble dabayein, kuch bolein — partial text aana chahiye,
     panel "Boliye..." par atakna nahi chahiye
   - "WhatsApp open karo" — WhatsApp khulna chahiye, Gemini
     pata nahi nahi aana chahiye
   - "WhatsApp kholo" — wahi, WhatsApp khulna chahiye
   Agar sunna abhi bhi na aaye toh Settings mein Language
   English karke dobara try karein aur error message bhej dein.
