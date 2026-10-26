# dash-apps

Dashboards that run inside the Dashpilot app, fed live car data.

| folder | |
|---|---|
| `web-*/` | web dashboards: `index.html`, `manifest.json`, `preview.jpg` |
| `rive/` | Rive dashboards: built `.riv` files and their sources |
| `contest/` | [dash app contest](contest/README.md) rules and entries |

## Getting data

The app sends a new state about 25 times a second.

| | delivery |
|---|---|
| Web, Android | calls `window.onCarStateUpdate()`; read values with `window.NativeCarState.getX()` / `isX()` |
| Web, iOS | calls `window.receiveMessage(state)` with a plain object |
| Rive | writes the same keys into the `DashViewModel` view model, see [Rive](#rive) |

A web app should handle both:

```js
const KEYS = ["egoSpeed", "gear", "leftBlinker", "rightBlinker", "isImperial"]; // the keys you use

function fromAndroid() {
  const n = window.NativeCarState, s = {};
  for (const k of KEYS) {
    const cap = k[0].toUpperCase() + k.slice(1);
    const m = k === "isImperial" ? k : n["get" + cap] ? "get" + cap : "is" + cap;
    s[k] = n[m]?.();
  }
  return s;
}

window.onCarStateUpdate = () => render(fromAndroid());
window.receiveMessage = (state) => render(state);
```

## DashState

What a dash app receives: the [CarState](#carstate) fields plus these phone and settings fields, all in one flat object.

| key | type | meaning |
|---|---|---|
| `currentTime` | ms since epoch | |
| `phoneBattery` | 0–100 | -1 = unknown |
| `isImperial` | bool | user prefers miles / °F |
| `darkMode` | bool | |
| `darkModeBackgroundGray` | 0–255 | background gray picked for dark mode |
| `showPhoneBattery`, `showCarBattery`, `showOdometer` | bool | user wants these visible |
| `alwaysOnBlindSpotMonitor` | bool | show blind spots even without a blinker |
| `renderQuality` | 1–3 | hint for expensive effects |
| `dataSourceType` | string | `"dashkit"`, `"comma"`, `"websocket"`, `"demo"` |
| `speedCameraDistance` | meters | -1 = none nearby. **Android only** |

## CarState

Values not received yet are `0` / `false`. For speed limit, stop line and energy fields, 0 means unknown.

**Driving**

| key | type | meaning |
|---|---|---|
| `egoSpeed` | number | speed as the car displays it. Show as-is |
| `gear` | 0–4 | 0 unknown, 1 P, 2 R, 3 N, 4 D |
| `egoSteeringAngle` | degrees | steering wheel angle, negative = left |
| `leftBlinker`, `rightBlinker` | 0–2 | 0 off, 1 blinking (lamp dark), 2 blinking (lamp lit) |
| `anyDoorOpen` | 0/1 | |
| `buckleStatus` | 0/1 | 1 = driver buckled |
| `odometer` | number | km, or miles when `isImperial` |

**ADAS**

| key | type | meaning |
|---|---|---|
| `adasOn` | bool | cruise / lane keeping engaged. Use this for "assist on": it works with every data source |
| `accSetSpeed` | number | cruise set speed, same unit as `egoSpeed` |
| `fusedSpeedLimit` | number | posted limit, in the user's unit |
| `leftBlindSpot`, `rightBlindSpot` | 0–2 | 0 clear, 1 vehicle, 2 vehicle + urgent |
| `trafficLightColor` | 0–3 | 0 none, 1 red, 2 green, 3 yellow |
| `stopLineDist` | meters | distance to the stop line |
| `changingLane` | bool | lane change in progress |
| `madsActive`, `experimentalMode` | bool | openpilot states, comma only |
| `selfdriveActive` | bool | openpilot engaged, comma only. **iOS only** for web apps |

**Vehicles ahead** (DashKit, from the Autopilot's `DAS_object`)

| key | type | meaning |
|---|---|---|
| `leadVehDx`, `leftVehDx`, `rightVehDx` | meters | gap from the front bumper to the vehicle ahead in this lane, the lane to the left, the lane to the right. **-1 = no vehicle** |
| `leadVehDy`, `leftVehDy`, `rightVehDy` | meters | lateral offset. Sign not yet verified on a car: place side vehicles by lane, not by this |
| `leadVehType`, `leftVehType`, `rightVehType` | 0–5 | 0 unknown, 1 truck, 2 car, 3 motorcycle, 4 bicycle, 5 pedestrian |
| `cutinVehDx`, `cutinVehDy`, `cutinVehType` | as above | a vehicle cutting into your lane. Its Dy is its real lateral position |
| `leadVehVxRel`, `leftVehVxRel`, `rightVehVxRel`, `cutinVehVxRel` | m/s | relative speed, + = pulling away; 0 = unknown |
| `leadVehHeading`, `leftVehHeading`, `rightVehHeading`, `cutinVehHeading` | rad | relative heading (sign not yet verified); 0 = unknown |

**Road and lane** (DashKit, from `DAS_object` and `DAS_lanes`)

| key | type | meaning |
|---|---|---|
| `roadSignId` | -1–1 | -1 none, 0 stop sign, 1 traffic light |
| `roadSignColor` | 0–4 | 0 none, 1 red, 2 yellow, 3 green, 4 red+yellow |
| `roadSignArrow` | 0–4 | 0 circle, 1 left, 2 right, 3 straight, 4 unknown |
| `roadSignStopLineDist` | meters | -1 = unknown |
| `roadSignControlActive` | 0/1 | Autopilot is acting on this sign |
| `laneWidth`, `laneViewRange` | meters | `laneViewRange` 0 = no lane data |
| `laneC0`…`laneC3` | m, slope, 1/m, 1/m² | lane center `y = C0 + C1·x + C2·x² + C3·x³`, x ahead. Sign not yet verified |

**Warnings** (DashKit, from `DAS_status`)

| key | type | meaning |
|---|---|---|
| `forwardCollisionWarning` | 0/1 | |
| `laneDepartureWarning` | 0–4 | 0 none, 1 left, 2 right, 3 left severe, 4 right severe |
| `sideCollisionWarning` | 0–3 | 0 none, 1 left, 2 right, 3 both |

The vehicles only cover what's level with or ahead of the car. `leftBlindSpot` / `rightBlindSpot` cover beside and behind, so use both: a side vehicle under ~5 m while its blind spot is set is the same vehicle. Each slot refreshes about every 180 ms; ease positions between updates. [`web-lanes/`](web-lanes/) shows one way to draw them; open its `index.html?demo` in a browser to try it without the app.

**Battery**

| key | type | meaning |
|---|---|---|
| `nominalEnergyRemaining`, `fullPackEnergy`, `energyBuffer` | kWh | charge = `(remaining - buffer) / (full - buffer)` |
| `powerLevel` | -100…100 | % of max regen (negative) or max discharge (positive) |
| `packVoltage` | V | |
| `packCurrent` | A | positive = discharging |
| `maxRegenPower`, `maxDischargePower` | kW | current limits |
| `packTMin`, `packTMax` | °C, or °F when `isImperial` | |

The app's CarState also has climate fields and the VIN; dash apps don't receive those yet.

## Rive

A Rive dashboard gets the same [DashState](#dashstate) and [CarState](#carstate) keys as a web app, through a view model named `DashViewModel` (default instance) on the main artboard. Numbers are number properties, bools are boolean properties, `dataSourceType` is a string. Declare only the ones you use; the rest are skipped.

One difference: Rive numbers are 32-bit, so `currentTime` is **seconds since local midnight** (0–86399) instead of epoch milliseconds.

The app plays the file's default state machine and resizes the artboard to the screen (Fit = Layout): it is always **1080 units tall**, and its width follows the screen, from 1440 (4:3 tablet) to about 2340 (19.5:9 phone). Build the main artboard from layouts so it stretches.

[`rive/dash-demo/`](rive/dash-demo/) is a demo dashboard written in Rive markup. It declares every property, so it's a good starting point. `rive dash-apps/rive/dash-demo --once` with the [Rive CLI](https://rive.app) rebuilds `rive/dash_demo.riv`.

To try your own `.riv` on a phone, go to Settings → Developer → **Load Rive File**.

## Saving settings

Web apps get one string of storage, usually JSON.

| | Android | iOS |
|---|---|---|
| load | `NativeCarState.getAppData()`, `""` if none | `window.__DASHPILOT_APP_DATA__` |
| save | `NativeCarState.setAppData(string)` | `webkit.messageHandlers.appData.postMessage(string)` |
| pause the swipe-to-next-dashboard gesture while you drag | `NativeCarState.setEditing(bool)` | `webkit.messageHandlers.editing.postMessage(bool)` |

## Adding a web app to the apps

1. `dash-apps/web-<id>/` with `index.html`, `preview.jpg` (landscape, ~2:1) and a `manifest.json`:
   ```json
   { "$schema": "../manifest.schema.json", "manifestVersion": 1, "id": "<id>", "settings": ["useImperial", "darkMode"] }
   ```
   `id` is lowercase letters, digits and hyphens, matching the folder name. `settings` lists the display settings your app uses, so the app only offers those: `showPhoneBattery`, `showCarBattery`, `showOdometer`, `alwaysOnBlindSpotMonitor`, `useImperial`, `darkMode`, `renderQuality`, `darkModeBackgroundGray`.
2. Add `web-<id>` to `DEFAULT_FOLDERS` in `scripts/sync-to-android.sh` and `scripts/sync-to-ios.sh`.
3. Android: an entry in `availableDashboards` (`datamodel/dash/DashboardConfig.kt`) and a `dashboard_name_<id>` string.
4. iOS: an entry in `availableDashboards` (`Models/DashboardConfig.swift`), `<id>` in `localApps` in `UI/WebDashView.swift`, and a `preview_<id>.imageset` in `Assets.xcassets` (copy an existing one).

`node scripts/validate-manifests.js` checks the manifests; CI runs it too.
