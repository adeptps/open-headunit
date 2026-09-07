# SCS MVite navigation bridge

This branch mirrors the navigation information received on the Android Auto
instrument-cluster channel to the broadcast contract consumed by SCS:

```text
action: ssa.labs.monjaro.YN_HOOK
extra:  payload (UTF-8 JSON string)
```

The intent is explicitly addressed to `com.example.climateseats`. Each intent
carries exactly one MVite-style event. The existing
`com.andrerinas.headunitrevived.NAVIGATION_UPDATE` broadcast remains available
for existing integrations; this branch only adds the previously omitted
`maneuver_type` and `navigation_event_type` extras to it.

## Faithful field mapping

| MVite event | Android Auto source | Availability |
| --- | --- | --- |
| `RouteActive` | cluster start/stop and navigation status | Full |
| `Maneuver` | current step maneuver, `road_info` and step distance | Full when the phone supplies the fields |
| `Maneuver.ExitNumber` | roundabout exit number | Full when supplied |
| `RouteStatus` | current road, then current-step instruction text | Partial: at most the text supplied by Android Auto |
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

The navigation protobuf in Open Headunit is based on community reverse
engineering; Google does not publish the Android Auto wire schema. The legacy
and corrected flat interpretations of NAV `0x8004` reuse the same protobuf tags,
so the bridge only accepts the corrected interpretation when the maneuver value
is outside the legacy `Side` range. Ambiguous flat packets are ignored instead
of being decoded with the wrong field meanings. NAV `0x8005` is also ignored:
observed AA versions reuse its four varint tags for incompatible meanings, so a
maneuver enum could otherwise be emitted as a distance. Rich `0x8006`/`0x8007`
and an unambiguous corrected `0x8004` remain the primary paths. Unit tests
validate the mapper, wire tags, serialization and stale-session barrier, but a capture from the
target phone and head unit is required to prove the exact wire fields used by
that Android Auto and navigation-app combination. Keep raw NAV messages from a
real session as golden fixtures before extending the schema.
