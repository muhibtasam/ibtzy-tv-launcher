#!/usr/bin/env bash
# Dev helper: the same remote-control scenarios every time (tools/perf.sh each), for before/after numbers.
cd "$(dirname "$0")/.." || exit 1
S=tools/tv_shot.sh; P=tools/perf.sh
go() { bash $S "$1" x >/dev/null; }
# Each scenario's start was checked on screen (2026-10-27): the tab bar now starts at Home, so three rights reach Apps.
go "3 s s s 20";                  bash $P "22 22 22 22 21 21 21 20 22 22 21 19 22 22 19" home-cards
go "3 s s s";                     bash $P "22 22 22 20 22 22 21 21 20 22 22 19 19" apps-search   # down from the Apps tab: its search bar and keyboard button
go "3 s s 20 20 20 s";            bash $P "22 22 22 22 21 21 21 21" home-dock
go "3 s s 20 20 20 20 s";         bash $P "22 22 21 21 19 20" home-drawer
go "3 s s 20 19 22 s s 20 21 s s 20 s"; bash $P "22 22 22 22 20 22 22 22 21 21 20 22 22" movies   # the switch on Movies first
go "3 s s 20 19 22 20 22 s s s";  bash $P "22 22 20 22 22 22 21 21 20 22 22 22 20 22 22" games
go "3 s s 19 19 19 20 19 22 22 s s"; bash $P "20 20 22 22 22 20 22 22 20 21 21 19 19" apps
go "3 s s 19 19 19 20 19 s";      bash $P "22 s 22 s 21 s 21 s" tab-switch
# Home scrolled down to Inputs and back (only when an input has a signal; otherwise it stays on the dock).
go "3 s s 20 20 20 s";            bash $P "20 s s 19 s s 20 s s 19 s s" home-scroll
