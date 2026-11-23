# Free Navigator — developer preview 0.2.8

Android project for a Russian-language navigation app. This version replaces the placeholder screen with a real online vector map.

## Implemented in 0.2.8
- MapLibre Native Android map using OpenFreeMap's Liberty style.
- Light/dark map style switch.
- Place/address search using the Photon geocoding demo API.
- Map camera moves to a selected search result.
- Button to center the map on the last known Android device location.
- Visible blue position marker, restored when the map style changes.
- Search fallback: Photon first, then OpenStreetMap Nominatim if Photon is unavailable.
- Route planning with selectable origin and destination.
- Live GPS-follow mode after tapping “Начать маршрут”.
- Blue directional arrow, map camera following the current position, remaining distance/time estimate, and route stop button.
- OpenStreetMap/OpenFreeMap attribution shown on screen.

## Not implemented yet
- Turn-by-turn voice instructions and lane guidance.
- Live traffic, speed cameras/radar alerts.
- Offline regional map packages, offline search and offline routing.
- Background navigation service and guaranteed continuous tracking while the app is not visible.
- Production geocoder/routing backend and provider SLA.

## Data providers and limitations
The map style is served by OpenFreeMap and map data is based on OpenStreetMap. Search uses the public Photon demo endpoint with OpenStreetMap Nominatim as a fallback. Both are public services with usage policies and no guaranteed availability. These public services are suitable for development/testing, not a guaranteed production backend. Availability, rate limits and coverage can change. Do not treat this build as a finished navigation product.

## Build
Open this folder in Android Studio (JDK 17; Android SDK platform 35), allow Gradle to download dependencies, then run `:app:assembleDebug`. The project has not yet been built or device-tested in this environment. Verify the resulting APK on a real device before release.


## Версия 0.2.9 — исправления маршрута

- После построения маршрут автоматически помещается целиком в видимую область карты.
- Убрано дублирующее сообщение о синей линии из окна построенного маршрута.
- Если разрешение GPS запрашивается при нажатии «Начать маршрут», ведение запускается автоматически после предоставления разрешения.
- Линия маршрута хранится в состоянии экрана и повторно добавляется после смены стиля карты.

## Автоматическая сборка APK

В репозитории настроен GitHub Actions workflow `.github/workflows/android.yml`:
- запускается при отправке изменений в ветку `main` или вручную через **Actions → FreeNavigator Android build → Run workflow**;
- собирает `:app:assembleDebug` на Java 17 и Gradle 8.9;
- проверяет наличие APK и публикует его как артефакт `FreeNavigator-0.2.9-debug-apk` на 14 дней.

После успешного запуска APK можно получить на странице запуска workflow в разделе **Artifacts**. Сборка в облаке и проверка установки на физическом телефоне — разные этапы; перед использованием нужно установить APK и проверить GPS, построение маршрута и переключение стиля карты.

## Дополнение к исправлениям 0.2.9

- При повторной загрузке стиля карты активная GPS-стрелка восстанавливается вместе с линией маршрута.
- Обновление позиции и направления вынесено в общую функцию, чтобы одинаково работать при обычном GPS-обновлении и восстановлении карты.
