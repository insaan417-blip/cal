# QuickMath Overlay: get the APK and install it

## 1. Build the APK on GitHub (free, no Android Studio)
1. Unzip this project.
2. On github.com: **New repository** (make it **Private**), then **uploading an existing file**.
3. Drag in everything from INSIDE the unzipped folder (including the hidden `.github` folder and `keystore`). Click **Commit changes**.
   - If `.github` did not upload: **Add file > Create new file**, name it `.github/workflows/build.yml`, paste the contents of `build-workflow.yml`, commit.
4. Open the **Actions** tab > **Build APK**. It starts by itself (or press **Run workflow**). Wait about 5-10 minutes for the green tick.
5. Open the finished run > **Artifacts** > **QuickMath-APKs** > download > unzip.
   Use `app-release.apk` (or `app-debug.apk`; both use the same signing key).
   If the run is red, open it, copy the error text and send it to me.

## 2. Install on the Moto G45
1. Copy the APK to the phone and tap it. Allow "install unknown apps" for your file manager or browser when asked.
2. If Play Protect warns about an unknown app, tap **Install anyway** (it is your own app).

## 3. First run
1. Open **QuickMath Overlay** > **Start Monitoring**.
2. Allow **Display over other apps** for QuickMath Overlay, go back, press **Start Monitoring** again.
3. Allow notifications.
4. Android asks to record/cast the screen: choose **Entire screen** (not "A single app"), then **Start now**.
5. Switch to your math app. The answer appears in the bubble. Drag it anywhere. Tap it to shrink to a dot, tap the dot to expand.
6. Stop from the app or from the notification's **Stop** button.

Optional: Settings > Apps > QuickMath Overlay > Battery > **Unrestricted**, so Motorola does not kill it.

## Good to know
- Questions need a space on both sides of `-` and `/` (or use the real symbols `−` and `÷`). This is on purpose, so dates like 31/10 are not "solved". (In a stacked question, a minus whose space after it was lost, like `-46`, is still read.)
- Stacked questions (numbers in a column, the sign at the start of each following line) work with up to 30 numbers, any mix of + and -.
- The operator signs are read from the pixels of the screenshot, because the OCR often turns ÷ into +.
- It reacts to any arithmetic it can read on screen, not only quizzes.
- Unclear reads are skipped, never guessed. Divide by zero shows nothing.
- Apps that block screenshots show a black frame, so nothing can be read there.
- Everything runs on the phone. Nothing is uploaded.
