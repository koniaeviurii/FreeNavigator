# Mountain Watch 1.1 — native Tizen project

Target: Samsung Galaxy Watch SM-R800 (Tizen 4.0, wearable).

## Project status

This directory is the beginning of the native C watch application. It is **not yet a finished watch face** and does not contain a signed `.tpk` package.

Current source:
- `src/watch.c` — native watch lifecycle, analog clock with two tapered filled metal hour/minute hands with slim blue inlays, one thin blue seconds hand and subtle hand shadows, ambient-mode updates, four separate touch-drag widget targets with translucent glass-like highlights constrained to the round dial, a layered concentric steel-tone bezel with fine inner highlight lines and a circular center cap with a dark micro-bezel, fixed silver minute/hour marks and cardinal numerals, persistent widget positions/colors, live battery percentage, sensor listeners for heart rate and steps when supported by the watch, and a clearly approximate distance estimate derived from steps.
- `tizen-manifest.xml` — wearable application manifest.

## Native project metadata

The Eclipse/Tizen project metadata files have now been added:
- `.project` — Eclipse C project descriptor.
- `.tproject` — Wearable 4.0.0 target profile.
- `project_def.prop` — native application source and linker settings.

The Evas polygon objects now receive explicit geometry (move and resize), including the dial rings and the four indicator/glass layers. The health-info privilege is expressed using the standard manifest `privileges` block.

**Build status:** metadata preparation is not a compilation. A matching Wearable 4.0 SDK/toolchain and a valid signing profile are still required. The Eclipse managed-build descriptor (`.cproject`) and an actual SDK build remain outstanding.

## Required build environment

Samsung's supported workflow for a native Tizen watch application uses **Tizen Studio** with the Wearable 4.0 platform and a generated Tizen author/distributor certificate. The project must be built and signed in that environment before it can be installed on the SM-R800.

Official Samsung guide:
https://developer.samsung.com/galaxy-watch-tizen/creating-your-first-app/native-watch-face.html

## Next implementation tasks

1. Import the approved dial artwork as a resource and split it into independent layers. The current C source draws a temporary dark-background prototype with three hands and four independently draggable circular touch targets; it does not yet render the approved photographic face. The four widgets must be movable independently; hands, numerals and the rest of the dial remain fixed. Dragging a widget saves its position; tapping the central hub resets all four positions.
2. The native prototype now adds a layered concentric steel-tone bezel and a circular silver/blue center cap and a tapered filled polygon bodies for the two broad hour/minute hands, with subtle offset shadows; the single thin blue seconds hand remains a line. The original two broad hands and single thin blue seconds hand remain the only hands. The broad hands are drawn as tapered filled polygons positioned in local Evas coordinates; fixed cardinal numerals are raised above the movable indicator layers. Final polished metallic inserts and the approved dial artwork are still pending.
3. Implement independent color settings and persistent positions for the bezel/dial, hands, each of the four widgets, and the landscape. The prototype now draws fixed silver minute/hour marks and 12/3/6/9 numerals; these are not movable. The four prototype widgets currently have separate base colors. Their positions are stored with the Tizen application preference API when a drag ends and restored when the watch app starts. Dragging now clamps the entire circle to the round dial edge rather than a square boundary. Saved positions are also re-clamped when restored, preventing indicators from appearing outside the dial after a display-size or layout change. The central reset target has a larger invisible touch area so it is easier to activate. Tapping an indicator cycles its individual color through five preset colors and saves that choice. Tapping the central hub restores the four default positions. A full graphical color picker is not implemented yet.
4. Battery percentage is read from the Tizen Battery API. Heart-rate and pedometer listeners are wired through the Tizen Sensor API and the manifest includes the healthinfo privilege. Failed listener startup is logged and cleaned up so the app can retry on resume. The distance circle currently displays an estimate based on 0.70 m per step; it is not GPS distance and needs calibration. Each indicator also has a translucent highlight and a small specular glint; both layers follow the indicator during dragging. Sensor availability, event values and readings still require verification on the SM-R800. Weather needs a defined data source/companion connection.
5. Add low-power ambient rendering and verify performance on the SM-R800.
6. Health sensor listeners are stopped while the watch is paused or in ambient mode to reduce power use, and are restarted when active. Build, sign and test the `.tpk` on the actual watch. Compilation has not yet been verified because the Tizen 4.0 SDK/toolchain is not available in this environment.

## Automated ARM build check

The repository now includes `.github/workflows/mountain-watch-tizen-build.yml`. It provisions the Tizen Studio 4.0 command-line tools, installs the Wearable 4.0 native compiler, generates a clean Watch-template project, inserts this project's C source and manifest, and attempts an ARM Debug compilation. The workflow uploads the compiler output for inspection.

This workflow is a **compile check only**. It does not claim to produce a Galaxy-Watch-installable package: a physical-watch TPK must be signed with a valid Samsung/Tizen security profile. A default emulator profile is not sufficient for the SM-R800.

## Important

The HTML files elsewhere in this project are previews only. Demo values in those previews are not live sensor readings. Do not install an HTML preview as if it were a Tizen watch face.

## Подготовка графических слоёв

В `tools/prepare_assets.py` добавлен воспроизводимый скрипт извлечения четырёх круглых индикаторов из утверждённого изображения: battery, pulse, steps и distance. Он сохраняет исходные градиенты, значки и цифры в PNG с прозрачными углами.

Пример запуска:
```bash
python3 tools/prepare_assets.py approved-dial.png generated-widgets
```

**Ограничение текущего этапа:** исходная картинка уже содержит индикаторы и стрелки как часть единого растра. Извлечённые PNG пока нельзя накладывать поверх исходного изображения — получится дублирование. Также тестовое автоматическое удаление индикаторов/стрелок даёт заметные артефакты и не включено в проект как готовый фон. Для корректного циферблата нужно вручную/послойно восстановить фон, отделить три стрелки и затем подключить независимые виджеты.
