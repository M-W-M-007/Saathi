# Saathi v3.3 — kya fix hua hai

Version: 3.3 (versionCode 11). Pehle wali 3.2 se badli hui files sirf yeh hain:
`app/build.gradle.kts`, `CommandParser.kt`, `ActionRunner.kt`,
`FileOps.kt`, `SaathiAccessibilityService.kt`, `VoiceService.kt`.
Koi nayi permission nahi li gayi hai — CALL_PHONE aur SEND_SMS
jaanboojh kar abhi bhi nahi liye gaye hain (Play Protect wali wajah
README mein likhi hai). Call dialer se aur SMS/WhatsApp chat se hi
honge, ek tap aapka rahega.

## 1. Gemini model (pehle 429 / 404 / 503 aata tha)
- Model list ab: `gemini-3.5-flash-lite`, `gemini-flash-lite-latest`,
  `gemini-3.8-flash`, `gemini-flash-latest`.
- 2.5 / 2.0 / 1.5 hata diye — woh naye users ko 404 dete hain.
- 3.5-flash-lite ko 2026-10-27 ko curl se OK verify kiya gaya tha.
- 429/503 par pehle loop toot jata tha. Ab wahi model 3 second ruk
  kar dobara try hota hai, phir agla model try hota hai. Sirf
  400/401/403 (galat/blocked key) par rukte hain.

## 2. WhatsApp message send nahi hota tha
Asli wajah teen thi, teeno fix ki gayi hain:
- (a) Auto-send ka tap sirf ~3.6 second tak try hota tha, chat
  aksar usse der se khulta tha. Ab ~15 second tak (30 baar, 500ms)
  try hota hai, aur sirf tab tap hota hai jab saamne wali window
  sach mein WhatsApp/Business ho.
- (b) Send button ab sirf id/text se nahi, poore tree mein bhi
  dhoondha jata hai (id mein "send" ho ya contentDescription mein
  Send/भेजें ho) — icon-only button ke liye.
- (c) Business bolne par bhi personal WhatsApp khul jata tha.
  Ab hint ke hisab se wa.me intent usi package par pin hota hai.
- (d) Pehle app jhootha kehta tha "bhej raha hoon" jabki tap fail
  ho gaya hota tha. Ab panel mein saaf likha aayega ki agar send
  na dabe to chat mein send khud dabayein — chat aur message dono
  pehle se khule honge, isliye yeh fallback hamesha kaam karega.

## 3. Call nahi lag rahi thi (dialer tak ja kar rukna)
Design se call dialer mein hi khulti hai, direct call nahi lagta.
Asli dikkat yeh thi: Saathi ka panel screen ke neeche 45% par reh
jata tha aur dialer ka call button dhak deta tha, isliye user
button dabaa hi nahi pata tha. Ab dialer / WhatsApp / SMS khulne
wale kaam ke baad panel 4 second mein khud hat jayega, taaki
neeche wala button dikhe aur dab sake. Agar aap usse pehle
dobara bolna shuru kar dein toh naya panel nahi hatega.

## 4. Folder nahi ban raha tha
- App ka apna example "Downloads mein Bills naam ka folder banao"
  purane parser se milta hi nahi tha (naam folder se pehle hai).
  Ab dono order chalte hain: "Downloads mein Bills naam ka folder
  banao" aur "Bills naam ka folder banao Downloads mein", aur
  purana "Downloads mein folder banao Bills" bhi chalta hai.
- Ek aur bug: alias folder (jaise Download) ke na hone par folder
  Internal storage ke root mein ban jata tha aur Downloads mein
  dikhta hi nahi tha. Ab alias ka asli path hi parent banega.

## 5. File move nahi ho rahi thi
- "resume wali file ko Documents mein move karo" ka destination
  "ko documents" ban jata tha aur folder nahi milta tha. Ab shuru
  ke ko/par/pe markers aur "wali/file" shabd slot se hataye jate
  hain, isliye destination sahi "documents" nikalta hai.
- "resume wali file Documents mein move karo" (bina ko ke) bhi
  ab chalta hai.

## Test karne ka order (naye APK ke baad)
1. App kholein — upar "Saathi · v3.3" likha hona chahiye.
2. "Gemini test karo" ek baar dabayein — "Chal gaya. Model:
   gemini-3.5-flash-lite" aana chahiye.
3. "Downloads mein Bills naam ka folder banao" — phir Files app
   mein Download folder mein Bills dikhna chahiye.
4. Ek test file Download mein rakh kar "us file ko Documents mein
   move karo" bolein — confirm ke baad move honi chahiye.
5. "Ammi ko WhatsApp par likho ki test message hai" — chat khule,
   message likha ho, aur Accessibility on ho toh send dabna chahiye;
   agar send na dabe toh panel mein likha hoga, chat mein send
   khud dabayein.
6. "Ammi ko call karo" — confirm ke baad dialer sahi number ke
   saath khule, panel 4 second mein hat jaye, phir call button
   dabayein. (Direct call design mein nahi hai — yeh Play Protect
   se bachne ke liye jaanboojh kar aisa hai.)

Note: Is VM mein Gradle daemon nahi chala, isliye APK yahan build
nahi hua hai. GitHub Actions wala build hi asli check hai — wahan
build fail ho toh log mein "Build errors" summary dekhein.
