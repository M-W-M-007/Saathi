# Saathi v3.8 — asli Siri-style panel + galat samajh ke fix

Version: versionName "3.8", versionCode 16. Base v3.7 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi
liye gaye hain.

## Pehli baat: title check phir se
Installer par v3.7 dikh raha tha, isliye v3.7 sahi install hua
tha. v3.8 ke liye bhi pehle title "Saathi · v3.8" dekhein. Agar
title purana dikhe toh commands test karne ka koi matlab nahi hai.

## v3.7 video mein jo galat tha, uski wajah (test karke)
1. Panel bada aur khaali: v3.7 mein ScrollView ki height 45%
   par fix thi, chahe details khaali ho. Isliye panel bada dark
   box lagta tha, peeche ka screen thoda hi dikhta tha.
2. "Yahan per document open karo" WhatsApp ban gaya: v3.7 ke
   WHATSAPP_PER pattern mein who="yahan", body="document open
   karo" ban jata tha, phir contact matching galat contact
   utha leta tha.
3. Contact galat dikhna: wordScore mein ek akshar wala display
   shabd ("R") kisi bhi usi akshar se shuru hone wale shabd se
   match ho jata tha (score 0.85), isliye Rubina bolne par "R"
   wale alag contacts aa jate the.
4. Awaz: speech language default phone default (English UI)
   tha, jabki user Hinglish bolta hai. App mein Hindi/English
   setting maujood hai, lekin default Hindi nahi tha.
5. Files button generic: "Files mein kholo" generic Files app
   kholta tha, specific folder nahi.

## v3.8 mein kya badla
1. Siri-style panel, sahi wala:
   - ScrollView ki height ab details ke hisaab se hai. Khaali
     ya chhoti list par WRAP_CONTENT — panel chhota hi rahega.
     Lambi list (8 se zyada) par 45% cap, taaki buttons kabhi
     screen se bahar na jayein aur list scroll ho sake.
   - Neeche ki patti chhoti aur halki (8dp padding, 12sp text).
   - Choices full-width green button nahi, chhote chips hain
     (text jitne chauda, padding kam).
   - Background wahi halka translucent dark, upar ke kone gol.
2. Filler strip + WhatsApp gate:
   - "yahan per / yahan par / iske andar / is chat mein"
     jaise filler shuru se hat jate hain, taaki "yahan per
     document open karo" seedha "document open karo" bane.
   - WHATSAPP_PER ab sirf tabhi chalega jab "whatsapp" shabd
     ho ya body mein message verb ho (bhejo/likho/message/msg).
     "document open karo" mein na whatsapp hai, na message verb,
     isliye woh WhatsApp nahi banega.
3. Contact matching:
   - Prefix match ke liye dono shabd kam se kam 3 akshar ke
     hone chahiye. Ek akshar wala match band ho gaya hai.
   - Agar koi achha match na ho toh galat list dikhane ki
     jagah "contact nahi mila" hi sahi hai — pehle se maujood
     not-found hint wahi kaam karta hai.
4. Speech language default hi-IN:
   - Jo user pehle se English set kar chuka hai, uska English
     hi rahega. Jiska kuch set nahi hai, uska default ab hi-IN
     hai, taaki Hindi shabd sahi sunai dein. Settings mein
     Hindi/English badla ja sakta hai.
5. Wapas jaana / dobara bolna:
   - Simple kaam ke baad panel 4 second mein khud hat jayega
     (folder/list/search ki list tab tak rehti hai jab tak
     user band na kare ya bahar tap na kare, kyunki list
     padhni hoti hai).
   - "Nahi" dabane ke baad panel 2.5 second mein khud hat
     jayega.
   - Bubble dabane par chahe panel khula ho, sunna phir se
     shuru hoga (pehle se tha, ab auto-dismiss ke saath
     milkar wapas jaana aasan hoga).
