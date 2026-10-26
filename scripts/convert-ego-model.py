"""Convert the Printables Model Y STL (CC BY 4.0) into the lanes dashboard's
dash-apps/web-lanes/models/ego-model-y.glb.

Source: https://www.printables.com/model/1111101-tesla-model-y-high-detail
(file tesla-model-y.stl). Requires: pip install trimesh==4.5.3 numpy
fast-simplification==0.1.9 scipy networkx
Usage: python scripts/convert-ego-model.py tesla-model-y.stl ego-model-y.glb

Steps:
assign materials by region on the full-resolution mesh, decimate each part,
crease-aware smooth shading, glTF axes (Y up, front +Z), real size."""
import sys, json, struct, numpy as np, trimesh, fast_simplification

src, dst = sys.argv[1], sys.argv[2]
# Triangle budget per part: the paint carries the shape (metallic reflections
# show every dent), tyre tread detail is invisible at dashboard distance.
BUDGET = {'paint': 210000, 'glass': 30000, 'trim': 15000, 'tyre': 40000,
          'rim': 75000, 'headlight': 10000, 'taillight': 10000}
m = trimesh.load(src, force='mesh'); m.merge_vertices()
f = m.faces

# STL frame: x right, y toward the rear (front is -y), z up; units ~2.42 cm.
c = m.triangles_center; n = m.face_normals
x, y, z = c[:, 0], c[:, 1], c[:, 2]
AXLES = (-55.1, 63.6); AXLE_Z = 15.3; WHEEL_R = 14.9; RIM_R = 10.6

mat = np.full(len(f), 'paint', dtype=object)
# Wheels: within the tyre radius of an axle, outboard of the wheel arches.
for ay in AXLES:
    r = np.hypot(y - ay, z - AXLE_Z)
    wheel = (r < WHEEL_R) & (np.abs(x) > 26)
    mat[wheel] = 'tyre'
    mat[wheel & (r < RIM_R) & (np.abs(n[:, 0]) > 0.35)] = 'rim'
# Greenhouse: above the beltline from the windshield back (glass roof too).
glass = (z > 45.5) & (y > -33) & (y < 93) & (n[:, 2] > -0.6)
mat[glass & (mat == 'paint')] = 'glass'
# Lamps.
# 2020-2024 Model Y: headlights and taillights sit at the outer corners only
# (the full-width light bar is the 2025 refresh).
mat[(y < -78) & (z > 29) & (z < 38) & (np.abs(x) > 17) & (n[:, 1] < -0.35) & (mat == 'paint')] = 'headlight'
mat[(y > 89) & (z > 37) & (z < 47) & (np.abs(x) > 17) & (n[:, 1] > 0.35) & (mat == 'paint')] = 'taillight'
# Lower black trim.
mat[(z < 6.5) & (mat == 'paint')] = 'trim'

# name: (baseColor RGB, metallic, roughness, emissive RGB)
MATS = {
    'paint':     ([0.33, 0.34, 0.36], 0.75, 0.30, None),
    'glass':     ([0.02, 0.025, 0.03], 0.30, 0.06, None),
    'trim':      ([0.02, 0.02, 0.022], 0.00, 0.55, None),
    'tyre':      ([0.025, 0.025, 0.025], 0.00, 0.90, None),
    'rim':       ([0.42, 0.44, 0.47], 0.90, 0.28, None),
    'headlight': ([0.85, 0.88, 0.92], 0.20, 0.10, [0.25, 0.27, 0.30]),
    'taillight': ([0.45, 0.02, 0.02], 0.10, 0.15, [0.35, 0.0, 0.0]),
}

# glTF axes: X = x, Y = z (up), Z = -y (front +Z); proper rotation (det +1).
R = np.array([[1, 0, 0, 0], [0, 0, 1, 0], [0, -1, 0, 0], [0, 0, 0, 1]], dtype=float)
scale = 4.751 / m.extents[1]
parts = {}
for name in MATS:
    sel = np.where(mat == name)[0]
    if not len(sel):
        continue
    part = m.submesh([sel], append=True)
    # Decimate each part on its own so material borders stay where the
    # full-resolution classification put them.
    if BUDGET[name] < len(part.faces):
        pv, pf = fast_simplification.simplify(part.vertices.astype(np.float32), part.faces.astype(np.int32), target_count=BUDGET[name])
        part = trimesh.Trimesh(pv, pf, process=True)
    part = trimesh.graph.smooth_shade(part, angle=np.radians(38))
    part.apply_transform(R)
    part.apply_scale(scale)
    parts[name] = part
