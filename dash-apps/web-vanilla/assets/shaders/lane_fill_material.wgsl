#import bevy_pbr::forward_io::VertexOutput
#import bevy_pbr::mesh_view_bindings::globals

@group(2) @binding(0) var<uniform> material_color: vec4<f32>;
@group(2) @binding(1) var<uniform> params: vec4<f32>;  // x = alpha

// End lane distance
const FAR: f32 = 50.0;
// Inset of the fill from the lane mesh edge (the original used a fixed
// 1.8 m half width on a 3.8 m mesh)
const INSET: f32 = 0.1;
// Lane depth offset
const DEPTH: f32 = 3.5;
// Lane corner roundness
const ROUND: f32 = 0.5;
// Softness of the edges
const SOFTNESS: f32 = 1.0;

// The fill mesh is the curved lane strip: u runs across it, v along it from
// MESH_START meters (behind the car) to the lane's view range. Its length is
// recovered from screen-space derivatives and snapped to MESH_LENGTH_STEP so
// it is identical on every fragment, inner and outer edge of a curve alike;
// the dashboard sends view ranges in whole MESH_LENGTH_STEP steps.
const MESH_START: f32 = -20.0;
const MESH_LENGTH_STEP: f32 = 10.0;

// Pulse animation parameters
const PULSE_SPEED: f32 = 2.0;
const PULSE_AMPLITUDE: f32 = 0.3;
const PULSE_FREQUENCY: f32 = 0.2;

// Arrow point slope (0 = flat)
const ARROW_SLOPE: f32 = 0.5;
// Arrow scroll speed (radians per second)
const ARROW_SPEED: f32 = 4.0;
// Arrow frequency (cycles per unit distance)
const ARROW_FREQUENCY: f32 = 1.0;
// Arrow thickness (0 = no arrow, 2 = full arror)
const ARROW_THICKNESS: f32 = 0.2;
// Between arrow opacity (0 = transparent, 1 = opaque)
const ARROW_OPACITY: f32 = 0.5;

fn antialias(d: f32) -> f32 {
    let dxy = vec2<f32>(dpdx(d), dpdy(d));
    return smoothstep(-SOFTNESS, SOFTNESS, d / max(length(dxy), 0.0001));
}

@fragment
fn fragment(
    mesh: VertexOutput,
) -> @location(0) vec4<f32> {
    // Lane-aligned coordinates: across = lateral meters from the lane center,
    // along = meters along the lane (0 at the car). Falls back to straight
    // world axes if the mesh has no UVs.
    var across = mesh.world_position.x;
    var along = mesh.world_position.z;
    var half_width = 1.9;
#ifdef VERTEX_UVS
    let p = mesh.world_position.xyz;
    let du = vec2<f32>(dpdx(mesh.uv.x), dpdy(mesh.uv.x));
    let dv = vec2<f32>(dpdx(mesh.uv.y), dpdy(mesh.uv.y));
    let det = dv.x * du.y - dv.y * du.x;
    if (abs(det) > 1e-12) {
        let dp_du = (dpdy(p) * dv.x - dpdx(p) * dv.y) / det;
        let dp_dv = (dpdx(p) * du.y - dpdy(p) * du.x) / det;
        let len = max(round(length(dp_dv) / MESH_LENGTH_STEP), 1.0) * MESH_LENGTH_STEP;
        half_width = 0.5 * length(dp_du);
        across = (mesh.uv.x - 0.5) * 2.0 * half_width;
        along = MESH_START + mesh.uv.y * len;
    }
#endif

    // XY distance to lane corners
    let corner = ROUND + vec2<f32>(abs(across) - (half_width - INSET), -DEPTH - along);
    // Pulse waves
    let wave = sin(corner.y * PULSE_FREQUENCY - globals.time * PULSE_SPEED);
    let pulse = wave * PULSE_AMPLITUDE + 1.0 - PULSE_AMPLITUDE;

    let arrow_d = ARROW_THICKNESS - 1.0 - sin((corner.y - ARROW_SLOPE*abs(across) - globals.time * ARROW_SPEED) * ARROW_FREQUENCY);
    let arrow = mix(ARROW_OPACITY, 1.0, antialias(arrow_d));
    
    var color = material_color;

    // Distance to lane edges
    let d = ROUND - (length(max(corner, vec2<f32>(0.0))) + min(max(corner.x, corner.y), 0.0));
    let aa = antialias(d);

    // Blend with pulse and antialiasing
    color.a = color.a * aa * pulse * arrow * smoothstep(-FAR, 0.0, corner.y);
    return color;
}
