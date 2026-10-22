# Speech

<p>
  <img src="docs/screenshots/home_result.png" width="24%">
  <img src="docs/screenshots/home_recording_dark.png" width="24%">
  <img src="docs/screenshots/models.png" width="24%">
  <img src="docs/screenshots/settings_dark.png" width="24%">
</p>

Dictée vocale Android 100 % locale (clone de Wispr Flow). Aucun appel réseau hors
téléchargement initial des modèles depuis Hugging Face.

Cible : Honor Magic7 Pro (Snapdragon 8 Elite), `arm64-v8a` uniquement, minSdk 29.

## Étape 1

- Squelette Compose / Material 3 (couleurs dynamiques).
- Enregistrement `AudioRecord` PCM float 16 kHz mono (`VOICE_RECOGNITION`), micro pré-initialisé.
- Deux moteurs derrière `SttEngine` :
  - **sherpa-onnx** (AAR officiel épinglé) + Parakeet TDT 0.6B v3 int8 ;
  - **whisper.cpp** (sous-module, JNI) + large-v3-turbo q5_0 / q8_0, small q8_0 en secours.
- Téléchargement des modèles avec reprise (Range) et vérification SHA-256.
- Écran **Benchmark** : clips enregistrés sur des phrases de référence, matrice modèles × threads
  (× audio_ctx Whisper), latence, RTF, RAM, WER, état thermique, export en un tap.

## État — étape 2

- **Bulle** (`TYPE_ACCESSIBILITY_OVERLAY` : au-dessus du clavier, aucune autorisation de
  superposition) : tap = démarrer/arrêter, appui long = maintenir pour parler, glisser =
  déplacer avec aimantation au bord, glisser vers ✕ = masquer (revient dans une autre app).
- **AccessibilityService** : détection du champ éditable en focus (hors mots de passe),
  insertion au curseur par `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`, vérifiée ; repli
  presse-papiers + `ACTION_PASTE` avec restauration (quand Android autorise sa lecture).
- **Service de premier plan `microphone`** démarré depuis l'app au premier plan (règle Android
  14+), notification Pause / Désactiver, modèle et `AudioRecord` préchargés. Si le process a
  été tué, la bulle relance le service via une activité relais invisible.

## État — étape 3

- **Modèles** : catalogue épinglé (révision, taille, SHA-256 vérifiés en CI), téléchargement
  avec reprise, service de premier plan, suppression, taille sur disque, modèle actif.
- **VAD Silero** (sherpa-onnx, 643 Ko) téléchargé automatiquement avec le premier modèle :
  pauses ≥ 0,5 s, segments ≤ 20 s, marge de 250 ms autour de la parole.
- Trois modes (Réglages → Reconnaissance, et comparés par le benchmark) :
  bloc entier / silences coupés (défaut, rien n'est inséré si aucune parole) /
  segments aux pauses (latence = dernier segment : simulation de l'étape 5).
- Bulle : apparition « pop » avec rebond, disparition en fondu contracté.

## État — étape 4

- **llama.cpp** (sous-module épinglé b11345) dans un module Android séparé `:llm` (son ggml
  et celui de whisper.cpp ont les mêmes cibles CMake ; deux `.so`, symboles masqués).
- `TextCleaner` / `LlamaCleaner` : Qwen 2.5 1.5B Instruct Q4_0 (défaut) ou 3B, génération
  gloutonne, limite = 1,5 × tokens d'entrée + 16 ; prompt système (règles + exemples) évalué
  une fois et gardé dans le cache KV (préfixe commun réutilisé à chaque dictée), préchargé au
  démarrage du service et au début de chaque enregistrement.
- Garde-fou : sortie vide ou > 1,5 × l'entrée → transcription brute.
- Bascule : Réglages → Nettoyage par IA, ou double tap sur la bulle (étincelle ✦ si actif).
  L'appui long reste « maintenir pour parler ».
- Temps de nettoyage affiché sur l'accueil, journalisé, et mesuré au benchmark (WER avant/après).

## État — étape 5 (en cours)

- **Transcription en direct** (Réglages → Détection de la parole → En direct) : VAD en flux
  pendant l'enregistrement, chaque segment transcrit dès la pause ; au stop il ne reste que
  le dernier segment. Le nettoyage IA pré-calcule le début du texte pendant qu'on parle
  (cache KV), l'appel final ne traite que la fin.
- **Pipeline commun** bulle / essai rapide (`DictationPipeline`).
- **Dictionnaire personnel** : prompt initial Whisper, prompt système du nettoyage, et
  correction des variantes proches (casse, accents, trait d'union, distance d'édition
  pour les termes ≥ 6 lettres) pour tous les moteurs.
- **Historique** des 50 dernières dictées (brut + final), copie en un tap, effaçable.
- **Mémoire** : libération des modèles après 5 / 15 / 60 min sans dictée ; test d'endurance
  de 100 dictées (RSS relevée toutes les 10) dans le benchmark.

- **Accélération matérielle (à mesurer)** :
  - Parakeet : fournisseur onnxruntime CPU / NNAPI (NPU) / XNNPACK, au choix dans le
    benchmark et les réglages (repli CPU si indisponible) ;
  - llama.cpp : variante GPU OpenCL (noyaux Adreno) dans un module `:llmgpu`, liée au
    `libOpenCL.so` du téléphone (`uses-native-library`, chargeur ICD Khronos pour l'édition
    de liens seulement) et chargée à la demande ; repli automatique sur le CPU.

## Moteur par défaut (benchmark étape 1, Magic7 Pro)

| Config | 10 s d'audio → texte | WER |
|---|---|---|
| Parakeet TDT 0.6B v3 int8, 4 threads | 0,69 s | 9,4 % |
| Whisper large-v3-turbo q8_0, 6 threads | 17,8 s | 9,4 % |
| Whisper small q8_0, ctx dynamique | 0,9 s | 18 % |

## Captures d'écran (sans téléphone)

Rendu des écrans sur le JVM (Robolectric + Roborazzi), exclu de la CI :

```bash
./gradlew -Pscreenshots testDebugUnitTest --tests '*Screenshots*'   # → app/build/screenshots/
```

## Build

```bash
git submodule update --init --recursive
./gradlew assembleRelease
```

La CI (`.github/workflows/android.yml`) produit l'APK en artefact à chaque push et vérifie le
catalogue de modèles (`scripts/check_models.py`).

### Signature

Sans secrets, l'APK est signé avec la clé de dev commitée (`app/dev.keystore`) pour que les
mises à jour s'installent par-dessus. Pour une clé privée, ajouter dans les secrets GitHub :
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
(changer de clé impose une désinstallation, donc un nouveau téléchargement des modèles).

## Choix natifs

- `-O3 -march=armv8.2-a+dotprod+fp16+i8mm` (Oryon : pas de SVE).
- ggml sans OpenMP (pool de threads interne), symboles masqués (`--exclude-libs,ALL`).
- Natif toujours compilé en Release, y compris pour le variant debug.
