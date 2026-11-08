// 3D car for the Sport dashboard: a Tesla Model Y (CC BY 4.0, see model/ATTRIBUTION.md) rendered
// with three.js over the tyres panel. Doors, frunk and liftgate are separate parts hinged at their
// pivots; the liftgate follows the real angle. Falls back to the SVG car when WebGL is unavailable.
(function () {
  'use strict';
  if (!window.THREE3D) return;
  const { THREE, GLTFLoader, RoomEnvironment } = window.THREE3D;

  const canvas = document.getElementById('car3dCanvas');
  const panel = document.getElementById('tyres');
  const wrap = document.getElementById('tyresWrap');
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'low-power' });
  } catch (e) {
    window.car3dError = String(e && e.message || e);
    return;
  }
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;

  // Panel is a 460x630 viewBox (xMidYMid meet); the canvas covers its top 510 units so the car,
  // centred in the canvas, sits at viewBox y = 255 like the SVG car.
  const VB_W = 460, VB_H = 630, CANVAS_H = 510;
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(22, VB_W / CANVAS_H, 50, 3000);
  // Car centre in model units (nose at -y, +z up). Camera above, tilted 10 deg toward the rear.
  const CENTER = new THREE.Vector3(0, 5.5, 30);
  const TILT = 10 * Math.PI / 180, DIST = 620;
  camera.position.set(0, CENTER.y + DIST * Math.sin(TILT), CENTER.z + DIST * Math.cos(TILT));
  camera.up.set(0, -1, 0);
  camera.lookAt(CENTER);

  // Environment: a soft overcast sky, bright above and dark below, so reflections are broad sheens
  // rather than the hard light panels of a room environment.
  const envCanvas = document.createElement('canvas');
  envCanvas.width = 64; envCanvas.height = 32;
  const g = envCanvas.getContext('2d');
  const grad = g.createLinearGradient(0, 0, 0, 32);
  grad.addColorStop(0, '#ffffff'); grad.addColorStop(0.3, '#c6ccd4'); grad.addColorStop(0.5, '#5a616a'); grad.addColorStop(0.62, '#262a30'); grad.addColorStop(1, '#0c0e11');
  g.fillStyle = grad; g.fillRect(0, 0, 64, 32);
  const envTex = new THREE.CanvasTexture(envCanvas);
  envTex.mapping = THREE.EquirectangularReflectionMapping;
  envTex.colorSpace = THREE.SRGBColorSpace;
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromEquirectangular(envTex).texture;
  void RoomEnvironment;
  // No point lights: the sky environment alone lights the car, so there are no hot spots.
  scene.add(new THREE.HemisphereLight(0xc0c8d2, 0x14161a, 1.0));

  // Midnight Silver Metallic.
  const paint = new THREE.MeshPhysicalMaterial({ color: 0x3a3e44, metalness: 0.8, roughness: 0.45,
    clearcoat: 1, clearcoatRoughness: 0.3, envMapIntensity: 1.3 });
  const MATS = {
    paint,
    glass: new THREE.MeshPhysicalMaterial({ color: 0x0a0d12, metalness: 0.1, roughness: 0.3, clearcoat: 0.5, envMapIntensity: 0.9 }),
    tyre: new THREE.MeshStandardMaterial({ color: 0x111317, roughness: 0.95, metalness: 0 }),
    rim: new THREE.MeshStandardMaterial({ color: 0x8a8f99, roughness: 0.35, metalness: 1 }),
    trim: new THREE.MeshStandardMaterial({ color: 0x15181c, roughness: 0.9, metalness: 0 }),
    light: new THREE.MeshStandardMaterial({ color: 0xc9d2dd, roughness: 0.3, metalness: 0.2, emissive: 0x2a3038 }),
    tail: new THREE.MeshStandardMaterial({ color: 0x8c0f0f, roughness: 0.4, metalness: 0.1, emissive: 0x3a0404 }),
  };
  const interior = new THREE.MeshStandardMaterial({ color: 0x0b0c0f, roughness: 1, metalness: 0, side: THREE.BackSide });
  // Open-part markers. Doors: a door-thick translucent slab, the way the car's own display draws
  // open doors. Frunk and liftgate: the interior light coming on, a soft warm glow in the opening,
  // plus a thin light outline on the lifted lid.
  const slabMat = new THREE.MeshBasicMaterial({ color: 0xffb020, transparent: true, opacity: 0, depthWrite: false });
  const glowCanvas = document.createElement('canvas');
  glowCanvas.width = glowCanvas.height = 128;
  const gg = glowCanvas.getContext('2d');
  const rg = gg.createRadialGradient(64, 64, 4, 64, 64, 64);
  rg.addColorStop(0, 'rgba(255,255,255,1)'); rg.addColorStop(0.45, 'rgba(255,255,255,0.55)'); rg.addColorStop(1, 'rgba(255,255,255,0)');
  gg.fillStyle = rg; gg.fillRect(0, 0, 128, 128);
  const glowTex = new THREE.CanvasTexture(glowCanvas);
  const glowMat = new THREE.MeshBasicMaterial({ map: glowTex, color: 0xffe2b0, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false });
  const edgeMat = new THREE.LineBasicMaterial({ color: 0xe6edf5, transparent: true, opacity: 0, depthWrite: false });

  // Hinges: axis and the open angle (deg) for the on/off parts; the liftgate uses the real angle.
  const HINGES = {
    door_fl: { axis: 'z', sign: -1, open: 35 }, door_rl: { axis: 'z', sign: -1, open: 35 },
    door_fr: { axis: 'z', sign: 1, open: 35 }, door_rr: { axis: 'z', sign: 1, open: 35 },
    frunk: { axis: 'x', sign: -1, open: 75 }, liftgate: { axis: 'x', sign: 1, open: 95 },
  };
  const parts = {};   // name -> { group, slab, value, from, to, t0 }
  // The model is Z-up; three.js lighting (environment, hemisphere) is Y-up. Car and camera live in
  // a rig rotated so the model's up is the world's up.
  const rig = new THREE.Group();
  rig.rotation.x = -Math.PI / 2;
  scene.add(rig);
  rig.add(camera);
  const car = new THREE.Group();
  rig.add(car);

  let ready = false, dirty = true, animating = false, pending = null;
  new GLTFLoader().load('model/model-y.glb', (gltf) => {
    gltf.scene.traverse((node) => {
      if (!node.isMesh) return;
      const [part, mat] = node.name.split('__');
      let p = parts[part];
      if (!p) {
        p = parts[part] = { group: new THREE.Group(), markers: [], value: 0, from: 0, to: 0, t0: 0 };
        p.group.position.copy(node.position);
        car.add(p.group);
      }
      const m = node.clone();
      m.position.set(0, 0, 0);
      m.material = MATS[mat] || paint;
      p.group.add(m);
      if (mat === 'paint' || mat === 'glass') {
        const back = m.clone();
        back.material = interior;
        p.group.add(back);
      }
    });
    // Open markers, faded in with the opening angle. Doors: a door-thick slab just inside the skin
    // (sill to beltline) that swings with the door. Lids: a warm glow in the opening (the interior
    // light) and an outline on the lid itself, which is otherwise hard to make out from above.
    const box = new THREE.Box3();
    for (const name in HINGES) {
      const p = parts[name];
      if (!p) continue;
      box.setFromObject(p.group);     // world == car space (rotation is zero at load)
      const size = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
      p.markers = [];
      if (name.startsWith('door')) {
        const outer = name.endsWith('l') ? box.max.x : box.min.x, sgn = name.endsWith('l') ? 1 : -1;
        const z0 = Math.max(box.min.z, 11), z1 = Math.min(box.max.z, 46);
        const slab = new THREE.Mesh(new THREE.BoxGeometry(6, size.y - 2, z1 - z0), slabMat.clone());
        slab.position.set(outer - sgn * 3, c.y, (z0 + z1) / 2).sub(p.group.position);
        p.group.add(slab);
        p.markers.push([slab.material, 0.7]);
      } else {
        const glow = new THREE.Mesh(new THREE.PlaneGeometry(size.x * 1.4, size.y * 1.5), glowMat.clone());
        glow.position.set(c.x, c.y, name === 'frunk' ? box.max.z - 10 : 44);
        car.add(glow);
        p.markers.push([glow.material, 1.0]);
        const edges = edgeMat.clone();
        for (const m of p.group.children.slice()) {
          if (m.isMesh && m.material !== interior) p.group.add(new THREE.LineSegments(new THREE.EdgesGeometry(m.geometry, 35), edges));
        }
        p.markers.push([edges, 0.55]);
      }
    }
    ready = true;
    document.getElementById('carSvg').style.display = 'none';
    if (pending) update(pending);
    fit();
    dirty = true;
    tick();
  }, undefined, () => { /* keep the SVG car */ });

  function fit() {
    const r = wrap.getBoundingClientRect();
    const s = Math.min(r.width / VB_W, r.height / VB_H);
    const w = VB_W * s, h = CANVAS_H * s;
    const left = (r.width - w) / 2, top = (r.height - VB_H * s) / 2;
    canvas.style.left = left + 'px';
    canvas.style.top = top + 'px';
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';
    renderer.setSize(Math.round(w), Math.round(h), false);
    dirty = true;
  }
  let quality = 3;
  function setQuality(q) {
    q = Math.max(1, Math.min(3, Math.round(q || 3)));
    if (q === quality && renderer.getPixelRatio()) return;
    quality = q;
    renderer.setPixelRatio(q >= 3 ? Math.min(window.devicePixelRatio || 1, 2) : q === 2 ? 1.5 : 1);
    paint.clearcoat = q >= 2 ? 1 : 0;
    dirty = true;
  }
  setQuality(3);

  function apply(name, deg) {
    const p = parts[name], h = HINGES[name];
    if (!p || !h) return;
    const a = h.sign * deg * Math.PI / 180;
    p.group.rotation.set(h.axis === 'x' ? a : 0, 0, h.axis === 'z' ? a : 0);
    const k = Math.min(1, deg / 10);
    for (const [mat, max] of p.markers || []) mat.opacity = k * max;
  }
  function setTarget(name, deg) {
    const p = parts[name];
    if (!p || p.to === deg) return;
    p.from = p.value; p.to = deg; p.t0 = performance.now();
    animating = true;
  }
  function step(now) {
    let busy = false;
    for (const name in parts) {
      const p = parts[name];
      if (p.value === p.to) continue;
      const t = Math.min(1, (now - p.t0) / 350);
      const e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
      p.value = t >= 1 ? p.to : p.from + (p.to - p.from) * e;
      apply(name, p.value);
      if (t < 1) busy = true;
    }
    animating = busy;
    dirty = true;
  }
  let raf = 0;
  function tick() {
    raf = 0;
    if (!ready) return;
    if (animating) step(performance.now());
    if (dirty) { renderer.render(scene, camera); dirty = false; }
    if (animating) raf = requestAnimationFrame(tick);
  }
  function schedule() { if (!raf) raf = requestAnimationFrame(tick); }

  function update(c) {
    pending = c;
    if (!ready) return;
    const n = (v, d) => (typeof v === 'number' && isFinite(v) ? v : d);
    for (const w of ['FL', 'FR', 'RL', 'RR']) setTarget('door_' + w.toLowerCase(), n(c['doorOpen' + w], 0) > 0 ? HINGES.door_fl.open : 0);
    setTarget('frunk', n(c.frunkOpen, 0) > 0 ? HINGES.frunk.open : 0);
    const trunkOpen = n(c.trunkOpen, 0) > 0;
    setTarget('liftgate', Math.max(Math.min(n(c.trunkPosition, 0), 95), trunkOpen ? 10 : 0));
    setQuality(n(c.renderQuality, 3));
    schedule();
  }

  window.addEventListener('resize', () => { fit(); schedule(); });
  window.visualViewport && window.visualViewport.addEventListener('resize', () => { fit(); schedule(); });
  // Jump every part to its target (used by capture tooling; animation is the normal path).
  function settle() {
    for (const name in parts) { const p = parts[name]; p.value = p.to; apply(name, p.to); }
    animating = false; dirty = true; tick();
  }
  window.car3d = { update, settle };
})();