lo = np.min([p.bounds[0] for p in parts.values()], axis=0)
hi = np.max([p.bounds[1] for p in parts.values()], axis=0)
shift = np.array([-(lo[0] + hi[0]) / 2, -lo[1], -(lo[2] + hi[2]) / 2])

# Hand-written GLB with KHR_mesh_quantization (three.js reads it natively):
# positions as normalized uint16 dequantized by the node's scale/translation
# (one uniform scale, so normals aren't skewed by the node transform),
# normals as normalized int8, indices uint16 where they fit.
bin_chunks, views, accessors, meshes, nodes, materials = [], [], [], [], [], []
offset = 0
def add_view(data, target, stride=None):
    global offset
    pad = (-len(data)) % 4
    views.append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(data), 'target': target, **({'byteStride': stride} if stride else {})})
    bin_chunks.append(data + b'\0' * pad)
    offset += len(data) + pad
    return len(views) - 1

counts = {}
for name, part in parts.items():
    v = part.vertices + shift
    vmin, vmax = v.min(axis=0), v.max(axis=0)
    ext = max(float((vmax - vmin).max()), 1e-6)
    q = np.round((v - vmin) / ext * 65535).astype(np.uint16)
    pos = np.zeros((len(q), 4), np.uint16); pos[:, :3] = q
    nrm = np.zeros((len(v), 4), np.int8)
    nrm[:, :3] = np.round(np.clip(part.vertex_normals, -1, 1) * 127).astype(np.int8)
    idx = part.faces.astype(np.uint16 if len(v) < 65536 else np.uint32).ravel()
    pv = add_view(pos.tobytes(), 34962, 8)
    nv = add_view(nrm.tobytes(), 34962, 4)
    iv = add_view(idx.tobytes(), 34963)
    accessors += [
        # min/max are in stored (integer) units for quantized accessors.
        {'bufferView': pv, 'componentType': 5123, 'normalized': True, 'count': len(v), 'type': 'VEC3',
         'min': q.min(axis=0).astype(int).tolist(), 'max': q.max(axis=0).astype(int).tolist()},
        {'bufferView': nv, 'componentType': 5120, 'normalized': True, 'count': len(v), 'type': 'VEC3'},
        {'bufferView': iv, 'componentType': 5123 if idx.dtype == np.uint16 else 5125, 'count': len(idx), 'type': 'SCALAR'},
    ]
    a = len(accessors) - 3
    color, metal, rough, emis = MATS[name]
    materials.append({'name': name, 'pbrMetallicRoughness': {'baseColorFactor': color + [1], 'metallicFactor': metal, 'roughnessFactor': rough},
                      **({'emissiveFactor': emis} if emis else {})})
    meshes.append({'name': name, 'primitives': [{'attributes': {'POSITION': a, 'NORMAL': a + 1}, 'indices': a + 2, 'material': len(materials) - 1}]})
    nodes.append({'name': name, 'mesh': len(meshes) - 1, 'translation': vmin.tolist(), 'scale': [ext] * 3})
    counts[name] = len(part.faces)

gltf = {
    'asset': {'version': '2.0', 'generator': 'DashPilot convert.py',
              'copyright': 'CC BY 4.0. "Tesla Model Y (high detail)", printables.com/model/1111101, a remix of "Tesla-model-y-3d-printable-file-stl 2" by SueRoger (Sketchfab). Decimated, materials assigned and quantized for DashPilot.'},
    'extensionsUsed': ['KHR_mesh_quantization'], 'extensionsRequired': ['KHR_mesh_quantization'],
    'scene': 0, 'scenes': [{'nodes': list(range(len(nodes)))}], 'nodes': nodes, 'meshes': meshes,
    'materials': materials, 'accessors': accessors, 'bufferViews': views, 'buffers': [{'byteLength': offset}],
}
js = json.dumps(gltf, separators=(',', ':')).encode()
js += b' ' * ((-len(js)) % 4)
binary = b''.join(bin_chunks)
with open(dst, 'wb') as fh:
    fh.write(struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(binary)))
    fh.write(struct.pack('<II', len(js), 0x4E4F534A)); fh.write(js)
    fh.write(struct.pack('<II', len(binary), 0x004E4942)); fh.write(binary)
print('faces per material', counts, 'extents m', (hi - lo).round(3).tolist())
