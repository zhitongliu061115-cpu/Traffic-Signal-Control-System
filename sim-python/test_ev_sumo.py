"""
test_ev_sumo.py — Standalone test: EV dispatch + signal override on SUMO.

Prerequisites:
    pip install traci sumolib
    SUMO binary must be on PATH or set SUMO_HOME/bin

Usage:
    python test_ev_sumo.py
"""
from __future__ import annotations

import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

# Add sim-python to path so we can import app modules
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from app.ev_sumo_engine import SumoEngine
from app.ev_sumo_roadnet import parse_net_xml, enrich_grid_roadnet
from app.ev_service import EVPriorityService


# ================================================================
# 1. Generate a simple SUMO .net.xml (4 columns x 3 rows grid)
# ================================================================
def generate_grid_net(rows: int = 3, cols: int = 4, spacing: float = 300.0) -> Path:
    """Generate a SUMO .net.xml for a rows x cols grid.

    Intersection IDs: intersection_{col}_{row}
    Road IDs: road_{col}_{row}_{dir}  (dir: 0=east, 1=south, 2=west, 3=north)
    """
    net_path = Path(tempfile.gettempdir()) / "test_ev_grid.net.xml"

    nodes: list[str] = []
    edges: list[str] = []
    connections: list[str] = []
    connections: list[str] = []

    # --- Junctions (nodes) ---
    # Traffic light intersections
    for r in range(1, rows + 1):
        for c in range(1, cols + 1):
            jid = f"intersection_{c}_{r}"
            x = c * spacing
            y = r * spacing
            nodes.append(
                f'    <junction id="{jid}" type="traffic_light" x="{x}" y="{y}" '
                f'incLanes="" intLanes="" shape="{x-15},{y-15} {x+15},{y-15} {x+15},{y+15} {x-15},{y+15}"/>'
            )

    # Virtual fringe nodes (for vehicle insertion/removal)
    for r in range(1, rows + 1):
        # Left fringe
        jid = f"virtual_W_{r}"
        x = 0
        y = r * spacing
        nodes.append(f'    <junction id="{jid}" type="dead_end" x="{x}" y="{y}" incLanes="" intLanes=""/>')
        # Right fringe
        jid = f"virtual_E_{r}"
        x = (cols + 1) * spacing
        y = r * spacing
        nodes.append(f'    <junction id="{jid}" type="dead_end" x="{x}" y="{y}" incLanes="" intLanes=""/>')

    for c in range(1, cols + 1):
        # Top fringe
        jid = f"virtual_N_{c}"
        x = c * spacing
        y = 0
        nodes.append(f'    <junction id="{jid}" type="dead_end" x="{x}" y="{y}" incLanes="" intLanes=""/>')
        # Bottom fringe
        jid = f"virtual_S_{c}"
        x = c * spacing
        y = (rows + 1) * spacing
        nodes.append(f'    <junction id="{jid}" type="dead_end" x="{x}" y="{y}" incLanes="" intLanes=""/>')

    # --- Edges (roads) ---
    edge_id = 0
    road_index: dict[tuple, str] = {}

    # Horizontal roads: road_{col}_{row}_0 (east) and road_{col}_{row}_2 (west)
    for r in range(1, rows + 1):
        for c in range(1, cols):
            # East direction
            eid = f"road_{c}_{r}_0"
            edges.append(
                f'    <edge id="{eid}" from="intersection_{c}_{r}" to="intersection_{c+1}_{r}" priority="2" numLanes="1" speed="13.89">'
                f'<lane id="{eid}_0" index="0" speed="13.89" length="{spacing}" shape="{c*spacing},{r*spacing} {(c+1)*spacing},{r*spacing}"/></edge>'
            )
            # West direction
            eid2 = f"road_{c+1}_{r}_2"
            edges.append(
                f'    <edge id="{eid2}" from="intersection_{c+1}_{r}" to="intersection_{c}_{r}" priority="2" numLanes="1" speed="13.89">'
                f'<lane id="{eid2}_0" index="0" speed="13.89" length="{spacing}" shape="{(c+1)*spacing},{r*spacing} {c*spacing},{r*spacing}"/></edge>'
            )

    # Vertical roads: road_{col}_{row}_1 (south) and road_{col}_{row}_3 (north)
    for c in range(1, cols + 1):
        for r in range(1, rows):
            # South direction
            eid = f"road_{c}_{r}_1"
            edges.append(
                f'    <edge id="{eid}" from="intersection_{c}_{r}" to="intersection_{c}_{r+1}" priority="2" numLanes="1" speed="13.89">'
                f'<lane id="{eid}_0" index="0" speed="13.89" length="{spacing}" shape="{c*spacing},{r*spacing} {c*spacing},{(r+1)*spacing}"/></edge>'
            )
            # North direction
            eid2 = f"road_{c}_{r+1}_3"
            edges.append(
                f'    <edge id="{eid2}" from="intersection_{c}_{r+1}" to="intersection_{c}_{r}" priority="2" numLanes="1" speed="13.89">'
                f'<lane id="{eid2}_0" index="0" speed="13.89" length="{spacing}" shape="{c*spacing},{(r+1)*spacing} {c*spacing},{r*spacing}"/></edge>'
            )

    # Fringe → first/last intersection edges
    # Left → column 1
    for r in range(1, rows + 1):
        eid = f"road_fringe_W_{r}_0"
        edges.append(
            f'    <edge id="{eid}" from="virtual_W_{r}" to="intersection_1_{r}" priority="1" numLanes="1" speed="13.89">'
            f'<lane id="{eid}_0" index="0" speed="13.89" length="{spacing}" shape="0,{r*spacing} {spacing},{r*spacing}"/></edge>'
        )
    # Column last → right
    for r in range(1, rows + 1):
        eid = f"road_fringe_E_{r}_0"
        edges.append(
            f'    <edge id="{eid}" from="intersection_{cols}_{r}" to="virtual_E_{r}" priority="1" numLanes="1" speed="13.89">'
            f'<lane id="{eid}_0" index="0" speed="13.89" length="{spacing}" shape="{cols*spacing},{r*spacing} {(cols+1)*spacing},{r*spacing}"/></edge>'
        )
    # Top → row 1
    for c in range(1, cols + 1):
        eid = f"road_fringe_N_{c}_1"
        edges.append(
            f'    <edge id="{eid}" from="virtual_N_{c}" to="intersection_{c}_1" priority="1" numLanes="1" speed="13.89">'
            f'<lane id="{eid}_0" index="0" speed="13.89" length="{spacing}" shape="{c*spacing},0 {c*spacing},{spacing}"/></edge>'
        )
    # Row last → bottom
    for c in range(1, cols + 1):
        eid = f"road_fringe_S_{c}_1"
        edges.append(
            f'    <edge id="{eid}" from="intersection_{c}_{rows}" to="virtual_S_{c}" priority="1" numLanes="1" speed="13.89">'
            f'<lane id="{eid}_0" index="0" speed="13.89" length="{spacing}" shape="{c*spacing},{rows*spacing} {c*spacing},{(rows+1)*spacing}"/></edge>'
        )

    # --- Connections (turn directions at each intersection) ---
    for r in range(1, rows + 1):
        for c in range(1, cols + 1):
            inter = f"intersection_{c}_{r}"
            # All roads that END at this intersection (incoming)
            in_edges = []
            out_edges = []
            for e in edges:
                # Parse the edge line to extract from/to
                e_str = str(e)
                if f'to="{inter}"' in e_str or f"to='{inter}'" in e_str:
                    # Extract edge id
                    eid = e_str.split('id="')[1].split('"')[0] if 'id="' in e_str else e_str.split("id='")[1].split("'")[0]
                    in_edges.append(eid)
                if f'from="{inter}"' in e_str or f"from='{inter}'" in e_str:
                    eid = e_str.split('id="')[1].split('"')[0] if 'id="' in e_str else e_str.split("id='")[1].split("'")[0]
                    out_edges.append(eid)
            # Create connections: each incoming edge connects to each outgoing edge (except reverse)
            for in_e in in_edges:
                in_dir = None
                parts = in_e.rsplit("_", 1)
                if len(parts) == 2 and parts[1].isdigit():
                    in_dir = int(parts[1])
                for out_e in out_edges:
                    out_dir = None
                    parts2 = out_e.rsplit("_", 1)
                    if len(parts2) == 2 and parts2[1].isdigit():
                        out_dir = int(parts2[1])
                    # Determine turn type
                    if in_dir is not None and out_dir is not None:
                        diff = (out_dir - in_dir) % 4
                        if diff == 0:
                            direc = "s"
                        elif diff == 1:
                            direc = "r"
                        elif diff == 2:
                            direc = "t"
                        else:
                            direc = "l"
                    else:
                        direc = "s"
                    connections.append(
                        f'    <connection from="{in_e}" to="{out_e}" fromLane="0" toLane="0" dir="{direc}" state="M"/>'
                    )

    # --- Traffic light programs (asymmetric, no yellow) ---
    # Phase 0: NS through+left (15s)  ? short, EV on NS roads needs this
    # Phase 1: EW through+left (120s) ? LONG, EV on EW roads needs this
    # Without EV override, EV would wait up to 120s on EW roads.
    tl_logics = []
    for r in range(1, rows + 1):
        for c in range(1, cols + 1):
            inter = f"intersection_{c}_{r}"
            tl_logics.append(
                f'    <tlLogic id="{inter}" type="static" programID="0" offset="0">'
                f'<phase duration="15" state="GGggrrrr"/>'   # 0: NS (short)
                f'<phase duration="120" state="rrrrGGgg"/>'  # 1: EW (LONG)
                f'</tlLogic>'
            )

    # Assemble XML
    xml_lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<net version="1.27" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">',
        *nodes,
        *edges,
        *connections,
        *tl_logics,
        '</net>',
    ]
    net_path.write_text("\n".join(xml_lines), encoding="utf-8")
    print(f"[OK] Generated net.xml: {net_path}  ({len(nodes)} junctions, {len(edges)} edges)")
    return net_path


