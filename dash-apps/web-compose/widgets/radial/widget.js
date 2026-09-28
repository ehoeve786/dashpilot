// 270° arc gauge in a 200×200 viewBox; the SVG scales to any square-ish cell.
// The value arc uses pathLength=100, so progress is a single dashoffset write.
function create(ctx) {
  const { el, props } = ctx;
  const NS = "http://www.w3.org/2000/svg";
  const CX = 100, CY = 100, R = 82;
  const START = 135, SWEEP = 270;

  if (props.accent) el.style.setProperty("--accent", props.accent);

  function point(deg, r) {
    const a = (deg * Math.PI) / 180;
    return [CX + r * Math.cos(a), CY + r * Math.sin(a)];
  }
  function arcPath(r) {
    const [x0, y0] = point(START, r);
    const [x1, y1] = point(START + SWEEP, r);
    return "M" + x0.toFixed(2) + " " + y0.toFixed(2) + " A" + r + " " + r + " 0 1 1 " + x1.toFixed(2) + " " + y1.toFixed(2);
  }
  function node(name, attrs) {
    const n = document.createElementNS(NS, name);
    for (const k in attrs) n.setAttribute(k, attrs[k]);
    return n;
  }

  const svg = node("svg", { viewBox: "0 0 200 200", class: "radial__svg", "aria-hidden": "true" });
  svg.appendChild(node("path", { d: arcPath(R), class: "radial__track", pathLength: "100" }));
  const arc = node("path", { d: arcPath(R), class: "radial__arc", pathLength: "100", "stroke-dasharray": "100 100", "stroke-dashoffset": "100" });
  svg.appendChild(arc);

  const markerGroup = node("g", { class: "radial__marker", visibility: "hidden" });
  const [mx0, my0] = point(0, R - 16);
  const [mx1, my1] = point(0, R + 16);
  markerGroup.appendChild(node("line", { x1: mx0, y1: my0, x2: mx1, y2: my1 }));
  svg.appendChild(markerGroup);

  const valueText = node("text", { x: CX, y: CY + 12, class: "radial__value", "text-anchor": "middle" });
  const valueNode = document.createTextNode("");
  valueText.appendChild(valueNode);
  svg.appendChild(valueText);

  const unitText = node("text", { x: CX, y: CY + 44, class: "radial__unit", "text-anchor": "middle" });
  unitText.textContent = ctx.unit("value");
  svg.appendChild(unitText);

  if (props.showLabel) {
    const labelText = node("text", { x: CX, y: CY - 40, class: "radial__label", "text-anchor": "middle" });
    labelText.textContent = props.label || ctx.label("value");
    svg.appendChild(labelText);
  }
  el.appendChild(svg);

  function bounds() {
    const range = ctx.range("value") || [0, 100];
    const min = props.min ?? range[0];
    const max = props.max ?? range[1];
    return max > min ? [min, max] : [0, 1];
  }
  const ratio = (v, [min, max]) => Math.min(1, Math.max(0, (v - min) / (max - min)));

  let lastText = null, lastOffset = null, lastMarker = null, lastVisibility = "hidden", lastArcVisible = true;
  return {
    update(values) {
      const b = bounds();
      const text = ctx.format("value", values.value, props.precision ?? undefined);
      if (text !== lastText) {
        lastText = text;
        valueNode.nodeValue = text;
      }
      // Half-percent steps: finer arc changes are not visible.
      const offset = values.value === undefined ? 100 : Math.round((1 - ratio(values.value, b)) * 200) / 2;
      if (offset !== lastOffset) {
        // A round cap paints a dot even at zero length, so hide the arc there.
        const visible = offset < 100;
        if (visible !== lastArcVisible) {
          lastArcVisible = visible;
          arc.setAttribute("visibility", visible ? "visible" : "hidden");
        }
        lastOffset = offset;
        arc.setAttribute("stroke-dashoffset", offset);
      }
      const m = values.marker;
      const marker = m === undefined || m <= 0 ? "" : "rotate(" + (START + SWEEP * ratio(m, b)).toFixed(1) + " " + CX + " " + CY + ")";
      if (marker !== lastMarker) {
        lastMarker = marker;
        if (marker) markerGroup.setAttribute("transform", marker);
        const visibility = marker ? "visible" : "hidden";
        if (visibility !== lastVisibility) {
          lastVisibility = visibility;
          markerGroup.setAttribute("visibility", visibility);
        }
      }
    },
  };
}
