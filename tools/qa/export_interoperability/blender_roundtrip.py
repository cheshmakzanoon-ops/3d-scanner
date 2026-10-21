"""Run only through Blender --background --factory-startup --python ... -- input output."""
import sys
from pathlib import Path
import bpy

source, target = map(Path, sys.argv[sys.argv.index('--') + 1:])
if bpy.app.version[:2] != (4, 3):
    raise RuntimeError('Qualified Blender series is 4.3')
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
suffix = source.suffix.lower()
if suffix == '.glb':
    bpy.ops.import_scene.gltf(filepath=str(source))
elif suffix == '.obj':
    bpy.ops.wm.obj_import(filepath=str(source), forward_axis='Y', up_axis='Z')
elif suffix == '.ply':
    bpy.ops.wm.ply_import(filepath=str(source), forward_axis='Y', up_axis='Z')
elif suffix == '.stl':
    bpy.ops.wm.stl_import(filepath=str(source), forward_axis='Y', up_axis='Z')
else:
    raise RuntimeError('Unsupported input')
if not any(obj.type == 'MESH' for obj in bpy.context.scene.objects):
    raise RuntimeError('No imported mesh; points are not a triangle round-trip')
if suffix == '.glb':
    bpy.ops.export_scene.gltf(filepath=str(target), export_format='GLB', export_yup=True)
elif suffix == '.obj':
    bpy.ops.wm.obj_export(filepath=str(target), forward_axis='Y', up_axis='Z', export_triangulated_mesh=True, path_mode='COPY')
elif suffix == '.ply':
    bpy.ops.wm.ply_export(filepath=str(target), forward_axis='Y', up_axis='Z')
else:
    bpy.ops.wm.stl_export(filepath=str(target), forward_axis='Y', up_axis='Z')
