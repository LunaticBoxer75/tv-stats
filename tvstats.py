#!/usr/bin/python3
"""Watch-time stats for an Android / Google TV, read over adb from the TV's own usage log.

  tvstats.py            live view, refreshes every 5 seconds (Ctrl-C to quit)
  tvstats.py report     print once and exit

Today and the last 7 days come from the TV's app open/close events (kept about 10 days).
All time comes from the TV's yearly usage bucket (since setup, for a TV less than a year old).
"""
import os
import re
import shutil
import subprocess
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

TV = os.environ.get('TV_HOST', '')
if TV and ':' not in TV:
    TV += ':5555'
ADB = os.environ.get('ADB') or shutil.which('adb') or '/opt/homebrew/bin/adb'
REFRESH = 5

LABELS = {
    'com.netflix.ninja': 'Netflix',
    'com.google.android.youtube.tv': 'YouTube',
    'com.google.android.youtube.tvunplugged': 'YouTube TV',
    'com.google.android.youtube.tvkids': 'YouTube Kids',
    'com.amazon.amazonvideo.livingroom': 'Prime Video',
    'com.disney.disneyplus': 'Disney+',
    'com.hulu.livingroomplus': 'Hulu',
    'com.wbd.stream': 'Max',
    'com.apple.atve.androidtv.appletv': 'Apple TV',
    'com.spotify.tv.android': 'Spotify',
    'com.lagradost.cloudstream3': 'CloudStream',
    'com.lagradost.cloudstream3.prerelease': 'CloudStream (pre-release)',
    'com.graymatrix.did': 'ZEE5',
    'in.startv.hotstar': 'Hotstar',
    'com.plexapp.android': 'Plex',
    'org.jellyfin.androidtv': 'Jellyfin',
    'org.xbmc.kodi': 'Kodi',
    'com.teamsmart.videomanager.tv': 'SmartTube',
    'com.esaba.downloader': 'Downloader',
    'com.google.android.videos': 'Google TV (Play Movies)',
    'com.google.android.apps.mediashell': 'Chromecast',
    'com.tcl.airplay2': 'AirPlay',
    'com.google.android.apps.tv.launcherx': 'Google TV Home',
    'com.spocky.projengmenu': 'Projectivy Home',
    'com.google.android.apps.tv.dreamx': 'Screensaver',
    'com.android.tv.settings': 'Settings',
    'com.android.vending': 'Play Store',
    'com.google.android.katniss': 'Assistant / Search',
}
# Shown below the apps, dimmed: time on screen that is not watching something.
SYSTEM = {'com.google.android.apps.tv.launcherx', 'com.spocky.projengmenu', 'com.google.android.apps.tv.dreamx',
          'com.android.tv.settings', 'com.android.vending', 'com.google.android.katniss', 'com.android.systemui',
          'com.google.android.permissioncontroller', 'com.google.android.tungsten.setupwraith', 'android'}

BOLD, DIM, CYAN, GREEN, RESET = '\033[1m', '\033[2m', '\033[36m', '\033[32m', '\033[0m'


def label(pkg):
    return LABELS.get(pkg) or pkg.split('.')[-1].replace('_', ' ').title()


