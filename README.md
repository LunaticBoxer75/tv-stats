# tv-stats

Watch-time stats for an Android TV / Google TV, read from the TV's own usage log: an app that runs on
the TV, and a Python script that reads the TV from a computer over adb.

## On the TV itself (Android app)

`android/` is a small app that runs on the TV, with no computer needed after setup:

| Home panel | Dashboard |
|---|---|
| ![Watch-time panel on the Google TV Home screen](docs/home-panel.png) | ![Full-screen watch-time dashboard](docs/dashboard.png) |

- **Dashboard:** open **Watch time** from your apps for today, the last 7 days and all time per app,
  and a 7-day chart.
- **Home panel (optional, off by default):** a small card under the clock on the Home screen with
  today's total, today's top 3 apps and the last 7 days. Turn it on with the **Home panel** button in the
  app. It can't be selected with the remote and hides as soon as an app opens.
- **Saved history:** the TV deletes per-day detail after about 10 days, so the app saves each day's
  per-app time every 6 hours (and whenever the dashboard opens) to `days.json` in its own storage. All
  time is never lower than the saved days. Uninstalling the app deletes the saved history.

Build with the Android SDK command-line tools (platform 34, build-tools 34.0.0) and JDK 17. Enable adb
on the TV (see [Enable adb](#enable-adb-on-the-tv)), then install and grant its permissions once:

```sh
android/build.sh
adb install -r android/build/tvstats.apk
adb shell appops set dev.tvstats GET_USAGE_STATS allow
adb shell appops set dev.tvstats SYSTEM_ALERT_WINDOW allow   # only for the Home panel
adb shell appops set dev.tvstats APP_AUTO_START allow        # TCL TVs only: lets it start at boot
```

Open **Watch time** from your apps. Saving, and the Home panel if you turn it on, then start by
themselves at every boot. To copy the saved history to your computer:

```sh
adb shell run-as dev.tvstats cat files/days.json > days.json
```

## From a computer (Python script)

`tvstats.py` reads the same data over adb. One file, no dependencies, nothing installed on the TV.

```
▶ Now: CloudStream  (4m this session)

App                 Today  Last 7 days   All time  Launches
Netflix                 -       5h 26m    39h 33m        35
YouTube            1h 21m      17h 13m    24h 16m        63
CloudStream        1h 30m      10h 37m    13h 52m        34
Apps total         2h 51m      33h 16m    78h 28m

Screensaver           16m       2h 21m     3h 51m        27
Google TV Home        53s          49m     1h 17m        86

Last 7 days (apps)
  Sat Oct 10  ██████████████████████████     7h 03m
  ...
Tracking since 10/1/2026, 10:15 PM (start of the TV's yearly record).
```

Install adb (`brew install android-platform-tools` on macOS) and Python 3.9+, enable adb on the TV,
then run it with your TV's IP address (Settings → Network & Internet → your network):

```sh
TV_HOST=192.168.1.20 ./tvstats.py          # live view, refreshes every 5 s (Ctrl-C to quit)
TV_HOST=192.168.1.20 ./tvstats.py report   # print once
```

The script does not save history; the app does.

## Enable adb on the TV

Settings → System → About → press OK on **Android TV OS build** 7 times to enable Developer options.
Then Settings → System → Developer options → turn on **USB debugging** (on some TVs also **Network
debugging**). The first time a computer connects, the TV asks "Allow USB debugging?". Tick **Always
allow from this computer**.

## How it counts

- **Today / Last 7 days:** from the app open and close events. An app is in front from its first
  resumed screen until the screen in front pauses, which is how Android counts `totalTimeUsed`. Days
  split at local midnight in the TV's timezone.
- **What the TV keeps:** per-day detail about 10 days, weekly totals about 4 weeks, monthly about
  6 months, yearly about 2 years. A factory reset erases all of it.
- **All time:** the TV's yearly usage bucket, which starts when the TV was set up (or at its last
  yearly rollover), and never less than the app's saved days. The current session is added live, since
  Android only adds it when the app closes.
- Home screen, screensaver, Settings and similar are listed separately and left out of **Apps total**.
- The script shows unknown apps by the last part of their package name; add a friendly name to
  `LABELS` in `tvstats.py`. The app uses each app's own name.

Tested on a TCL Google TV (Android 12). Other Android TV devices use the same `usagestats` service,
but the output format can vary between Android versions.

## License

MIT
