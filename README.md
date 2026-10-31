<div align="center">

<a href="https://github.com/rokku-app/rokku">
    <img src="./.github/readme-images/app-icon-round.png" alt="Rokku logo" height="200px" width="200px" />
</a>

# Rokku

</div>

<div align="center">

A free and open source manga reader

[![CI](https://github.com/rokku-app/rokku/actions/workflows/build_push.yml/badge.svg?labelColor=27303D)](https://github.com/rokku-app/rokku/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/rokku-app/rokku?labelColor=27303D&color=0877d2)](/LICENSE)
[![GitHub downloads](https://img.shields.io/github/downloads/rokku-app/rokku/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/rokku-app/rokku/releases)
[![Translation status](https://img.shields.io/weblate/progress/rokku?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/rokku/)

<img src="./.github/readme-images/screens.gif" alt="Rokku screenshots" />

## Download

[![Rokku Stable](https://img.shields.io/github/v/release/rokku-app/rokku?maxAge=3600&label=Stable&labelColor=06599d&color=043b69&filter=v*)](https://github.com/rokku-app/rokku/releases)
[![Rokku Nightly](https://img.shields.io/github/v/release/rokku-app/rokku-nightly?maxAge=3600&label=Nightly&labelColor=2c2c47&color=1c1c39&include_prereleases)](https://github.com/rokku-app/rokku-nightly/releases)

*Requires Android 8.0 or higher.*

## About Fork

Rokku is a fork of [Yokai](https://github.com/null2264/yokai), which is itself a fork of [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K) and [Mihon](https://github.com/mihonapp/mihon) (formerly Tachiyomi).

This fork was created for personal use, after Yokai fell behind on the current Keiyoushi extension ecosystem, dependencies, and various other fixes.

Updates are sporadic, sometimes fast, sometimes slow.

The name is a play on the developer's surname (Rocha, "rock" in Portuguese): 岩 (*iwa*), the kanji used as the project's symbol, is the traditional Japanese word for rock — used for natural rock formations, mountains, rock climbing, and geology. "Rokku" (ロック) is the same word in its more modern/loanword sense, closer to pop culture and "rock" as in rock 'n' roll.

## Features

<div align="left">

<details open="">
    <summary><h3>From Rokku</h3></summary>

* Downloads now resume interrupted pages instead of restarting them.
* Manga details FAB now shrinks/extends on scroll instead of overlapping the chapter list.
* Added support for extension-lib 1.6.
* Added support for Android's native "App info" settings shortcut.

</details>

<details open="">
    <summary><h3>From Komikku</h3></summary>

* Library update errors screen: keeps a persistent, selectable list of manga that failed to update instead of a one-off log file.
* Bulk-migrate selected failed manga to another source directly from that list.

</details>

<details open="">
    <summary><h3>From Yōkai</h3></summary>

* NSFW/SFW library filter (taken from [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)).
* Fix backup incompatibility with upstream.
* New theme.
* Local Source chapters now reads ComicInfo.xml for chapter title, number, and scanlator.

</details>

<details open="">
    <summary><h3>From upstream (Tachiyomi/Mihon)</h3></summary>

* Local reading of downloaded content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support:
  [MyAnimeList](https://myanimelist.net/),
  [AniList](https://anilist.co/),
  [Kitsu](https://kitsu.app/explore/anime),
  [Manga Updates](https://www.mangaupdates.com/),
  [Shikimori](https://shikimori.io),
  and [Bangumi](https://bgm.tv/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters.
* Create backups locally to read offline or to your desired cloud service.

</details>

<details open="">
    <summary><h3>From J2K</h3></summary>

* UI redesign.
* New Manga details screens, themed by their manga covers.
* Combine 2 pages while reading into a single one for a better tablet experience.
* An expanded toolbar for easier one handed use (with the option to reduce the size back down).
* Floating searchbar to easily start a search in your library or while browsing.
* Library redesigned as a single list view: See categories listed in a vertical view, that can be collasped or expanded with a tap.
* Staggered Library grid.
* Drag & Drop Sorting in Library.
* Dynamic Categories: Group your library automatically by the tags, tracking status, source, and more.
* New Recents page: Providing quick access to newly added manga, new chapters, and to continue where you left on in a series.
* Stats Page.
* New Themes.
* Dynamic Shortcuts: open the latest chapter of what you were last reading right from your homescreen.
* [New material snackbar](.github/readme-images/material%20snackbar.png): Removing manga now auto deletes chapters and has an undo button in case you change your mind.
* Batch Auto-Source Migration (taken from [TachiyomiEH](https://github.com/NerdNumber9/TachiyomiEH)).
* [Share sheets upgrade for Android 10](.github/readme-images/share%20menu.png)
* View all chapters right in the reader.
* A lot more Material Design You additions.
* Android 12 features such as automatic extension and app updates.
* Copy/paste manga covers via a long-press context menu.
* Vertical page seekbar option for webtoon/vertical reading modes.
* Manga descriptions render inline images and auto-link URLs.
* Floating search toolbar now has a drop shadow.

</details>

</div>

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<div align="left">

<details><summary>Issues</summary>

**Before reporting a new issue, take a look at the [FAQ](https://rokku-app.github.io/docs/faq/general), the [changelog](https://github.com/rokku-app/rokku/releases) and the already opened [issues](https://github.com/rokku-app/rokku/issues).**

</details>

<details><summary>Bugs</summary>

* Include version (**Settings → About → Version**).
  * If not latest, try updating, it may have already been solved.
  * Dev version is equal to the number of commits as seen in the main page.
* Include steps to reproduce (if not obvious from description).
* Include screenshot (if needed).
* If it could be device-dependent, try reproducing on another device (if possible).
* For large logs use [Pastebin](https://pastebin.com/) (or similar).
* Don't group unrelated requests into one issue.
- **DO**: [Example #1](https://git.mihon.tech/tachiyomi/tachiyomi/issues/24), [Example #2](https://git.mihon.tech/tachiyomi/tachiyomi/issues/71).
- **DON'T**: [Example #1](https://git.mihon.tech/tachiyomi/tachiyomi/issues/75).

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaning what it should do or how.
  * Avoid writing just "like X app does"
* Include screenshot (if needed).

</details>

</div>

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/rokku-app/rokku/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=rokku-app/rokku" alt="Rokku contributors" title="Rokku contributors" width="600"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 null2264
Copyright © 2026 Thiago Rocha

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
</div>
