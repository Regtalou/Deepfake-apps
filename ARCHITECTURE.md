# 🧠 DeepfakeDetector — Architecture Industrielle

## Vue d'ensemble

```
┌─────────────────────────────────────────────────────────────────┐
│                     COUCHE PRÉSENTATION                         │
│  MainScreen │ ResultScreen │ HistoryScreen │ CommunityScreen    │
│  EducationScreen │ BacktestScreen                               │
└─────────────────────────┬───────────────────────────────────────┘
                          │ StateFlow / LiveData
┌─────────────────────────▼───────────────────────────────────────┐
│                     COUCHE VIEWMODEL                            │
│  AnalysisViewModel │ HistoryViewModel │ BacktestViewModel       │
│  CommunityViewModel                                              │
└─────────────────────────┬───────────────────────────────────────┘
                          │ Repository Pattern
┌─────────────────────────▼───────────────────────────────────────┐
│                     COUCHE DOMAINE                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              PIPELINE D'ANALYSE                         │   │
│  │                                                         │   │
│  │  1. MetadataAnalyzer   → Score métadonnées             │   │
│  │  2. VideoAnalyzer      → Score pixel + temporel        │   │
│  │  3. AudioAnalyzer      → Score audio                   │   │
│  │  4. FaceAnalyzer       → Score visage + physiologie    │   │
│  │  5. ScoreFusionEngine  → Score global pondéré          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  BacktestEngine │ ExternalVerificationService                   │
└─────────────────────────┬───────────────────────────────────────┘
                          │ Room + DataStore + Retrofit
┌─────────────────────────▼───────────────────────────────────────┐
│                     COUCHE DONNÉES                              │
│  AppDatabase (Room) │ PreferencesDataStore                      │
│  AnalysisRepository │ CommunityRepository                       │
│  ExternalApiService (Retrofit)                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Modules d'Analyse

### 1. MetadataAnalyzer (⚡ Rapide — 0.1s)
- Codec détecter (H.264, H.265, VP9, AV1)
- Date de création / modification
- Logiciel de création (Adobe Premiere, DaVinci, etc.)
- Résolution et framerate inhabituels
- Taille de fichier vs durée anormale

### 2. VideoAnalyzer (⚡ Rapide-Medium — 0.5–3s)
**Niveau 1 — Pixel :**
- Analyse histogramme (distribution anormale des couleurs)
- Détection bruit numérique (AWGN vs bruit GAN)
- Artefacts JPEG/compression anormaux
- Uniformité des textures

**Niveau 2 — Fréquentiel :**
- FFT 2D sur frames échantillonnées
- Détection pics spectraux GAN (fréquences caractéristiques)
- Analyse DCT (coefficients haute fréquence)

**Niveau 3 — Temporel :**
- Optical Flow simplifié (cohérence mouvement)
- Stabilité inter-frames
- Détection scènes (transitions abruptes)
- Flicker temporel (incohérences luminosité)

### 3. AudioAnalyzer (⚡ Medium — 1–2s)
- Analyse spectrographique
- Détection GAN audio (artefacts vocoder)
- Synchronisation lip-sync (audio/vidéo)
- Coupures anormales
- Bruit de fond uniformément plat

### 4. FaceAnalyzer (🔬 Complet — 2–5s)
- Détection ML Kit Face Detection
- Stabilité landmarks faciaux
- Cohérence clignements yeux
- rPPG (Remote PhotoPlethysmoGraphy) — pouls estimé
- Symétrie faciale anormale
- Artefacts aux bords du visage

### 5. ScoreFusionEngine
```
Score_global = Σ(wᵢ × scoreᵢ × confᵢ) / Σ(wᵢ × confᵢ)

Poids dynamiques :
- Metadata : 0.10
- Pixel    : 0.25
- Temporel : 0.20
- Audio    : 0.15
- Visage   : 0.20
- Physio   : 0.10

Ajustement si module absent → redistribution des poids
```

## Modes d'Analyse

| Mode | Durée | Modules actifs | Usage |
|------|-------|----------------|-------|
| INSTANT | ~2s | Metadata + Pixel rapide | Scroll TikTok |
| FAST | 5–10s | + Audio + Temporel | Analyse courante |
| COMPLETE | 15–30s | Tous modules | Investigation |

## Backtest Engine

```
Dataset: 100 vidéos (50 RÉEL + 50 FAKE)
Métriques: Accuracy, Precision, Recall, F1, AUC-ROC
Output: Matrice de confusion + rapport détaillé
Auto-calibration: Ajustement seuils basé sur performances
```

## Stack Technique

| Composant | Technologie |
|-----------|-------------|
| Language | Kotlin 1.9+ |
| UI | Jetpack Compose |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| DB | Room |
| Async | Coroutines + Flow |
| ML | TensorFlow Lite + ML Kit |
| Vision | OpenCV Android |
| Network | Retrofit + OkHttp |
| Serialization | Kotlinx Serialization |
| Media | MediaMetadataRetriever + MediaCodec |

## Sécurité & Transparence
- Toutes les analyses restent locales (pas de vidéo envoyée au cloud)
- Les scores sont explicables et détaillés
- Limites clairement affichées
- Faux positifs/négatifs documentés
