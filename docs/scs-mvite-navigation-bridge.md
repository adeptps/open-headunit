# SCS MVite navigation bridge

This branch mirrors the navigation information received on the Android Auto
instrument-cluster channel to the broadcast contract consumed by SCS:

```text
action: ssa.labs.monjaro.YN_HOOK
extra:  payload (UTF-8 JSON string)
```

The intent is explicitly addressed to `com.example.climateseats`. Each intent
carries exactly one MVite-style event plus an `open_headunit_build` diagnostic
extra. The existing
`com.andrerinas.headunitrevived.NAVIGATION_UPDATE` broadcast remains available
for existing integrations and includes `maneuver_type` and
`navigation_event_type` extras.

## Faithful field mapping

| MVite event | Android Auto source | Availability |
| --- | --- | --- |
| `RouteActive` | cluster start/stop and navigation status | Full |
| `Maneuver` | current step maneuver, `road.name` and step distance; checked `0x8005` distance is fallback | Partial when the phone omits fields |
| `Maneuver.ExitNumber` | roundabout exit number | Full when supplied |
| `RouteStatus` | current road, then current-step cue text | Partial: at most the text supplied by Android Auto |
| `Eta.ArrivalTime` | destination estimated arrival string | Full for canonical `HH:mm`; other text is omitted rather than sent with a non-MVite shape |
| `Eta.DistanceLeft` | destination remaining distance | Full when supplied |
| `Eta.TimeLeft` | destination remaining time | Partial: numeric fields come from AA seconds; required `value` is normalized because AA does not expose the original UI string |
| `Lanes` | current-step distance, lane directions and highlighted direction | Partial: only the current step has a usable MVite distance; `view` is selected deterministically |
| `Speed` | a real location fix available to Open Headunit | Supplemental: emitted only while a route is active and the fix has speed |

The bridge deliberately does not emit values that cannot be obtained from the
Android Auto navigation channel:

- `SpeedLimit`
- `SpeedLimit.Alarm`
- `RouteProgress`
- `RouteProgress.TrafficJam`
- `TrafficLights`
- `Camera`
- `TrafficLevel`
- route geometry and map theme bridge events

Synthesizing these values would make the SCS display look populated while
silently providing incorrect navigation data. A phone-side data source is
required for full MVite parity.

`Lanes` emits `items:[]` when an authoritative navigation state no longer has
usable lane data and when a route ends. Late NAV packets received after an
explicit stop cannot reactivate the route; another start or active status is
required. Lane composition/highlight changes are emitted immediately with the
navigation-state message; live distance changes remain on the one-second
debounced path. A transport disconnect also emits the lane clear and inactive
route events, so speed and stale route state cannot survive a lost AA session.

## JSON examples

```json
{"event":"RouteActive","value":true}
```

```json
{"event":"Maneuver","distance":350,"metric":"м","nextRoad":"Main Street","sign":"turn_right"}
```

```json
{"event":"Maneuver.ExitNumber","value":3}
```

```json
{"event":"RouteStatus","value":"Keep right"}
```

```json
{"event":"Eta.DistanceLeft","value":12400,"metric":"м"}
```

```json
{"event":"Eta.TimeLeft","day":null,"hour":null,"minute":18,"value":"18 мин"}
```

```json
{"event":"Eta.ArrivalTime","hour":14,"minute":35}
```

```json
{"event":"Lanes","items":[{"idx":0,"dist":350,"lanes":[[{"type":"straightahead","view":"large","isHighlighted":true}]]}]}
```

## Validation boundary

The Android Auto protobuf is community-maintained. This branch retains the
3.4.0 schema present in upstream and the supplied debug APK: `NavigationStep`
uses `road` at field 2 and `cue` at field 4, with maneuver values 0 through 42.
The legacy and corrected flat interpretations of NAV `0x8004` reuse tags, so
ambiguous packets are ignored and the corrected interpretation is accepted only
when its maneuver value is outside the legacy `Side` range. NAV `0x8005` is
parsed according to the 3.4.0 schema, but distance/time are accepted only within
bounds; this does not identify a conflicting wire dialect. Rich `0x8006`/`0x8007`
data takes precedence. A capture from the target phone and head unit is still
needed to prove the exact wire fields used by that Android Auto and navigation-
app combination. Keep raw NAV messages from a real session as golden fixtures
before extending the schema.

This bridge is intentionally not full MVite parity. Android Auto does not expose
speed limits, cameras, traffic, route geometry or map theme in this channel; do
not treat their absence as a serialization failure or synthesize them.
