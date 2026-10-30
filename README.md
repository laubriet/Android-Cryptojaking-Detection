# Monitoring – POC de détection de cryptominer (Android 14+)

Dashboard live (rafraîchi toutes les 2 s) : tension batterie, températures, CPU, services, accès réseau, avec alertes heuristiques.

## Ce qui fonctionne sans / avec Shizuku
| Métrique | Sans Shizuku | Avec Shizuku (UID shell) |
|---|---|---|
| Tension, courant, niveau batterie | ✅ | ✅ |
| Température batterie + statut thermique | ✅ | ✅ + zones `/sys/class/thermal` |
| CPU global + top process | ❌ | ✅ (`/proc/stat`, `top`) |
| Services en cours | ❌ | ✅ (`dumpsys activity services`) |
| Réseau | débit total | ✅ + connexions par app (`/proc/net/tcp*`, `udp*`) |

## Mise en route
1. Installer l'app : `./gradlew :androidApp:assembleDebug` puis `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`
2. Installer [Shizuku](https://shizuku.rikka.app/) sur la tablette, puis le démarrer :
   `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` (ou via le débogage sans fil dans l'app Shizuku).
3. Ouvrir Monitoring → « Autoriser via Shizuku ».

## Alertes (`shared/src/commonMain/.../Detector.kt`)
- Process ≥ 70 % CPU : WARN après 5 ticks (~10 s), CRITICAL après 15 (~30 s). CPU global ≥ 80 % soutenu : WARN.
- Connexion vers un port de pool connu (3333, 4444, 5555, 7777, 14444…) : CRITICAL.
- Nom de process / service / paquet contenant `xmrig`, `miner`, `stratum`… : CRITICAL.
- Batterie > 45 °C, zone > 80 °C, statut thermique ≥ sévère.

## Simuler un mineur
- Charge CPU : `adb shell "yes > /dev/null &"` (plusieurs fois), arrêt : `adb shell pkill yes`
- Connexion « pool » : sur le PC `nc -l 3333`, puis `adb shell "toybox nc <ip_pc> 3333"`

## Structure
- `shared/src/commonMain` : modèles, parsers (texte shell → données), détecteur, ViewModel, UI Compose.
- `shared/src/androidMain` : `AndroidMetricsSource` (API Android + Shizuku), `ShizukuShell`.
- `androidApp` : `MainActivity`, manifest (provider Shizuku).

Tests : `./gradlew :shared:allTests`
