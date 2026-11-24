# Meal Journal Android

A local-first Android meal journal using an HTML interface inside an Android WebView.

## Features
- Daily meal journal with Breakfast, Lunch, Dinner, Snacks and custom entries
- Local entry storage, meal photos, editing and deletion
- Export and import backups
- Barcode scanning using ZXing
- Product name, brand, ingredients and allergen lookup through Open Food Facts
- No advertisements

## Build
Push this project to GitHub and run the **Build Android APK** workflow in Actions. Download the `meal-journal-debug-apk` artifact and install `app-debug.apk`.

## Product lookup
Barcode lookups use the Open Food Facts public product database. Product coverage and details vary; information may be incomplete or community-submitted. Network access is required.

## Data
Meal entries are stored locally in the app's WebView storage. Export backups periodically. Clearing app data may erase entries.
