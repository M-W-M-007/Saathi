# Saathi v3.7 — Siri-style UI + perfect karne ki koshish

Version: versionName "3.7", versionCode 15. Base v3.6 par bana hai.
Play Protect design wahi hai: CALL_PHONE / SEND_SMS abhi bhi nahi
liye gaye hain.

## Pehli baat: is baar title check sabse zaroori hai
28 Oct ki video mein title "Saathi · v3.2" tha, isliye v3.3–v3.6
ke fix phone par the hi nahi. v3.7 ko test karne se pehle title
"Saathi · v3.7" dekhna zaroori hai. Agar title purana dikhe toh
commands test karne ka koi matlab nahi hai — pehle title bata dein.

## Is video ki boli par test-first
v3.6 ke asli code par is video ki boli test ki gayi thi:
- PASS tha v3.6 mein bhi: "download ke andar ek best folder
  hai use open karo", "test folder ko open karo", Business open.
- FAIL tha v3.6 mein bhi, isliye v3.7 mein fix hai:
  "download open karo", "documents open karo", "ismein ammi new
  number per...", "calling list mein se rubina second number
  call lagao", dated PDF ka naam (number tootna + "and"),
  "normal text file create kar", "test ko open karke dikhao".

## v3.7 mein kya badla
1. Siri-style UI:
   - Panel ki fixed 32% height khatam. Ab panel sirf utna hi
     lamba hai jitna uska content hai (WRAP_CONTENT), taaki
     peeche ki app dikhti rahe aur screen cover na ho.
   - Lambi folder list ke liye ScrollView 45% par cap hai,
     taaki buttons kabhi screen se bahar na jayein.
   - Background halka translucent dark (#E61C1E26), upar ke
     kone 24dp gol, text thoda chhota, padding kam.
   - Doosri app khulne ke baad panel 2.5 second ki jagah
     1.2 second mein hat jata hai, taaki call/send button
     turant dikhe.
2. Folder Saathi ke andar khulta hai:
   - Pehle Files app khud khul jati thi aur Recent/generic
     jagah dikhti thi, isliye user ko lagta tha "kewal file
     manager khula, folder nahi khula".
   - Ab Files app khud nahi khulti. Folder ki list panel par
     dikhti hai, panel tab tak rehta hai jab tak user band na
     kare, aur panel par "Files mein kholo" ka button milta
     hai agar Files app mein bhi dekhna ho.
3. Alias folder open: "download open karo", "documents open
   karo", "pictures/music/movies open karo" ab OPEN_FOLDER
   hain, generic Files nahi.
4. Naam saaf karna:
   - "ismein" / "is" / "ye" naam ke shuru se hat jata hai.
   - "calling" aur "second number" / "first number" call ke
     naam se hat jate hain, taaki "rubina" par contact choices
     dikhein. "Ammi New Number" jaise naam ko chheda nahi hai.
5. File ka naam fuzzy:
   - "and" shabd search se hata diya jata hai.
   - Edit-distance wala fuzzy rank add hua hai (full naam par
     bhi, extension ke saath), taaki saal wala naam jo sunne
     mein toot jata hai, woh bhi mile. Move karne se pehle
     confirmation mein asli file ka naam dikhta hai, isliye
     galat file chupke se move nahi hogi.
6. Text file banana: naya CREATE_FILE action. "documents mein
   notes naam ki text file banao" aur "ek normal text file
   create karo" (naam na ho toh "New Text File") ab kaam
   karenge. Pehle se maujood naam par overwrite nahi hoga.
7. App nahi mili toh file/folder ka rasta: "test ko open karke
   dikhao" par ab sirf Notes nahi milega — "Folder kholo:
   test" aur "File dhoondho: test" ki choices bhi milengi.

## Test results — naye cases PASS, purane bhi
Kotlin file ke ASLI regex par test:
- download open karo -> OPEN_FOLDER download
- documents open karo -> OPEN_FOLDER documents
- ismein ammi new number per ek message bhejo hay how r u
  -> WHATSAPP ammi new number / ek message bhejo hay how r u
- calling list mein se rubina second number call lagao
  -> CALL rubina
- documents mein notes naam ki text file banao
  -> CREATE_FILE documents / notes
- ek normal text file create karo -> CREATE_FILE / New Text File
- Fuzzy file: "toidelhi20002625pdf" vs asli "toidelhi20261025pdf"
  edit distance 4, threshold 5 — PASS (pehle stem par 7 tha, fail)
- Purane v3.6 ke folder/call cases (test folder ko open karo,
  call list se shaheen sis ko call karo, etc.) abhi bhi PASS.

Brace/paren count badli hui Kotlin files mein balanced hai.
XML (overlay_panel, panel_bg) well-formed check kiya gaya hai.
Conflict markers kisi bhi .kt file mein nahi hain.

## Jo test abhi bhi nahi hua hai (imandari se)
- Local Gradle build nahi hua (VM mein Gradle daemon fail hota
  hai). GitHub Actions wala build hi asli compile check hai.
- Siri-style panel ka asli look/feel, folder list ka panel par
  dikhna, aur Business app ka asli package/label — ye sab
  aapke phone par hi pata chalega, main device par test nahi
  kar sakta. Agar panel abhi bhi bada lage toh screenshot
  bhej dein, usi hisaab se height aur kam kar dunga.
- Multi-step lambi boli ("folder banakar usme text file banao"
  ek hi saans mein) abhi bhi Gemini ke plan par nirbhar hai;
  single-step text file ab offline kaam karega.

## Badli hui files
- CommandParser.kt — ALIAS_FOLDER_OPEN, CREATE_FILE_PARENT,
  cleanNamePrefix, cleanCallTarget mein calling/second number
- Command.kt — CREATE_FILE action
- ActionRunner.kt — createFile, openFolder mein Files auto-launch hataya
- FileOps.kt — fuzzyKey, edit-distance fuzzy rank
- VoiceService.kt — Siri panel (WRAP_CONTENT, 1.2s dismiss),
  folder par Files button, app-nahi-mili par file/folder choices
- overlay_panel.xml, panel_bg.xml — Siri-style chhota panel
- app/build.gradle.kts — versionCode 15, versionName "3.7"

## Push / build — PEHLE VERSION CHECK KAREIN
1. Zip ko SAATHI folder mein copy karein. `app/build.gradle.kts`
   zaroor copy ho. Purani WhatsApp wali APK dobara install mat
   karein — Actions se nayi `app-debug-apk` hi install karein.
2. `git add -A`, `git commit -m "Saathi v3.7"`, `git push`.
   "fetch first" aaye toh pehle `git pull --no-edit origin
   main`, force push kabhi mat karein.
3. Actions mein build hone dein, `app-debug-apk` download
   karke install karein.
4. **Pehla test version ka hai:** app ka title "Saathi · v3.7"
   dikhna chahiye. Agar title purana dikhe toh commands test
   karne ka koi matlab nahi hai, pehle title bata dein.
5. Uske baad yeh test karein:
   - "WhatsApp Business open karo"
   - "download open karo" — panel par Download ki list dikhni
     chahiye, generic Files nahi khulna chahiye
   - "download mein test folder ko open karo"
   - "ismein ammi new number per ek message bhejo hay how r u"
   - "calling list mein se rubina second number call lagao"
   - "documents mein notes naam ki text file banao"
   Har command ke baad panel par jo heard text + tag (offline /
   Gemini) dikhe, woh bata dein agar fail ho.
