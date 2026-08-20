# Noise Logger

Android app for recording audio and logging noise levels with GPS coordinates. Designed for documenting noise disturbances.

## Features

- **Continuous Recording** - Records audio as M4A files, auto-splits every 30 minutes
- **Noise Level Logging** - Logs decibel levels every second to CSV
- **GPS Coordinates** - Captures location and elevation at recording start
- **Background Service** - Keeps recording when screen is off
- **Share & Export** - Share recordings and logs via Google Drive, email, etc.

## Requirements

- Android 7.0+ (API 24+)
- Microphone permission (required)
- Location permission (optional, for GPS coordinates)

## Installation

Download the latest APK from [Releases](https://github.com/alexkuklin/noise-logger/releases).

## Data Storage

Files are saved to:
```
Android/data/com.noiselogger/files/NoiseLogger/
├── recordings/          # Audio files (M4A)
└── logs/               # CSV log files
```

### CSV Format

```csv
# Noise Logger Session
# Started: 2024-01-15 22:30:00
# Location: lat=37.123456, lon=-122.123456, alt=50.0m, accuracy=10.0m
#
timestamp,db_level,latitude,longitude,altitude,recording_file
2024-01-15 22:30:01,45.2,37.123456,-122.123456,50.0,recording_2024-01-15_22-30-00.m4a
```

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## Creating a Release

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will automatically build and create a release with the APK.

## License

MIT
