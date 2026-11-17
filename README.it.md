<div align="center">

# Reels Killer

**Un attimo di pausa prima del prossimo video.**

App Android che si accorge quando stai scorrendo Reels, Shorts o TikTok<br>
e ti chiede, con calma, se vuoi davvero restare.

[![Build](https://github.com/MichaelZama/reels-killer/actions/workflows/build.yml/badge.svg)](https://github.com/MichaelZama/reels-killer/actions/workflows/build.yml)
[![Ultima release](https://img.shields.io/github/v/release/MichaelZama/reels-killer?label=release)](https://github.com/MichaelZama/reels-killer/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![Licenza GPL-3.0](https://img.shields.io/badge/licenza-GPL--3.0-blue)](LICENSE)

**[⬇️ Scarica l'ultima versione](https://github.com/MichaelZama/reels-killer/releases/latest)**

[English](README.md) · **Italiano**

</div>

---

## Come funziona

Quando entri nei video brevi, sopra il player compare una schermata di pausa. Il resto dell'app continua a funzionare: messaggi, video normali, ricerca.

- **Esci** ti porta fuori dai video brevi.
- **Continua** ti lascia guardare per qualche secondo. Se sei ancora lì, la schermata torna.

Non è un divieto: scorrere diventa solo un po' più scomodo, abbastanza da farti chiedere se ne hai voglia davvero.

## Funzioni

| | |
|---|---|
| 🎯 **Mirato** | Interviene solo su Reels di Instagram, Shorts di YouTube, TikTok e Reels di Facebook. Un reel ricevuto in chat si guarda per intero: il blocco scatta al primo swipe. |
| ⏳ **Attesa crescente** | A ogni "Continua" di fila l'attesa prima di poter continuare si allunga: 2, 5, 10, 15, 20, 30 secondi. |
| 🪜 **Livelli** | Ogni livello (primo blocco, dopo un Continua, dopo due…) ha il suo messaggio e, se vuoi, la sua immagine, GIF o video con audio. Un livello senza media usa quello del precedente. |
| 🔇 **Audio** | Durante la pausa l'audio del video viene silenziato e poi ripristinato. |
| 🔒 **Privato** | Nessun permesso internet: nessun dato lascia il telefono. Non legge i tuoi messaggi e non salva il contenuto dello schermo. |
| 🌙 **Calmo** | Tema scuro, nessun colore allarmante, "Esci" sempre più facile di "Continua". |
| 🌍 **Lingue** | Italiano e inglese, con selettore nell'app. |

## Installazione

1. Scarica l'APK dall'**[ultima release](https://github.com/MichaelZama/reels-killer/releases/latest)** e aprilo. Se richiesto, consenti al browser di installare app.
2. Apri Reels Killer e segui i tre passaggi del primo avvio.
3. Attiva il servizio di accessibilità di Reels Killer quando l'app te lo chiede.

<details>
<summary><b>L'interruttore dell'accessibilità è grigio ("Impostazione con limitazioni")</b></summary>

<br>

Da Android 13 il sistema protegge l'accessibilità per le app installate fuori dal Play Store.

1. Tocca la voce grigia di Reels Killer nelle impostazioni di accessibilità e chiudi l'avviso.
2. Apri *Impostazioni › App › Reels Killer*, tocca **⋮** in alto a destra e scegli **Consenti impostazioni con limitazioni**.
3. Torna nelle impostazioni di accessibilità e attiva il servizio.

</details>

<details>
<summary><b>Google Play Protect blocca l'installazione</b></summary>

<br>

Play Protect blocca le app scaricate che chiedono l'accessibilità. Nel Play Store tocca il tuo profilo › **Play Protect** › ⚙️ e disattiva temporaneamente **Scansiona app con Play Protect**, installa l'APK, poi riattivalo.

</details>

<details>
<summary><b>Xiaomi, Redmi, POCO, Samsung, Oppo, Vivo, Huawei</b></summary>

<br>

Questi telefoni chiudono le app in background in modo aggressivo. Nelle informazioni dell'app:

- attiva **Avvio automatico**;
- imposta la batteria su **Nessuna restrizione**;
- evita **Chiusura forzata**, che stacca il servizio di accessibilità.

Se il servizio si ferma, la schermata principale dell'app lo segnala con "Servizio fermato dal sistema" e un pulsante per sistemarlo.

</details>

> [!NOTE]
> Su Android 17 la *Protezione avanzata* può impedire alle app non di accessibilità di usare questo permesso. Se è attiva, Reels Killer non può funzionare.

## Aggiornamenti

Scarica il nuovo APK dalle [release](https://github.com/MichaelZama/reels-killer/releases), poi in Reels Killer apri la sezione **Aggiornamento** e tocca **Installa aggiornamento da file APK**.

Installato così, l'aggiornamento mantiene impostazioni e permessi e non richiede di riconsentire le impostazioni con limitazioni.

## Permessi

| Permesso | A cosa serve |
|---|---|
| Accessibilità | Capire quale schermata è aperta e mostrare la pausa sopra il player. |
| Esclusione dal risparmio batteria | Evitare che il sistema fermi il servizio. |
| Notifiche | La notifica silenziosa "Reels Killer attivo". |
| Installazione app | Solo per il pulsante "Installa aggiornamento", con un file scelto da te. |

Internet non è tra questi.

## Se il blocco non scatta

Le app cambiano spesso la loro interfaccia, e il rilevamento si basa su regole in [`rules.json`](app/src/main/assets/rules.json).

1. In Reels Killer apri **Diagnostica** e tocca **Esporta albero tra 10 s**.
2. Entro 10 secondi apri la schermata che non viene riconosciuta e aspetta.
3. Torna nell'app e tocca **Condividi ultimo albero**. Il file contiene solo la struttura della schermata: i testi sono sostituiti dalla loro lunghezza.

Con quel file si possono aggiornare le regole. [Apri una issue](https://github.com/MichaelZama/reels-killer/issues) allegandolo.

## Compilare da sorgente

Kotlin e Jetpack Compose, `minSdk` 26. Le build girano su GitHub Actions: ogni push su `main` produce un APK tra gli artefatti, ogni tag `v*` pubblica una release.

In locale servono JDK 17 e Android SDK:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Per firmare le release la CI legge quattro secrets del repository: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Senza secrets le build da `main` vengono firmate con una chiave di debug, mentre un tag `v*` si ferma invece di pubblicare una release che non si aggiornerebbe sopra le precedenti.

## Licenza

[GPL-3.0](LICENSE)
