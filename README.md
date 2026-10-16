# Shipments

A package tracker for Android in the spirit of Parcel and Deliveries on iOS. A fun side project by ChickenMyBobbers: no ads, no accounts, no nonsense.

## Features

- Add a package by tracking number (paste, type, or share text into the app from any email or message)
- Carrier detected automatically, or pick from 17TRACK's ~3,000 carriers
- Status timeline grouped by day, with progress bar and estimated delivery
- Route map of scan locations (OpenStreetMap, no Google account needed)
- Background refresh with notifications when a status or location changes
- Home screen widget listing packages on the way
- Archive, rename, delete, open on carrier website

## Setup

1. Get a free API key at [api.17track.net](https://api.17track.net/en).
2. Install the latest APK from [Releases](../../releases/latest).
3. Open Shipments, go to Settings, paste the key, and tap **Test key**.

## Notes and limits

- **Tracking credits:** 17TRACK charges one credit per *new* number registered. Refreshing numbers you already added is free. Per 17TRACK's docs, accounts created after January 7, 2026 get a one-time 200 free credits rather than a monthly refill.
- **Amazon Logistics (TBA...) numbers** are often not trackable by third parties.
- **Map accuracy:** carriers usually report scan locations as text ("ROMULUS, MI, US"), which the phone converts to map points. Expect a rough route, and no map on phones without Google's geocoding service.
- **Background timing:** Android won't run background checks more often than every 15 minutes and may delay them further to save battery.
- The API key is stored in app-private storage on the phone and excluded from backups.

## Building

GitHub Actions builds a signed release APK on every push to `main` and publishes it as a GitHub Release. It needs one repository secret, `SIGNING_PASSWORD` (Settings > Secrets and variables > Actions), which protects the signing key the workflow creates on its first run.
