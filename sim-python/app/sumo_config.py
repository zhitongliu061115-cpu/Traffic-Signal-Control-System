from dataclasses import dataclass
from pathlib import Path
import os
import sys


DEFAULT_SUMO_HOME = Path(r"D:\Download\SUMO")


@dataclass(frozen=True)
class SumoRuntimeConfig:
    home: Path
    binary: Path
    gui_binary: Path
    netconvert_binary: Path
    tools_dir: Path
    step_length: float

    def validate(self) -> None:
        required = {
            "SUMO_HOME": self.home,
            "SUMO_BINARY": self.binary,
            "SUMO_GUI_BINARY": self.gui_binary,
            "SUMO_NETCONVERT_BINARY": self.netconvert_binary,
            "SUMO tools": self.tools_dir,
        }
        missing = [f"{name}={path}" for name, path in required.items() if not path.exists()]
        if missing:
            raise RuntimeError("SUMO runtime is incomplete: " + "; ".join(missing))
        if self.step_length <= 0:
            raise RuntimeError("SUMO_STEP_LENGTH must be greater than 0")

    def add_tools_to_python_path(self) -> None:
        tools = str(self.tools_dir)
        if tools not in sys.path:
            sys.path.insert(0, tools)


def load_sumo_runtime_config() -> SumoRuntimeConfig:
    home = Path(os.getenv("SUMO_HOME", str(DEFAULT_SUMO_HOME))).expanduser()
    bin_dir = home / "bin"
    return SumoRuntimeConfig(
        home=home,
        binary=Path(os.getenv("SUMO_BINARY", str(bin_dir / "sumo.exe"))).expanduser(),
        gui_binary=Path(os.getenv("SUMO_GUI_BINARY", str(bin_dir / "sumo-gui.exe"))).expanduser(),
        netconvert_binary=Path(
            os.getenv("SUMO_NETCONVERT_BINARY", str(bin_dir / "netconvert.exe"))
        ).expanduser(),
        tools_dir=home / "tools",
        step_length=float(os.getenv("SUMO_STEP_LENGTH", "0.2")),
    )
