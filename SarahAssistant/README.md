# Sarah — Android starter app

Sarah is a native Kotlin + Jetpack Compose starter for Subhan's Roman Urdu AI assistant. The project is laid out for Android Studio and has a Gradle wrapper.

## Jo abhi kaam karta hai
- First-run screen par Google Gemini API key aur supported `generateContent` models ki live list; model IDs guess nahi kiye jate.
- Optional ElevenLabs speech output; API key + voice ID chahiye. Warna Android TextToSpeech fallback.
- Mic button se Urdu speech recognition (device ke speech service par depend karta hai), chat history aur editable short memory.
- Roman Urdu response prompt, dark/light theme, settings/About (`Created by Subhan`).
- YouTube search kholna aur Android home screen par jana.
- WhatsApp intent recipient number aur text ke saath khulta hai; app message ko khud se send nahi karti—WhatsApp mein aap final Send dabate hain.
- App data Android private storage mein rehta hai; API settings encrypted preferences mein rakhi jati hain. Agar device ka crypto provider unavailable ho to compatibility fallback plain app-private preferences use karta hai. API keys ko APK/share mein hardcode na karein.

## APK build karna
1. Android Studio (JDK 17 ke saath) mein `SarahAssistant` folder open karein.
2. Gradle sync hone dein; Android SDK Platform 35 install karne ko kahe to install karein.
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)` select karein.
4. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
5. Command line par: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).

Is workspace mein Android SDK/Gradle dependencies ke Maven access available nahi the, is liye yahan APK compile/test nahi ho saka. Android Studio mein pehli sync ke waqt dependencies internet se download honi chahiye.

## Zaroori Android/WhatsApp hudood
Android kisi normal app ko screen lock PIN/biometric ke baghair phone unlock nahi karne deta. Background mein hamesha sunta wake-word service battery, foreground-service notification, microphone permission aur OS restrictions ke baghair reliable nahi hoti; is starter mein always-on wake word implement nahi hai. Notification reading, screen capture aur Accessibility automation ke liye alag OS consent aur dedicated, auditable implementation chahiye—ye build chupke se monitor ya tap nahi karta. WhatsApp auto-send bhi nahi hota; user confirmation rakhi gayi hai.

This is a functional starter, not a claim that every phone action can be automated or that every device/model will work without network/API quota constraints. Keep keys private and check Google/ElevenLabs account usage and billing.
