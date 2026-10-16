# Trainingskompass

Fitness-App für Android mit Uhr-App für Wear OS.

- **Handy (`app/`):** der komplette Trainingskompass, also Kalender, Plan aus deinen Zielen, 133 Übungen, Supplements und Ernährung. Die App liest Ruhepuls, HRV und Schlaf aus Health Connect und schickt deine nächsten Trainings an die Uhr.
- **Uhr (`wear/`):** zeigt das heutige Training. Pro Satz stellst du Gewicht und Wiederholungen ein und speicherst mit einem Tipp. Danach läuft der Pausentimer und die Uhr vibriert, wenn es weitergeht. Während des Trainings misst die Uhr deinen Puls live und zeigt die Zone an. Am Ende geht das Training ans Handy, auch wenn das Handy erst später wieder in der Nähe ist.

## Installieren

Jeder Push auf `main` baut beide Apps automatisch (Reiter **Actions** → letzter Lauf → **trainingskompass-apks**).

1. **Handy:** `trainingskompass-handy.apk` auf das Handy laden und öffnen. Android fragt einmal, ob du Apps aus dieser Quelle erlauben willst.
2. **Uhr:** `trainingskompass-uhr.apk` muss vom Handy auf die Uhr übertragen werden, zum Beispiel mit der App „Wear Installer 2“ aus dem Play Store. Dafür auf der Uhr unter *Einstellungen → Entwickleroptionen* das *ADB-Debugging* und das *Debugging über WLAN* einschalten.

Alle Builds sind mit demselben Schlüssel (`keystore/debug.keystore`) signiert. Neue Versionen installieren sich deshalb über die alten, und Handy- und Uhr-App erkennen sich gegenseitig.

## Aufbau

| Teil | Technik |
|---|---|
| Handy-App | WebView mit `app/src/main/assets/index.html`, Brücke `TKNative` (Kotlin) |
| Gesundheitsdaten | Health Connect: Ruhepuls, HRV (RMSSD), Schlaf |
| Handy ↔ Uhr | Wearable Data Layer: `/plan` (Handy → Uhr), `/log/<datum>/<zeit>` (Uhr → Handy) |
| Uhr-App | Kotlin, Compose for Wear OS, Pulssensor der Uhr |

Die Web-App aktualisieren: `python3 tools/sync-web.py <pfad>/trainingskompass.html`
