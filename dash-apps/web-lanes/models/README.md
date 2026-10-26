# Lanes dashboard models

| file | what | source / license |
|---|---|---|
| `car-*.glb`, `truck-box.glb`, `motorcycle-sport.glb`, `bike-road.glb` | other road users, picked by `DAS_object` type, paint tinted per vehicle | [munim-maps-vehicles](https://www.npmjs.com/package/munim-maps-vehicles) 0.5.0, generated from code, no brands. Apache-2.0, see `LICENSE-munim-maps-vehicles` |
| `ego-tesla.glb` | your car, stand-in, repainted Midnight Silver Metallic at runtime | copied from `web-vanilla/assets/models/tesla.glb` |
| `pedestrian.glb` | pedestrians | copied from `web-vanilla/assets/models/pedestrian.glb` |

## Using your own car model

Put a `.glb` of your car at `models/ego.glb` (glTF convention: Y up, front toward +Z). The dashboard loads it instead of `ego-tesla.glb`, centres it on the ground and scales it to a 2020–2024 Model Y's 4.75 m length. Keep its own paint; the Midnight Silver repaint only applies to the stand-in.

`models/ego.glb` is gitignored, because purchased models usually can't be redistributed in a public repository. Add it to your local checkout before building.
