# Lanes dashboard models

| file | what | source / license |
|---|---|---|
| `car-*.glb`, `truck-box.glb`, `motorcycle-sport.glb`, `bike-road.glb` | other road users, picked by `DAS_object` type, paint tinted per vehicle | [munim-maps-vehicles](https://www.npmjs.com/package/munim-maps-vehicles) 0.5.0, generated from code, no brands. Apache-2.0, see `LICENSE-munim-maps-vehicles` |
| `ego-model-y.glb` | your car: a 2020–2024 Model Y, painted Midnight Silver Metallic at runtime | "Tesla Model Y (high detail)", [printables.com/model/1111101](https://www.printables.com/model/1111101-tesla-model-y-high-detail), **CC BY 4.0**. That model is itself a remix of "Tesla-model-y-3d-printable-file-stl 2" by SueRoger (Sketchfab). Changes: decimated from 1.6M to about 410k triangles, split into paint / glass / trim / tyre / rim / lamp materials by region, smooth-shaded, quantized. Made with `scripts/convert-ego-model.py` |
| `pedestrian.glb` | pedestrians | copied from `web-vanilla/assets/models/pedestrian.glb` |

## Using your own car model

Put a `.glb` of your car at `models/ego.glb` (glTF convention: Y up, front toward +Z). The dashboard loads it instead of `ego-model-y.glb`, centres it on the ground and scales it to a 2020–2024 Model Y's 4.75 m length. It keeps its own paint; the Midnight Silver repaint only applies to the bundled model.

`models/ego.glb` is gitignored, because purchased models usually can't be redistributed in a public repository. Add it to your local checkout before building.
