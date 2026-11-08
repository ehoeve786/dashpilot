// 3D car for the Sport dashboard: a Tesla Model Y (CC BY 4.0, see model/ATTRIBUTION.md) rendered
// with three.js over the tyres panel. Doors, frunk, liftgate and mirrors are separate parts hinged
// at their pivots; the liftgate follows the real angle. The roof is cut away at the beltline to show
// a simple cabin: seats with their heater and belt state. Falls back to the SVG car without WebGL.
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
  renderer.localClippingEnabled = true;

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
    // Mirrors fold back on a vertical hinge at their base.
    mirror_l: { axis: 'z', sign: 1, open: 65 }, mirror_r: { axis: 'z', sign: -1, open: 65 },
  };
  // Everything above the beltline is cut away, in each part's own frame so an open door or lid
  // keeps its cut. The mirrors stand above it and are kept whole.
  const CUT_Z = 47;
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
        if (!part.startsWith('mirror')) {
          p.plane = new THREE.Plane();
          p.cut = new THREE.Plane(new THREE.Vector3(0, 0, -1), CUT_Z - node.position.z);
        }
        car.add(p.group);
      }
      const clipped = (material) => {
        if (!p.plane) return material;
        const c = material.clone();
        c.clippingPlanes = [p.plane];
        return c;
      };
      const m = node.clone();
      m.position.set(0, 0, 0);
      m.material = clipped(MATS[mat] || paint);
      p.group.add(m);
      if (mat === 'paint' || mat === 'glass') {
        const back = m.clone();
        back.material = clipped(interior);
        back.userData.inside = true;
        p.group.add(back);
      }
    });
    // Open markers, faded in with the opening angle. Doors: a door-thick slab just inside the skin
    // (sill to beltline) that swings with the door. Lids: a warm glow in the opening (the interior
    // light) and an outline on the lid itself, which is otherwise hard to make out from above.
    const box = new THREE.Box3();
    for (const name in HINGES) {
      const p = parts[name];
      if (!p || name.startsWith('mirror')) continue;
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
        edges.clippingPlanes = [p.plane];
        for (const m of p.group.children.slice()) {
          if (m.isMesh && !m.userData.inside) p.group.add(new THREE.LineSegments(new THREE.EdgesGeometry(m.geometry, 35), edges));
        }
        p.markers.push([edges, 0.55]);
      }
    }
    // The mirrors sit on the front doors and swing with them.
    for (const side of ['l', 'r']) {
      const door = parts['door_f' + side], mirror = parts['mirror_' + side];
      if (door && mirror) door.group.attach(mirror.group);
    }
    buildCabin();
    updateCuts();
    ready = true;
    document.getElementById('carSvg').style.display = 'none';
    if (pending) update(pending);
    fit();
    dirty = true;
    tick();
  }, undefined, () => { /* keep the SVG car */ });

  // The cabin under the cut-away roof, built from simple shapes (the model is a hollow shell): floor,
  // dash with the screen and wheel, centre console, two front seats and a three-seat bench. A seat
  // turns red when someone sits there unbuckled (like the car's own display), shows a green belt
  // across it when buckled, and an amber heater icon with one to three waves for the heater level.
  // Model units: 196 = 4.75 m, nose at -y, driver at +x.
  const seats = {};   // FL FR RL RC RR -> { mat, strap, heat }
  const SEAT = 0x1e2127, SEAT_UNBUCKLED = 0xa3231c;
  const heatTex = [null, 1, 2, 3].map((n) => {
    if (!n) return null;
    const c = document.createElement('canvas');
    c.width = c.height = 128;
    const x = c.getContext('2d');
    x.strokeStyle = '#ffab2e'; x.lineWidth = 11; x.lineCap = 'round';
    for (let i = 0; i < n; i++) {
      const cx = 64 + (i - (n - 1) / 2) * 34;
      x.beginPath();
      x.moveTo(cx, 112);
      x.bezierCurveTo(cx - 22, 88, cx + 22, 64, cx, 40);
      x.bezierCurveTo(cx - 14, 26, cx + 6, 18, cx, 12);
      x.stroke();
    }
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  });
  function slab(w, d, h, r, mat) {
    const sh = new THREE.Shape(), x = w / 2 - r, y = d / 2 - r;
    sh.moveTo(-x, -d / 2); sh.lineTo(x, -d / 2); sh.quadraticCurveTo(w / 2, -d / 2, w / 2, -y);
    sh.lineTo(w / 2, y); sh.quadraticCurveTo(w / 2, d / 2, x, d / 2); sh.lineTo(-x, d / 2);
    sh.quadraticCurveTo(-w / 2, d / 2, -w / 2, y); sh.lineTo(-w / 2, -y); sh.quadraticCurveTo(-w / 2, -d / 2, -x, -d / 2);
    const geo = new THREE.ExtrudeGeometry(sh, { depth: h, bevelEnabled: true, bevelThickness: 0.8, bevelSize: 0.8, bevelSegments: 2, curveSegments: 4 });
    return new THREE.Mesh(geo, mat);
  }
  function buildCabin() {
    const cabin = new THREE.Group();
    car.add(cabin);
    const dark = new THREE.MeshStandardMaterial({ color: 0x15171b, roughness: 0.9, metalness: 0 });
    const trimMat = new THREE.MeshStandardMaterial({ color: 0x23262c, roughness: 0.6, metalness: 0.2 });
    const floor = new THREE.Mesh(new THREE.PlaneGeometry(64, 86), new THREE.MeshStandardMaterial({ color: 0x0e1013, roughness: 1 }));
    floor.position.set(0, 16, 15.5);
    cabin.add(floor);
    const dash = slab(64, 9, 7, 3, dark);
    dash.position.set(0, -25, 30);
    cabin.add(dash);
    const screen = new THREE.Mesh(new THREE.BoxGeometry(17, 1.2, 10),
      new THREE.MeshStandardMaterial({ color: 0x0b0d10, roughness: 0.2, metalness: 0.3, emissive: 0x1b2633 }));
    screen.position.set(0, -19, 41);
    screen.rotation.x = -0.35;
    cabin.add(screen);
    const wheel = new THREE.Mesh(new THREE.TorusGeometry(6, 0.9, 8, 28), trimMat);
    wheel.position.set(15.5, -17, 36);
    wheel.rotation.x = -70 * Math.PI / 180;
    cabin.add(wheel);
    const console_ = slab(10, 30, 9, 2, dark);
    console_.position.set(0, -4, 16);
    cabin.add(console_);
    const strapMat = new THREE.MeshBasicMaterial({ color: 0x34c759 });
    const layout = { FL: [15.5, 2, 22], FR: [-15.5, 2, 22], RL: [17, 33, 16], RC: [0, 33, 16], RR: [-17, 33, 16] };
    for (const id in layout) {
      const [x, y, w] = layout[id];
      const mat = new THREE.MeshStandardMaterial({ color: SEAT, roughness: 0.85, metalness: 0 });
      const seat = new THREE.Group();
      seat.position.set(x, y, 0);
      const cushion = slab(w, 20, 5, 3, mat);
      cushion.position.set(0, 0, 18);
      const back = slab(w, 6, 22, 3, mat);
      back.rotation.x = -0.25;   // upright, leaning back
      back.position.set(0, 12, 21);
      seat.add(cushion, back);
      if (y < 20) {
        const head = slab(w * 0.55, 5, 5, 2, mat);
        head.rotation.x = -0.25;
        head.position.set(0, 18.5, 41);
        seat.add(head);
      }
      // Belt: from the outer shoulder down across the seat to the inner hip.
      const outer = x === 0 ? 1 : Math.sign(x);
      const strap = new THREE.Mesh(new THREE.BoxGeometry(2.2, 26, 0.6), strapMat);
      strap.position.set(0, 4, 25.5);
      strap.rotation.z = outer * 0.55;
      strap.visible = false;
      // Heater icon on the front of the cushion, facing up (the nose is at the top of the screen).
      const heat = new THREE.Mesh(new THREE.PlaneGeometry(9, 9),
        new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false }));
      heat.position.set(0, -5, 25);
      heat.rotation.z = Math.PI;
      heat.visible = false;
      seat.add(strap, heat);
      cabin.add(seat);
      seats[id] = { mat, strap, heat };
    }
  }
  function setSeat(id, heat, belt) {
    const s = seats[id];
    if (!s) return;
    const level = Math.max(0, Math.min(3, Math.round(heat)));
    s.heat.visible = level > 0;
    if (level) s.heat.material.map = heatTex[level];
    s.heat.material.needsUpdate = true;
    s.mat.color.setHex(belt === 1 ? SEAT_UNBUCKLED : SEAT);
    s.strap.visible = belt === 2;
  }
  function updateCuts() {
    car.updateMatrixWorld(true);
    for (const name in parts) {
      const p = parts[name];
      if (p.plane) p.plane.copy(p.cut).applyMatrix4(p.group.matrixWorld);
    }
  }

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
    updateCuts();
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
    setTarget('mirror_l', n(c.mirrorFoldedL, 0) > 0 ? HINGES.mirror_l.open : 0);
    setTarget('mirror_r', n(c.mirrorFoldedR, 0) > 0 ? HINGES.mirror_r.open : 0);
    for (const id in seats) setSeat(id, n(c['seatHeat' + id], 0), n(c['seatBelt' + id], 0));
    dirty = true;
    setQuality(n(c.renderQuality, 3));
    schedule();
  }

  window.addEventListener('resize', () => { fit(); schedule(); });
  window.visualViewport && window.visualViewport.addEventListener('resize', () => { fit(); schedule(); });
  // Jump every part to its target (used by capture tooling; animation is the normal path).
  function settle() {
    for (const name in parts) { const p = parts[name]; p.value = p.to; apply(name, p.to); }
    updateCuts();
    animating = false; dirty = true; tick();
  }
  window.car3d = { update, settle };
})();