# ================================================================
# 2. Test
# ================================================================
def main():
    # Generate network
    net_file = generate_grid_net(rows=3, cols=4)

    # Parse roadnet
    roadnet = parse_net_xml(net_file)
    roadnet = enrich_grid_roadnet(roadnet)
    intersections = {i["id"]: i for i in roadnet["intersections"]}
    real_intersections = [i for i in intersections if not intersections[i].get("virtual")]
    print(f"[OK] Parsed roadnet: {len(roadnet['roads'])} roads, {len(real_intersections)} real intersections")
    for ri in real_intersections[:5]:
        print(f"  - {ri}")

    # Find a start/end pair for EV dispatch
    if len(real_intersections) >= 2:
        start_inter = real_intersections[0]   # intersection_1_1
        end_inter = real_intersections[-1]     # intersection_4_3
    else:
        print("ERROR: Not enough intersections")
        return 1

    print(f"\n[TEST] Dispatching EV from {start_inter} to {end_inter}")

    # Start SUMO engine
    sumo_cmd = os.environ.get("SUMO_BIN", "sumo").split()
    engine = SumoEngine(str(net_file), sumo_cmd=sumo_cmd)
    engine.start(step_length=1.0)

    # Init EV service
    ev_service = EVPriorityService()
    sid = "test_session"

    # Dispatch EV
    params = {
        "evId": "ev_test_1",
        "startIntersection": start_inter,
        "endIntersection": end_inter,
        "evType": "fire_truck",
        "maxSpeed": 20.0,
    }
    try:
        result = ev_service.dispatch(sid, "test_scene", roadnet, engine, params)
        print(f"[OK] Dispatch result: evId={result.get('evId')}, route={result.get('route')}")
        print(f"     route_roads={result.get('routeRoads', result.get('route_roads', []))[:5]}...")
    except Exception as e:
        print(f"[FAIL] Dispatch error: {e}")
        engine.close()
        return 1

    # Simulation loop with detailed signal decision logging
    max_steps = 600
    completed = False
    prev_overrides = {}
    prev_status = {}

    for step_no in range(max_steps):
        engine.next_step()
        sim_time = engine.get_current_time()

        try:
            overrides, events, status_list = ev_service.step(sid, engine, sim_time)
        except Exception as e:
            print(f"[WARN] step() error at t={sim_time:.0f}: {e}")
            continue

        # --- Detailed signal decision logging ---
        if overrides != prev_overrides:
            if overrides and not prev_overrides:
                print(f"\n  >>> SIGNAL OVERRIDE ACTIVATED at t={sim_time:.0f}s <<<")
            elif not overrides and prev_overrides:
                print(f"\n  <<< SIGNAL OVERRIDE RELEASED at t={sim_time:.0f}s >>>")

            for inter_id, target_phase in overrides.items():
                old_phase = prev_overrides.get(inter_id)
                if old_phase != target_phase or inter_id not in prev_overrides:
                    # Read current phase from SUMO (what the default cycle is at)
                    try:
                        current_phase = engine.get_tl_phase(inter_id)
                    except Exception:
                        current_phase = -1
                    action = "ADJUST" if inter_id not in prev_overrides else "UPDATE"  # [PAPER-REFACTOR]
                    print(f"  [{action}] intersection={inter_id} "
                          f"current_cycle_phase={current_phase} -> "
                          f"ev_forced_phase={target_phase} "
                          f"(skipped {abs(target_phase - current_phase) if current_phase >= 0 else '?'} phases)")

            for inter_id in prev_overrides:
                if inter_id not in overrides:
                    print(f"  [RELEASE] intersection={inter_id} (EV passed, returning to normal cycle)")

        prev_overrides = dict(overrides)

        # [PAPER-REFACTOR] Signal overrides applied via duration inside ev_service.step()

        # --- EV position tracking ---
        if status_list:
            s = status_list[0]
            # Try to get EV road info
            ev_info = None
            try:
                ev_sessions = ev_service.ev_sessions.get(sid, {})
                for ev_id, ev_sess in ev_sessions.items():
                    if not ev_sess.completed:
                        info = engine.get_vehicle_info(ev_sess.cf_vehicle_id)
                        if info:
                            ev_info = info
            except Exception:
                pass

            # Print status periodically or on important events
            is_important = (s.get("completed") or s["passedCount"] != prev_status.get("passedCount", -1))
            if step_no % 5 == 0 or is_important or overrides != prev_overrides:
                road_str = f" road={ev_info.get('road','?')}" if ev_info else ""
                phase_str = ""
                if overrides:
                    phase_str = "  overrides:"
                    for iid, ph in overrides.items():
                        phase_str += f" {iid}=>phase{ph}"
                print(f"  t={sim_time:5.0f}s | passed={s['passedCount']}/{s['totalCount']} "
                      f"| completed={s['completed']} | eta={s.get('elapsedTime', 0):.0f}s"
                      f"{road_str}{phase_str}")

            prev_status = dict(s)

            if s.get("completed"):
                completed = True
                print(f"\n{'='*50}")
                print(f"[PASS] EV COMPLETED!")
                print(f"  Total time: {s.get('elapsedTime', 0):.1f}s")
                print(f"  Route: {s.get('route_count', '')} intersections")
                print(f"  Without signal overrides, EV would have waited up to 120s at each EW intersection")
                print(f"{'='*50}")
                break

    engine.close()

    if not completed:
        print(f"\n[FAIL] EV did not complete within {max_steps} steps")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