def adb(*args):
    result = subprocess.run([ADB, '-s', TV, *args], capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return result.stdout


def connect():
    if TV + '\tdevice' not in subprocess.run([ADB, 'devices'], capture_output=True, text=True).stdout:
        out = subprocess.run([ADB, 'connect', TV], capture_output=True, text=True, timeout=15).stdout
        if 'connected' not in out:
            raise RuntimeError('Cannot reach the TV at %s: %s' % (TV, out.strip()))


def duration(text):
    """'43:03' (m:s), '1:17:57' or '44:22:26' (h:m:s) -> seconds."""
    seconds = 0
    for part in text.split(':'):
        seconds = seconds * 60 + int(part)
    return seconds


def fmt(seconds):
    seconds = int(seconds)
    if seconds < 60:
        return '%ds' % seconds if seconds else '-'
    h, m = divmod(seconds // 60, 60)
    return '%dh %02dm' % (h, m) if h else '%dm' % m


EVENT = re.compile(r'time=("[^"]+"|\d+) type=(\w+)(?: package=(\S+))?(?: class=(\S+))?')
ROW = re.compile(r'package=(\S+) totalTimeUsed="([\d:]+)".*?appLaunchCount=(\d+)')


def read_tv(tz):
    """Events (ms, type, package, class) from on-disk daily files, then any newer ones from the
    in-memory last 24 hours, in the TV's own order; plus the yearly bucket (all time since setup)."""
    events = []
    for line in adb('shell', 'dumpsys usagestats file 0 0').splitlines():  # 0 = daily interval
        m = EVENT.search(line)
        if m:
            events.append((int(m.group(1)), m.group(2), m.group(3), m.group(4)))
    last = max((e[0] for e in events), default=0)
    current = adb('shell', 'dumpsys usagestats')
    for line in current.split('In-memory daily stats')[0].splitlines():
        m = EVENT.search(line)
        if m:
            when = datetime.strptime(m.group(1).strip('"'), '%Y-%m-%d %H:%M:%S').replace(tzinfo=tz)
            ms = int(when.timestamp()) * 1000
            if ms > last + 999:  # seconds-only times: skip anything the files already hold
                events.append((ms, m.group(2), m.group(3), m.group(4)))
    events.sort(key=lambda e: e[0])  # stable: same-time events keep the TV's order
    yearly = current.split('In-memory yearly stats')[1]
    since = re.search(r'timeRange="([^"]+?) – ', yearly).group(1)
    totals = {pkg: (duration(used), int(launches)) for pkg, used, launches in ROW.findall(yearly)}
    return events, totals, since


def sessions(events, now_ms):
    """Foreground sessions (package, start ms, end ms), the way Android counts totalTimeUsed:
    an app is in front from its first resumed screen until the screen in front pauses."""
    out, fg, shown, start = [], None, None, 0
    for t, kind, pkg, cls in events:
        if kind == 'ACTIVITY_RESUMED':
            if fg != pkg:
                if fg:
                    out.append((fg, start, t))
                fg, start = pkg, t
            shown = cls
        elif kind == 'ACTIVITY_PAUSED' and pkg == fg and cls == shown:
            out.append((fg, start, t))
            fg = None
        elif kind == 'DEVICE_SHUTDOWN' and fg:
            out.append((fg, start, t))
            fg = None
    if fg:
        out.append((fg, start, now_ms))
    return out, (fg, start) if fg else None


def by_day(spans, tz):
    """Split sessions at local midnight -> {date: {package: seconds}}."""
    days = defaultdict(lambda: defaultdict(float))
    for pkg, start, end in spans:
        a, b = datetime.fromtimestamp(start / 1000, tz), datetime.fromtimestamp(end / 1000, tz)
        while a < b:
            midnight = datetime.combine(a.date() + timedelta(days=1), datetime.min.time(), tz)
            cut = min(b, midnight)
            days[a.date()][pkg] += (cut - a).total_seconds()
            a = cut
    return days


def render(tz):
    now = datetime.now(tz)
    events, totals, since = read_tv(tz)
    spans, ongoing = sessions(events, int(now.timestamp() * 1000))
    days = by_day(spans, tz)
    today = now.date()
    week = [today - timedelta(days=i) for i in range(6, -1, -1)]
    today_s = days.get(today, {})
    week_s = defaultdict(float)
    for d in week:
        for pkg, s in days.get(d, {}).items():
            week_s[pkg] += s
    ongoing_s = (now.timestamp() * 1000 - ongoing[1]) / 1000 if ongoing else 0
    all_s = {pkg: used for pkg, (used, _) in totals.items()}
    if ongoing:  # the TV adds the current session to its totals only when the app closes
        all_s[ongoing[0]] = all_s.get(ongoing[0], 0) + ongoing_s
    for pkg, s in week_s.items():  # the TV's totals can drop time around a restart; events are complete
        all_s[pkg] = max(all_s.get(pkg, 0), s)

    lines = ['%sTV watch time%s  %s%s · updated %s%s' % (BOLD, RESET, DIM, TV.split(':')[0], now.strftime('%-I:%M:%S %p'), RESET), '']
    if ongoing:
        lines.append('%s▶ Now: %s%s  %s(%s this session)%s' % (GREEN, label(ongoing[0]), RESET, DIM, fmt(ongoing_s), RESET))
    else:
        lines.append('%s■ TV is off or in standby%s' % (DIM, RESET))
    lines += ['', '%s%-26s %10s %12s %10s %9s%s' % (BOLD, 'App', 'Today', 'Last 7 days', 'All time', 'Launches', RESET)]
    pkgs = {p for p in set(all_s) | set(week_s) if max(all_s.get(p, 0), week_s.get(p, 0)) >= 60}
    order = sorted(pkgs, key=lambda p: (p in SYSTEM, -all_s.get(p, 0)))
    totals_row = [0.0, 0.0, 0.0]
    for p in order:
        row = (today_s.get(p, 0), week_s.get(p, 0), all_s.get(p, 0))
        if p not in SYSTEM:
            totals_row = [x + y for x, y in zip(totals_row, row)]
        style = DIM if p in SYSTEM else (CYAN if ongoing and p == ongoing[0] else '')
        if p in SYSTEM and order.index(p) and order[order.index(p) - 1] not in SYSTEM:
            lines.append('%s%-26s %10s %12s %10s%s' % (BOLD, 'Apps total', fmt(totals_row[0]), fmt(totals_row[1]), fmt(totals_row[2]), RESET))
            lines.append('')
        lines.append('%s%-26s %10s %12s %10s %9s%s' % (style, label(p)[:26], fmt(row[0]), fmt(row[1]), fmt(row[2]),
                                                       totals.get(p, (0, 0))[1], RESET))
    lines += ['', '%sLast 7 days (apps)%s' % (BOLD, RESET)]
    per_day = [sum(s for p, s in days.get(d, {}).items() if p not in SYSTEM) for d in week]
    peak = max(per_day) or 1
    for d, s in zip(week, per_day):
        lines.append('  %s  %-30s %s' % (d.strftime('%a %b %d'), '█' * int(round(30 * s / peak)), fmt(s)))
    lines += ['', '%sTracking since %s (start of the TV\'s yearly record). Today and last 7 days use open/close events;%s' % (DIM, since, RESET),
              '%sthe TV keeps those for about 10 days.%s' % (DIM, RESET)]
    return '\n'.join(lines)


def main():
    if not TV:
        sys.exit('Set TV_HOST to your TV\'s IP address, e.g. TV_HOST=192.168.1.20 %s' % sys.argv[0])
    connect()
    tz = ZoneInfo(adb('shell', 'getprop persist.sys.timezone').strip() or 'UTC')
    if sys.argv[1:] == ['report']:
        print(render(tz))
        return
    try:
        while True:
            try:
                screen = render(tz)
            except Exception as error:  # TV asleep or off the network: keep trying
                screen = '%sCannot read the TV right now (%s). Retrying…%s' % (DIM, error, RESET)
                try:
                    connect()
                except Exception:
                    pass
            sys.stdout.write('\033[H\033[2J' + screen + '\n')
            sys.stdout.flush()
            time.sleep(REFRESH)
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
