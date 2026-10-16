# Shipments

A package tracker for Android in the spirit of Parcel and Deliveries on iOS. A fun side project by ChickenMyBobbers: no ads, no accounts, no nonsense.

## Features

- Add a package by tracking number (paste, type, or share text into the app from any email or message)
- Carrier detected automatically (USPS, UPS, FedEx, DHL and more), or set it yourself
- Status timeline grouped by day, with progress bar and estimated delivery
- Route map of scan locations, drawn as a crisp vector map that follows your theme (OpenFreeMap, no Google account needed)
- Background refresh with notifications when a status or location changes
- Home screen widget listing packages on the way
- Archive, rename, delete, open on carrier website
- Appearance settings: System, Light, Dark or AMOLED theme, Match phone colors, Modern glass style, and custom colors for each part of the app

## Setup

1. Sign up free at [traceapi.dev](https://traceapi.dev) (email only, no card) and create an API key (starts with `trc_`).
2. Install the latest APK from [Releases](../../releases/latest).
3. Open Shipments, go to Settings, paste the key, and tap **Test key**.

## Notes and limits

- **Lookup allowance:** Trace's free plan is 1,000 lookups a month, and every check of every package uses one (cached results included). Background checks default to every 3 hours, and packages checked in the last 30 minutes are skipped when the app opens.
- **Data source:** Trace doesn't say how it gets carrier data, and carrier coverage isn't guaranteed. If a carrier stops returning results, that's on Trace's side.
- **Key on the phone:** Trace recommends keeping API keys on a server. That matters for apps you distribute; here it's your own key on your own phone, stored in app-private storage and excluded from backups.
- **Amazon Logistics (TBA...) numbers** are often not trackable by third parties.
- **Map accuracy:** carriers usually report scan locations as text ("ROMULUS, MI, US"), which the phone converts to map points. Expect a rough route, and no map on phones without Google's geocoding service.
- **Background timing:** Android won't run background checks more often than every 15 minutes and may delay them further to save battery.

## Building

GitHub Actions builds a signed release APK on every push to `main` and publishes it as a GitHub Release. It needs one repository secret, `SIGNING_PASSWORD` (Settings > Secrets and variables > Actions), which protects the signing key the workflow creates on its first run.
