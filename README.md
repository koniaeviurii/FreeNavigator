# Free Navigator — developer preview 0.3.1

Android project for a Russian-language navigation app. This version replaces the placeholder screen with a real online vector map.

## Implemented in 0.3.1
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
- Compact route progress panel with start time, estimated arrival, remaining distance caption and stop button.
- Route alternatives requested from OSRM when the public routing service provides them.
- Five-second full-route preview after pressing “Начать маршрут”.
- Russian TTS announcements for route readiness, maneuver steps when supplied by OSRM, route start and arrival.
- Search opened from the side menu; the old bottom search control has been removed.
- Compact search result labels: place name, type and city when available.
- Anti-radar cache downloads nearby fixed speed-camera nodes from OpenStreetMap Overpass, keeps the last successful dataset locally, checks for updates on app start/location change and schedules a daily WorkManager refresh.
- Foreground GPS proximity monitoring with Russian voice warning within approximately 800 metres of a cached camera.

## Not implemented yet
- Lane guidance, traffic-light/incident warnings and live traffic data.
- Mobile radar reports and community-reported mobile cameras.
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
- проверяет наличие APK и публикует его как артефакт `FreeNavigator-0.3.1-debug-apk` на 14 дней.

После успешного запуска APK можно получить на странице запуска workflow в разделе **Artifacts**. Сборка в облаке и проверка установки на физическом телефоне — разные этапы; перед использованием нужно установить APK и проверить GPS, построение маршрута и переключение стиля карты.

## Дополнение к исправлениям 0.2.9

- При повторной загрузке стиля карты активная GPS-стрелка восстанавливается вместе с линией маршрута.
- Обновление позиции и направления вынесено в общую функцию, чтобы одинаково работать при обычном GPS-обновлении и восстановлении карты.


## Важные ограничения версии 0.3.1

- База камер берётся из OpenStreetMap через Overpass и может быть неполной или устаревшей. Сейчас это стационарные точки, отмеченные в OSM; мобильные радары не покрываются.
- Голосовые предупреждения зависят от установленного на Android движка синтеза речи и доступного русского голоса.
- Публичный OSRM может вернуть только один маршрут, даже при запросе альтернатив. Пошаговые подсказки ограничены теми манёврами, которые вернул сервис.
- Непрерывной фоновой навигации в этой версии нет: GPS-антирадар работает, пока приложение находится на экране; ежедневное обновление базы выполняется WorkManager при наличии сети.
- Реальная сборка APK и проверка на телефоне должны быть выполнены через GitHub Actions. В текущей среде нет Android SDK/Gradle, поэтому сборка здесь не подтверждена.


### Build 0.4.8
Radar refresh now preserves the existing offline camera cache when a refresh response contains no valid camera points.
