# Shipments

A package tracker for Android in the spirit of Parcel and Deliveries on iOS. A fun side project by ChickenMyBobbers: no ads, no accounts, no nonsense.

## Features

- Add a package by tracking number (paste, type, or share text into the app from any email or message)
- Carrier detected automatically, or pick from Ship24's 1,000+ carriers
- Status timeline grouped by day, with progress bar and estimated delivery
- Route map of scan locations (OpenStreetMap, no Google account needed)
- Background refresh with notifications when a status or location changes
- Home screen widget listing packages on the way
- Archive, rename, delete, open on carrier website

## Setup

1. Sign up at [ship24.com](https://www.ship24.com), choose the free Tracking API plan, and copy your Default API key from the dashboard.
2. Install the latest APK from [Releases](../../releases/latest).
3. Open Shipments, go to Settings, paste the key, and tap **Test key**.

## Notes and limits

- **Shipment allowance:** Ship24's free plan covers 10 new tracking numbers per month. Each number added uses one; refreshing numbers you already added doesn't.
- **Key on the phone:** Ship24 recommends keeping API keys out of client apps. That matters for apps you distribute; here it's your own key on your own phone, stored in app-private storage and excluded from backups.
- **Amazon Logistics (TBA...) numbers** are often not trackable by third parties.
- **Map accuracy:** carriers usually report scan locations as text ("ROMULUS, MI, US"), which the phone converts to map points. Expect a rough route, and no map on phones without Google's geocoding service.
- **Background timing:** Android won't run background checks more often than every 15 minutes and may delay them further to save battery.

## Building

GitHub Actions builds a signed release APK on every push to `main` and publishes it as a GitHub Release. It needs one repository secret, `SIGNING_PASSWORD` (Settings > Secrets and variables > Actions), which protects the signing key the workflow creates on its first run.
