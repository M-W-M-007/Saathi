# Saathi

Awaaz se phone chalane wali app. Hinglish samajhti hai.

Build: GitHub par push karein → Actions → **app-debug-apk** download.
Ya Android Studio: File → Open → yeh folder → Build APK.

---

## Kya kar sakti hai

| Boliye | Hota kya hai |
|---|---|
| "WhatsApp kholo" | koi bhi installed app khulti hai |
| "Ramesh ko call karo" | dialer khulta hai, number laga hua |
| "Mummy ko SMS bhejo ki ghar aa raha hoon" | SMS app khulta hai, message likha hua |
| "Ramesh ko WhatsApp bhejo ki kal milte hain" | chat khulta hai, message likha hua |
| "Downloads mein Bills folder banao" | folder banta hai |
| "resume wali file Documents mein move karo" | file move |
| "Downloads mein kya hai" | list |
| "likho namaste kaise ho" | jahan cursor hai wahan type (Accessibility chahiye) |

---

## Do engine, samajhne ke liye

**Rules (default)** — keyword se kaam. Offline, turant, lekin seedhe commands hi.

**Gemini (API key daalne par)** — ghumi-phiri baat bhi samajh leta hai, aur
installed apps ke naam jaanta hai. Internet chahiye.

Key na ho toh app rules par chalti hai. Key ho toh pehle Gemini, woh fail ho
jaaye toh chupchap rules par chali jaati hai — app kabhi band nahi hoti.

**Gemini ko kya bheja jaata hai:** sirf aapka bola hua vaakya aur installed apps
ke naam. Contacts, phone numbers, file ke naam ya file ka content kabhi nahi.

---

## Permissions — aur har ek kyun chahiye

| Permission | Kiske liye | Bina iske |
|---|---|---|
| Mic | sunne ke liye | kuch nahi chalega |
| Contacts | naam se number nikalne | sirf number bol kar call |
| Display over apps | bubble | sirf app ke andar se chalega |
| All files access | file ka kaam | folder/move/copy kuch nahi |
| **Accessibility** | kisi bhi app mein likhna, WhatsApp send dabana | baaki sab chalta hai |

### CALL aur SMS ki permission jaanbujh kar nahi li

Pehle li thi, aur **Play Protect ne app install hi nahi hone di.** Wajah: SMS
bhejna + call lagana + Accessibility + saari files — yeh theek wahi set hai jo
banking malware maangta hai, isliye scanner sideloaded app ko rok deta hai.

Ab call karne par dialer number ke saath khulta hai aur SMS app message ke saath —
ek tap aap dabate ho. Feature wahi rehta hai, aur app install ho jaati hai.

**Accessibility ke baare mein saaf baat:** yeh screen reader wali permission hai —
isse app screen ka content padh sakti hai. Maine ise sirf do kaam ke liye use
kiya hai (text box mein likhna, send dabana), aur service apne aap kuch nahi
karti — sirf tab chalti hai jab aap command bolte ho. Phir bhi, yeh sabse bhaari
permission hai; na dena chaho toh app ke baaki 90% feature waise hi chalte hain.

---

## Confirmation

Call, SMS, WhatsApp, move aur delete se pehle app poochti hai — kyunki voice
recognition naam aur number galat sun leti hai, aur galat aadmi ko call ya galat
file delete ho jaana theek nahi. Bharosa ban jaye toh setting se band kar dein.

---

## Jo abhi nahi hai

**"Hey Saathi" wake word.** Uske liye mic 24 ghante chalu rakhna padta hai —
battery khatam aur galat trigger. Pehle dekhte hain ki command samajhna kitna
sahi ho raha hai; wake word baad mein jodna aasaan hai.

**Har app ka apna kaam** (jaise "Instagram par post karo") — har app ka apna
layout hota hai, aur Accessibility se ek-ek app ke liye alag code likhna padta
hai. Abhi WhatsApp ka send button hi handle kiya hai.

---

## Agar kuch galat ho

App ki **"Pichhle commands"** list dekhiye — usme likha hota hai ki app ne kya
**suna**. Aksar asli dikkat wahi hoti hai: "Ramesh" ko "Dinesh" suna, ya folder
ka naam adhoora aaya. Command badalne se pehle yeh dekh lena time bachata hai.
