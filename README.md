# Murmure

Dictée vocale Android 100 % locale (clone de Wispr Flow). Aucun appel réseau hors
téléchargement initial des modèles depuis Hugging Face.

Cible : Honor Magic7 Pro (Snapdragon 8 Elite), `arm64-v8a` uniquement, minSdk 29.

## État — étape 1

- Squelette Compose / Material 3 (couleurs dynamiques).
- Enregistrement `AudioRecord` PCM float 16 kHz mono (`VOICE_RECOGNITION`), micro pré-initialisé.
- Deux moteurs derrière `SttEngine` :
  - **sherpa-onnx** (AAR officiel épinglé) + Parakeet TDT 0.6B v3 int8 ;
  - **whisper.cpp** (sous-module, JNI) + large-v3-turbo q5_0 / q8_0, small q8_0 en secours.
- Téléchargement des modèles avec reprise (Range) et vérification SHA-256.
- Écran **Benchmark** : clips enregistrés sur des phrases de référence, matrice modèles × threads
  (× audio_ctx Whisper), latence, RTF, RAM, WER, état thermique, export en un tap.

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