6. Files mein specific folder:
   - "Files mein kholo" ab generic Files nahi, usi folder ka
     Documents URI try karta hai (system Files app ke liye).
     Na chale toh generic Files par gir jayega, aur message
     mein saaf hoga ki specific folder nahi khula. Ye hissa
     device par hi verify hoga — har Files app is URI ko
     nahi maanti.

## Test results — naye cases PASS
Kotlin file ke ASLI regex par test:
- yahan per document open karo -> stripped "document open
  karo" -> OPEN_FOLDER documents, WHATSAPP gate False
- yahan par document open karo -> wahi, OPEN_FOLDER documents
- iske andar ammi new number per ek message bhejo how are you
  -> WHATSAPP ammi new number / ek message bhejo how are you
- download open karo -> OPEN_FOLDER download
- download mein test folder ko open karo -> OPEN_FOLDER
  download / test
- Contact prefix: purana rubina vs "r" = 0.85, naya = 0.0;
  rubina vs rubina = 1.0 (sahi match abhi bhi milta hai)
- Purane cases bhi PASS: download ke andar best folder hai use
  open karo, test folder ko open karo, call list se shaheen sis
  ko call karo, calling list mein se rubina second number call
  lagao -> rubina

Brace/paren count badli hui Kotlin files mein balanced hai.
XML well-formed hai. Conflict markers kisi bhi .kt file mein
nahi hain.

## Jo test abhi bhi nahi hua hai (imandari se)
- Local Gradle build nahi hua (VM mein Gradle daemon fail hota
  hai). GitHub Actions wala build hi asli compile check hai.
- Panel ka asli look/feel, Documents URI se specific folder
  khulna, aur hi-IN recognizer ka asli sunna — ye sab aapke
  phone par hi pata chalega, main device par test nahi kar
  sakta. Agar panel abhi bhi bada lage toh screenshot bhej
  dein, usi hisaab se aur kam kar dunga.
- Speech recognizer ki galat sunai (jaise naam ka galat
  sunna) code se poori tarah khatam nahi hoti — hi-IN default
  usko behtar karega, lekin 100% guarantee nahi hai.

## Badli hui files
- CommandParser.kt — stripLeadingFiller, WHATSAPP_PER gate
- ContactIndex.kt — wordScore prefix rule (dono shabd >=3)
- Settings.kt — speechLanguage default hi-IN
- Command.kt — OPEN_FOLDER_IN_FILES action
- ActionRunner.kt — Outcome.folderPath, openFolderInFiles
- VoiceService.kt — ScrollView dynamic height, chips, Files
  button specific folder, 4s auto-dismiss, Nahi par 2.5s dismiss
- overlay_panel.xml — neeche ki patti chhoti/halki
- app/build.gradle.kts — versionCode 16, versionName "3.8"

## Push / build — PEHLE VERSION CHECK KAREIN
1. Zip ko SAATHI folder mein copy karein, nested
   SAATHI\Saathi\... na banne dein, `app/build.gradle.kts`
   zaroor copy ho, Replace all chunna.
2. `git add -A`, `git commit -m "Saathi v3.8"`, `git push`.
   "fetch first" aaye toh pehle `git pull --no-edit origin
   main`, force push kabhi mat karein.
3. Actions mein build hone dein, `app-debug-apk` download
   karke install karein. WhatsApp wali purani APK nahi.
4. **Pehla test version ka hai:** title "Saathi · v3.8"
   dikhna chahiye.
5. Uske baad yeh test karein:
   - "open WhatsApp" — panel chhota dikhna chahiye, peeche
     ki screen dikhni chahiye
   - "yahan per document open karo" — Documents folder ki
     list panel par, WhatsApp confirmation nahi
   - "rubina ko call karo" — Rubina ke contacts hi dikhne
     chahiye, "R" wale alag contacts nahi
   - "download mein test folder ko open karo" — panel par
     test folder ki list, phir "Files mein kholo" dabayein
     aur batayein ki Files app specific folder par khuli ya
     generic khuli
   Har command ke baad panel par heard text + tag (offline /
   Gemini) batayein agar fail ho.
