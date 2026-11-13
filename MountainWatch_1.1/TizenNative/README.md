# Mountain Watch 1.1 — native Tizen project

Target: Samsung Galaxy Watch SM-R800 (Tizen 4.0, wearable).

## Project status

This directory is the beginning of the native C watch application. It is **not yet a finished watch face** and does not contain a signed `.tpk` package.

Current source:
- `src/watch.c` — native watch lifecycle, analog hour/minute/seconds hands, ambient-mode updates, and four separate touch-drag widget targets.
- `tizen-manifest.xml` — wearable application manifest.

## Required build environment

Samsung's supported workflow for a native Tizen watch application uses **Tizen Studio** with the Wearable 4.0 platform and a generated Tizen author/distributor certificate. The project must be built and signed in that environment before it can be installed on the SM-R800.

Official Samsung guide:
https://developer.samsung.com/galaxy-watch-tizen/creating-your-first-app/native-watch-face.html

## Next implementation tasks

1. Import the approved dial artwork as a resource and split it into independent layers. The current C source draws a temporary dark-background prototype with three hands and four independently draggable circular touch targets; it does not yet render the approved photographic face. The four widgets must be movable independently; hands, numerals and the rest of the dial remain fixed. Dragging a widget saves its position; tapping the central hub resets all four positions.
2. Draw the approved metallic hour/minute hands and thin seconds hand without duplicating them.
3. Implement independent color settings and persistent positions for the bezel/dial, hands, each of the four widgets, and the landscape. The four prototype widgets currently have separate base colors. Their positions are stored with the Tizen application preference API when a drag ends and restored when the watch app starts. Tapping an indicator cycles its individual color through five preset colors and saves that choice. Tapping the central hub restores the four default positions. A full graphical color picker is not implemented yet.
4. Connect supported watch data sources for steps, heart rate and battery. Distance and weather need a defined data source/companion connection.
5. Add low-power ambient rendering and verify performance on the SM-R800.
6. Build, sign and test the `.tpk` on the actual watch. Compilation has not yet been verified because the Tizen 4.0 SDK/toolchain is not available in this environment.

## Important

The HTML files elsewhere in this project are previews only. Demo values in those previews are not live sensor readings. Do not install an HTML preview as if it were a Tizen watch face.

## Подготовка графических слоёв

В `tools/prepare_assets.py` добавлен воспроизводимый скрипт извлечения четырёх круглых индикаторов из утверждённого изображения: battery, pulse, steps и distance. Он сохраняет исходные градиенты, значки и цифры в PNG с прозрачными углами.

Пример запуска:
```bash
python3 tools/prepare_assets.py approved-dial.png generated-widgets
```

**Ограничение текущего этапа:** исходная картинка уже содержит индикаторы и стрелки как часть единого растра. Извлечённые PNG пока нельзя накладывать поверх исходного изображения — получится дублирование. Также тестовое автоматическое удаление индикаторов/стрелок даёт заметные артефакты и не включено в проект как готовый фон. Для корректного циферблата нужно вручную/послойно восстановить фон, отделить три стрелки и затем подключить независимые виджеты.
