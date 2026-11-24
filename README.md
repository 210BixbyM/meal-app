# Meal Journal Android project

This wraps the existing Meal Journal web app in a lightweight Android WebView shell.

## Build an APK without installing Android Studio
1. Create a free GitHub account and a new repository.
2. Upload all contents of this folder, including the hidden `.github` folder.
3. Open the repository's Actions tab.
4. Select **Build Android APK** and choose **Run workflow** (or wait for the first push build).
5. Open the completed run and download the `meal-journal-debug-apk` artifact.
6. Extract it and install `app-debug.apk` on your Android phone. Android may ask you to allow installs from your browser or file manager.

## Notes
- This is a debug APK for personal testing, not a Play Store release.
- Meal entries are stored locally in the app. Export a backup periodically.
- Photo selection is supported; automatic AI food recognition is not included.
- This version intentionally has no calorie, macro, or weight tracking.
