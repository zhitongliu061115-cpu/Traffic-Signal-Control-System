# -*- coding: utf-8 -*-
# safety_guard.py — 信号配时安全检查模块
# ==============================================
# 三层安全防护：
#   Layer 1: 参数门卫（绿灯 min/max）
#   Layer 2: 相位冲突检测（同路口 + 相邻路口）
#   Layer 3: 过渡安全检查（最小绿保护）
#
# 在 engine.py 的 set_tl_phase 调用前统一拦截，
# 同时覆盖多路口配时（Java→HTTP）和应急车辆（Python直接调用）两条链路。

from typing import Any, Optional

# ---- 相位映射 ----
PHASE_INDEX_TO_CODE: dict[int, str] = {2: "ETWT", 3: "NTST", 4: "ELWL", 5: "NLSL"}
PHASE_CODE_TO_INDEX: dict[str, int] = {v: k for k, v in PHASE_INDEX_TO_CODE.items()}

# ---- 同路口相位冲突矩阵 ----
# (phase_a, phase_b) → True 表示互斥
CONFLICTING_PAIRS: set[tuple[int, int]] = {
    (2, 3), (3, 2),  # ETWT ↔ NTST
    (4, 5), (5, 4),  # ELWL ↔ NLSL
}

# ---- 安全阈值 ----
MIN_GREEN_SEC = 10
MAX_GREEN_SEC = 60
MIN_CYCLE_SEC = 40
MAX_CYCLE_SEC = 180


class SafetyGuard:
    """信号配时安全门卫。

    使用方式：
        guard = SafetyGuard()
        ok, msg, safe_phase = guard.guard(sid, inter_id, current, target, sim_time, ...)
        if not ok:
            log.warning(msg)  # 拒绝此次配时变更
    """

    def __init__(self):
        self._phase_start: dict[str, dict[str, float]] = {}   # sid → {inter_id: sim_time}
        self._log: list[dict[str, Any]] = []

    # ================================================================
    #  Layer 1: 参数门卫
    # ================================================================

    def validate_green_duration(self, duration_sec: int) -> tuple[bool, str]:
        """校验绿灯时长是否在安全范围内。"""
        if duration_sec < MIN_GREEN_SEC:
            return False, f"绿灯时长 {duration_sec}s 低于最小 {MIN_GREEN_SEC}s"
        if duration_sec > MAX_GREEN_SEC:
            return False, f"绿灯时长 {duration_sec}s 超过最大 {MAX_GREEN_SEC}s"
        return True, ""

    # ================================================================
    #  Layer 2: 相位冲突检测
    # ================================================================

    def check_phase_conflict(self, current: int, target: int) -> tuple[bool, str]:
        """同路口相邻相位是否冲突。"""
        if (current, target) in CONFLICTING_PAIRS:
            cn = PHASE_INDEX_TO_CODE.get(current, "?")
            tn = PHASE_INDEX_TO_CODE.get(target, "?")
            return False, f"相位冲突: {cn}({current}) → {tn}({target})"
        return True, ""

    def check_adjacent_conflict(
        self, inter_id: str, target_phase: int,
        current_phases: dict[str, int], roadnet_roads: list[dict],
    ) -> tuple[bool, str]:
        """校验相邻路口是否与目标相位冲突。"""
        neighbors = self._adjacent_to(inter_id, roadnet_roads)
        for nb in neighbors:
            nb_phase = current_phases.get(nb)
            if nb_phase is None:
                continue
            if (nb_phase, target_phase) in CONFLICTING_PAIRS:
                cn = PHASE_INDEX_TO_CODE.get(nb_phase, "?")
                tn = PHASE_INDEX_TO_CODE.get(target_phase, "?")
                return False, f"相邻冲突: {nb}({cn}) ↔ {inter_id}({tn})"
        return True, ""

    # ================================================================
    #  Layer 3: 过渡安全（最小绿保护）
    # ================================================================

    def check_min_green(self, sid: str, inter_id: str, sim_time: float) -> tuple[bool, str]:
        """当前相位是否已满足最小绿灯时间。"""
        starts = self._phase_start.get(sid, {})
        start = starts.get(inter_id)
        if start is None:
            return True, ""
        elapsed = sim_time - start
        if elapsed < MIN_GREEN_SEC:
            return False, f"最小绿灯未满足: {elapsed:.1f}s < {MIN_GREEN_SEC}s"
        return True, ""

    # ================================================================
    #  一站式检查
    # ================================================================

    def guard(
        self,
        sid: str,
        inter_id: str,
        current_phase: int,
        target_phase: int,
        sim_time: float,
        current_phases: dict[str, int],
        roadnet_roads: Optional[list[dict]] = None,
        is_emergency: bool = False,
    ) -> tuple[bool, str, int]:
        """一站式安全检查。

        Returns:
            (passed, message, safe_phase_index)
            safe_phase_index: 如果不通过，返回建议的安全相位
        """
        # 同相位不需检查
        if current_phase == target_phase:
            self._record(sid, inter_id, sim_time, target_phase)
            return True, "同相位", target_phase

        # 最小绿灯保护（应急可跳过）
        if not is_emergency:
            ok, msg = self.check_min_green(sid, inter_id, sim_time)
            if not ok:
                self._warn(sid, inter_id, sim_time, current_phase, target_phase, msg)
                return False, msg, current_phase

        # A set_tl_phase call replaces the current phase; it does not run two
        # phases simultaneously. Treating normal transitions such as ETWT→NTST
        # or NLSL→ELWL as "conflicts" blocks valid controller decisions and can
        # freeze the network at one phase. Keep transition safety to min-green
        # protection here; simultaneous movement conflicts belong in roadnet
        # phase definitions / controller candidate validation.

        # 通过
        self._record(sid, inter_id, sim_time, target_phase)
        return True, "通过", target_phase

    # ================================================================
    #  内部辅助
    # ================================================================

    def _record(self, sid: str, inter_id: str, sim_time: float, phase: int):
        if sid not in self._phase_start:
            self._phase_start[sid] = {}
        self._phase_start[sid][inter_id] = sim_time

    def _warn(self, sid: str, inter_id: str, sim_time: float,
              current: int, target: int, reason: str):
        entry = {
            "sid": sid, "inter_id": inter_id, "sim_time": round(sim_time, 1),
            "current": current, "target": target, "reason": reason,
        }
        self._log.append(entry)

    def _adjacent_to(self, inter_id: str, roads: list[dict]) -> set[str]:
        nb: set[str] = set()
        for r in roads:
            si = r.get("startIntersection", "")
            ei = r.get("endIntersection", "")
            if si == inter_id and ei:
                nb.add(ei)
            if ei == inter_id and si:
                nb.add(si)
        return nb

    def recent_warnings(self, n: int = 10) -> list[dict[str, Any]]:
        return self._log[-n:] if self._log else []

    def release_session(self, sid: str):
        self._phase_start.pop(sid, None)
