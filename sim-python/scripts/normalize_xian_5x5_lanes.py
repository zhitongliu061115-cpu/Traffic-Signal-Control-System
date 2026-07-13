from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


SIM_ROOT = Path(__file__).resolve().parents[1]
if str(SIM_ROOT) not in sys.path:
    sys.path.insert(0, str(SIM_ROOT))

from app.sumo_config import load_sumo_runtime_config  # noqa: E402


LANE_BY_DIRECTION = {
    "r": 0,
    "R": 0,
    "s": 1,
    "l": 2,
    "L": 2,
}


def run_netconvert(binary: Path, arguments: list[str], expected: tuple[Path, ...]) -> None:
    process = subprocess.run(
        [str(binary), *arguments],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    missing = [str(path) for path in expected if not path.exists()]
    if process.returncode != 0 and missing:
        raise RuntimeError(
            "netconvert failed:\n"
            + process.stdout
            + process.stderr
            + "\nmissing outputs: "
            + ", ".join(missing)
        )


def export_plain(config, source: Path, prefix: Path) -> tuple[Path, Path, Path]:
    nodes = prefix.with_suffix(".nod.xml")
    edges = prefix.with_suffix(".edg.xml")
    types = prefix.with_suffix(".typ.xml")
    run_netconvert(
        config.netconvert_binary,
        ["--sumo-net-file", str(source), "--plain-output-prefix", str(prefix)],
        (nodes, edges, types),
    )
    return nodes, edges, types


def normalize_edges(source: Path, target: Path) -> int:
    tree = ET.parse(source)
    root = tree.getroot()
    count = 0
    for edge in root.findall("edge"):
        edge.set("numLanes", "3")
        for lane in list(edge.findall("lane")):
            edge.remove(lane)
        count += 1
    ET.indent(tree, space="    ")
    tree.write(target, encoding="utf-8", xml_declaration=True)
    return count


def build_network(config, nodes: Path, edges: Path, types: Path, output: Path, connections: Path | None = None) -> None:
    arguments = [
        "--node-files", str(nodes),
        "--edge-files", str(edges),
        "--type-files", str(types),
        "--output-file", str(output),
        "--no-turnarounds", "true",
        "--tls.rebuild", "true",
    ]
    if connections is not None:
        arguments.extend(["--connection-files", str(connections)])
    run_netconvert(config.netconvert_binary, arguments, (output,))


def write_dedicated_connections(network, target: Path) -> int:
    root = ET.Element("connections")
    connection_keys: set[tuple[str, str, int, int]] = set()

    for node in network.getNodes():
        incoming = [edge for edge in node.getIncoming() if not edge.getFunction()]
        outgoing = [edge for edge in node.getOutgoing() if not edge.getFunction()]
        if len(incoming) == 1 and len(outgoing) == 1:
            from_edge = incoming[0]
            to_edge = outgoing[0]
            if to_edge in from_edge.getOutgoing():
                for lane_index in range(3):
                    connection_keys.add((from_edge.getID(), to_edge.getID(), lane_index, lane_index))
            continue

        for from_edge in incoming:
            for to_edge, connections in from_edge.getOutgoing().items():
                if to_edge.getFunction():
                    continue
                directions = {connection.getDirection() for connection in connections}
                for direction in directions:
                    lane_index = LANE_BY_DIRECTION.get(direction)
                    if lane_index is None:
                        continue
                    connection_keys.add((from_edge.getID(), to_edge.getID(), lane_index, lane_index))

    for from_id, to_id, from_lane, to_lane in sorted(connection_keys):
        ET.SubElement(root, "connection", {
            "from": from_id,
            "to": to_id,
            "fromLane": str(from_lane),
            "toLane": str(to_lane),
        })

    tree = ET.ElementTree(root)
    ET.indent(tree, space="    ")
    tree.write(target, encoding="utf-8", xml_declaration=True)
    return len(connection_keys)


def validate_network(sumolib, network_path: Path, strategy_path: Path) -> dict[str, object]:
    network = sumolib.net.readNet(str(network_path), withPrograms=True)
    normal_edges = [edge for edge in network.getEdges() if not edge.getFunction()]
    invalid_edges = [edge.getID() for edge in normal_edges if len(edge.getLanes()) != 3]
    if invalid_edges:
        raise RuntimeError(f"edges without exactly three lanes: {invalid_edges}")

    with strategy_path.open("r", encoding="utf-8") as file:
        payload = json.load(file)
    traffic_r_ids = set(payload["scenes"][0]["trafficRIntersections"])
    missing_movements: list[str] = []
    for intersection_id in sorted(traffic_r_ids):
        node = network.getNode(intersection_id)
        for incoming in [edge for edge in node.getIncoming() if not edge.getFunction()]:
            lane_directions: dict[int, set[str]] = {0: set(), 1: set(), 2: set()}
            for connections in incoming.getOutgoing().values():
                for connection in connections:
                    lane_directions[connection.getFromLane().getIndex()].add(connection.getDirection())
            expected = {0: "r", 1: "s", 2: "l"}
            for lane_index, direction in expected.items():
                if direction not in lane_directions[lane_index]:
                    missing_movements.append(
                        f"{intersection_id}:{incoming.getID()}:lane{lane_index}:{direction}"
                    )
    if missing_movements:
        raise RuntimeError("Traffic-R lane movements are incomplete: " + ", ".join(missing_movements))

    return {
        "edgeCount": len(normal_edges),
        "trafficLightCount": len(network.getTrafficLights()),
        "threeLaneEdgeCount": len(normal_edges),
        "trafficRIntersectionCount": len(traffic_r_ids),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Normalize xian_5x5 to right/straight/left three-lane approaches")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--strategy", type=Path, required=True)
    args = parser.parse_args()

    config = load_sumo_runtime_config()
    config.validate()
    config.add_tools_to_python_path()
    import sumolib

    source = args.input.resolve()
    output = args.output.resolve()
    with tempfile.TemporaryDirectory(prefix="xian-5x5-three-lane-") as temp_dir:
        temp = Path(temp_dir)
        nodes, exported_edges, types = export_plain(config, source, temp / "source")
        normalized_edges = temp / "normalized.edg.xml"
        edge_count = normalize_edges(exported_edges, normalized_edges)
        automatic_network = temp / "automatic.net.xml.gz"
        build_network(config, nodes, normalized_edges, types, automatic_network)
        config.add_tools_to_python_path()
        automatic = sumolib.net.readNet(str(automatic_network), withPrograms=True)
        connection_file = temp / "dedicated.con.xml"
        connection_count = write_dedicated_connections(automatic, connection_file)
        final_network = temp / "xian_5x5.net.xml.gz"
        build_network(config, nodes, normalized_edges, types, final_network, connection_file)
        validation = validate_network(sumolib, final_network, args.strategy.resolve())
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(final_network, output)

    print(json.dumps({
        "status": "ok",
        "normalizedEdgeCount": edge_count,
        "connectionCount": connection_count,
        **validation,
        "output": str(output),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
