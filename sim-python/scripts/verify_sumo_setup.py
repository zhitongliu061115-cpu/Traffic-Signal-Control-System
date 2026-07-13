from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile


SIM_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SIM_ROOT.parent
if str(SIM_ROOT) not in sys.path:
    sys.path.insert(0, str(SIM_ROOT))

from app.sumo_config import load_sumo_runtime_config  # noqa: E402


def copy_smoke_inputs(target: Path) -> None:
    source = SIM_ROOT / "data" / "sumo_smoke"
    for name in ("nodes.nod.xml", "edges.edg.xml", "routes.rou.xml", "smoke.sumocfg"):
        shutil.copy2(source / name, target / name)


def build_network(config, target: Path) -> Path:
    network = target / "network.net.xml"
    command = [
        str(config.netconvert_binary),
        "--node-files", str(target / "nodes.nod.xml"),
        "--edge-files", str(target / "edges.edg.xml"),
        "--output-file", str(network),
        "--no-turnarounds", "true",
    ]
    subprocess.run(command, check=True, capture_output=True, text=True)
    return network


def verify_traci(config, target: Path) -> dict[str, object]:
    config.add_tools_to_python_path()
    import sumolib
    import traci

    network = sumolib.net.readNet(str(target / "network.net.xml"), withPrograms=True)
    label = "traffic_signal_sumo_setup_smoke"
    connection = None
    traci.start(
        [
            str(config.binary),
            "-c", str(target / "smoke.sumocfg"),
            "--step-length", str(config.step_length),
            "--no-step-log", "true",
            "--quit-on-end", "true",
        ],
        label=label,
    )
    try:
        connection = traci.getConnection(label)
        max_active_vehicles = 0
        loaded_vehicles: set[str] = set()
        steps = 0
        while connection.simulation.getMinExpectedNumber() > 0 and steps < 1000:
            connection.simulationStep()
            vehicle_ids = set(connection.vehicle.getIDList())
            loaded_vehicles.update(vehicle_ids)
            max_active_vehicles = max(max_active_vehicles, len(vehicle_ids))
            steps += 1

        if loaded_vehicles != {"veh_we", "veh_ew", "veh_ns", "veh_sn"}:
            raise RuntimeError(f"unexpected loaded vehicles: {sorted(loaded_vehicles)}")
        if connection.simulation.getMinExpectedNumber() != 0:
            raise RuntimeError("SUMO smoke simulation did not finish within 1000 steps")

        return {
            "status": "ok",
            "sumoHome": str(config.home),
            "sumoBinary": str(config.binary),
            "stepLength": config.step_length,
            "junctionCount": len(network.getNodes()),
            "edgeCount": len([edge for edge in network.getEdges() if not edge.getID().startswith(":" )]),
            "trafficLightCount": len(network.getTrafficLights()),
            "loadedVehicleIds": sorted(loaded_vehicles),
            "maxActiveVehicles": max_active_vehicles,
            "simulationSteps": steps,
        }
    finally:
        if connection is not None:
            connection.close()


def main() -> None:
    config = load_sumo_runtime_config()
    config.validate()
    with tempfile.TemporaryDirectory(prefix="traffic-sumo-smoke-") as temp_dir:
        target = Path(temp_dir)
        copy_smoke_inputs(target)
        build_network(config, target)
        result = verify_traci(config, target)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
