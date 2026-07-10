"""
Blender 脚本：为已加载的小车创建 4 个动画
在 Blender 的 Scripting 工作区粘贴运行
"""
import bpy
import math

# ---- 找到场景中的车 ----
# 确保在 Object 模式
if bpy.context.mode != 'OBJECT':
    bpy.ops.object.mode_set(mode='OBJECT')

car = bpy.context.active_object
if not car:
    for obj in bpy.context.scene.objects:
        if obj.type == 'MESH':
            car = obj
            bpy.context.view_layer.objects.active = car
            car.select_set(True)
            break

if not car:
    raise RuntimeError("请先在 3D 视图里选中车模型，再运行脚本")

print(f"操作对象: {car.name} (type={car.type})")

# 重置
car.location = (0, 0, 0)
car.rotation_euler = (0, 0, 0)
if car.animation_data:
    car.animation_data_clear()

# ---- 辅助函数 ----
def make_action(name, frames, keyframes):
    if car.animation_data:
        car.animation_data.action = None
    action = bpy.data.actions.new(name=name)
    car.animation_data_create()
    car.animation_data.action = action
    for f, x, y, z, ry in keyframes:
        bpy.context.scene.frame_set(f)
        car.location = (x, y, z)
        car.rotation_euler = (0, 0, math.radians(ry))
        car.keyframe_insert(data_path="location", frame=f)
        car.keyframe_insert(data_path="rotation_euler", frame=f)
    print(f"  ✓ {name}: {frames}帧")

# ---- 4 个动画 ----
make_action("straight", 60, [
    (1, -60, 0, 0, 0),
    (60, 60, 0, 0, 0),
])

make_action("left_turn", 90, [
    (1, -60, 0, 0, 0),
    (45, 0, 0, 0, 0),
    (90, 0, 0, 60, 90),
])

make_action("right_turn", 60, [
    (1, -60, 0, 0, 0),
    (30, 0, 0, 0, 0),
    (60, 0, 0, -60, -90),
])

make_action("stop", 30, [
    (1, -60, 0, 0, 0),
    (20, -8, 0, 0, 0),
    (30, -5, 0, 0, 0),
])

# ---- 把 4 个 Action 推入 NLA 轨道（glTF 导出多动画必须） ----
if car.animation_data:
    for action in bpy.data.actions:
        track = car.animation_data.nla_tracks.new()
        track.strips.new(action.name, 1, action)
        print(f"  NLA: {action.name} → track")

# ---- 导出 ----
bpy.context.scene.frame_set(1)
out = "C:/Users/Cxaiorui/Desktop/car.glb"
bpy.ops.export_scene.gltf(
    filepath=out,
    export_animations=True,
    export_format='GLB',
    use_selection=True,
)
print(f"\n✅ 导出完成: {out}（含 4 个动画）")
