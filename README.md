# 4peak

Fitness-App für Android mit Uhr-App für Wear OS.

- **Handy (`app/`):** die komplette 4peak-App, also Kalender, Plan aus deinen Zielen, 133 Übungen, Supplements und Ernährung. Die App liest Ruhepuls, HRV und Schlaf aus Health Connect und schickt deine nächsten Trainings an die Uhr.
- **Uhr (`wear/`):** zeigt das heutige Training. Pro Satz stellst du Gewicht und Wiederholungen ein und speicherst mit einem Tipp. Danach läuft der Pausentimer und die Uhr vibriert, wenn es weitergeht. Während des Trainings misst die Uhr Puls und Kalorien über Health Services, zeigt das Training als laufende Aktivität auf dem Zifferblatt und vibriert am Pausenende auch bei dunklem Bildschirm. Dazu kommen eine Kachel und eine Komplikation mit dem nächsten Training, eine Always-on-Ansicht und Bedienung über die Lünette. Das Layout ist auf die Galaxy Watch 8 (40 mm) abgestimmt. Am Ende geht das Training ans Handy, auch wenn das Handy erst später wieder in der Nähe ist.

## Installieren

Jeder Push auf `main` baut beide Apps automatisch (Reiter **Actions** → letzter Lauf → **4peak-apps**).

1. **Handy:** `4peak-HANDY.apk` auf das Handy laden und öffnen. Android fragt einmal, ob du Apps aus dieser Quelle erlauben willst.
2. **Uhr:** `4peak-UHR.apk` muss vom Handy auf die Uhr übertragen werden, zum Beispiel mit der App „Wear Installer 2“ aus dem Play Store. Dafür auf der Uhr unter *Einstellungen → Entwickleroptionen* das *ADB-Debugging* und das *Debugging über WLAN* einschalten.

Alle Builds sind mit demselben Schlüssel (`keystore/debug.keystore`) signiert. Neue Versionen installieren sich deshalb über die alten, und Handy- und Uhr-App erkennen sich gegenseitig.

## Aufbau

| Teil | Technik |
|---|---|
| Handy-App | WebView mit `app/src/main/assets/index.html`, Brücke `TKNative` (Kotlin) |
| Gesundheitsdaten | Health Connect: Ruhepuls, HRV (RMSSD), Schlaf |
| Handy ↔ Uhr | Wearable Data Layer: `/plan` (Handy → Uhr), `/log/<datum>/<zeit>` (Uhr → Handy) |
| Uhr-App | Kotlin, Compose for Wear OS, Health Services, Tiles, Komplikationen, Ongoing Activity |

Die Web-App aktualisieren: `python3 tools/sync-web.py <pfad>/trainingskompass.html`
