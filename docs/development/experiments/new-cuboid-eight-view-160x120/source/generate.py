"""Blender-only deterministic synthetic textured cuboid; no reconstruction output."""
import bpy, json, math, sys, struct, zlib
from pathlib import Path
import numpy as np
from mathutils import Vector

def canonical_png(path):
    raw = path.read_bytes()
    assert raw[:8] == b'\x89PNG\r\n\x1a\n'
    parts = [raw[:8]]
    offset = 8
    while offset < len(raw):
        length = struct.unpack('>I', raw[offset:offset + 4])[0]
        kind = raw[offset + 4:offset + 8]
        end = offset + 12 + length
        assert end <= len(raw) and zlib.crc32(raw[offset + 4:end - 4]) == struct.unpack('>I', raw[end - 4:end])[0]
        if kind not in (b'tEXt', b'zTXt', b'iTXt'):
            parts.append(raw[offset:end])
        offset = end
    path.write_bytes(b''.join(parts))
out = Path(sys.argv[sys.argv.index('--') + 1])
out.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
s = bpy.context.scene
s.render.engine = 'CYCLES'
s.cycles.device = 'CPU'
s.cycles.samples = 32
s.cycles.seed = 78424
s.cycles.use_denoising = False
s.render.resolution_x = 160
s.render.resolution_y = 120
s.render.resolution_percentage = 100
s.render.image_settings.file_format = 'PNG'
s.render.image_settings.color_mode = 'RGBA'
s.view_settings.view_transform = 'Standard'
s.view_settings.look = 'None'
s.view_settings.exposure = 0
s.view_settings.gamma = 1
s.world = bpy.data.worlds.new('black')
s.world.use_nodes = True
s.world.node_tree.nodes['Background'].inputs['Color'].default_value = (0.025, 0.025, 0.025, 1)
bpy.ops.mesh.primitive_cube_add()
obj = bpy.context.object
obj.name = 'synthetic_textured_cuboid'
obj.scale = (1, 0.75, 0.65)
bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
rng = np.random.default_rng(78424)
for i, poly in enumerate(obj.data.polygons):
    mat = bpy.data.materials.new('mat' + str(i))
    mat.use_nodes = True
    n = mat.node_tree.nodes
    n.clear()
    output = n.new('ShaderNodeOutputMaterial')
    em = n.new('ShaderNodeEmission')
    tex = n.new('ShaderNodeTexImage')
    tex.interpolation = 'Linear'
    image = bpy.data.images.new('seeded' + str(i), width=128, height=128)
    rgb = rng.uniform(0.03, 0.8, (128, 128, 3))
    rgba = np.concatenate((rgb, np.ones((128, 128, 1))), axis=2).astype('float32')
    image.pixels.foreach_set(rgba.ravel())
    tex.image = image
    mat.node_tree.links.new(tex.outputs['Color'], em.inputs['Color'])
    mat.node_tree.links.new(em.outputs[0], output.inputs['Surface'])
    obj.data.materials.append(mat)
    poly.material_index = i
uv = obj.data.uv_layers.active
for p in obj.data.polygons:
    for li, coord in zip(p.loop_indices, [(0, 0), (1, 0), (1, 1), (0, 1)]):
        uv.data[li].uv = coord
obj.data.calc_loop_triangles()
vertices = [list(v.co) for v in obj.data.vertices]
triangles = [list(t.vertices) for t in obj.data.loop_triangles]
bpy.ops.object.camera_add()
cam = bpy.context.object
s.camera = cam
cam.data.type = 'PERSP'
cam.data.lens = 45
cam.data.sensor_width = 36
cam.data.sensor_fit = 'HORIZONTAL'
cam.data.clip_start = 0.1
cam.data.clip_end = 100
cameras = []
ray_witness = []
for i, angle in enumerate(np.linspace(-35, 35, 8)):
    a = math.radians(float(angle))
    center = Vector((5 * math.sin(a), -5 * math.cos(a), 1.8))
    cam.location = center
    cam.rotation_euler = (-center).to_track_quat('-Z', 'Y').to_euler()
    bpy.context.view_layer.update()
    cv = np.diag([1, -1, -1])
    r = cv @ np.array(cam.matrix_world.to_3x3()).T
    t = -r @ np.array(center)
    f = 160 * 45 / 36
    cameras.append([*r.ravel().tolist(), *t.tolist(), f, f, 79.5, 59.5])
    s.render.filepath = str(out / f'view-{i:02d}.png')
    bpy.ops.render.render(write_still=True)
    canonical_png(out / f'view-{i:02d}.png')
    for y in [30, 45, 60, 75, 90]:
        for x in [30, 50, 70, 90, 110, 130]:
            direction = Vector(r.T @ np.array([(x - 79.5) / f, (y - 59.5) / f, 1.0]))
            direction.normalize()
            hit, location, normal, face_index, hit_obj, matrix = s.ray_cast(bpy.context.evaluated_depsgraph_get(), center, direction, distance=100)
            depth = float((r @ np.array(location) + t)[2]) if hit else None
            ray_witness.append({
                'view': i,
                'x': x,
                'y': y,
                'cameraZ': depth,
            })
manifest = {
    'schema': 1,
    'synthetic': True,
    'scene': 'seed78424 textured cuboid 2 x 1.5 x 1.3, front arc -35..35 deg, elevation z=1.8, radius=5',
    'seed': 78424,
    'numpyVersion': np.__version__,
    'renderSamples': 32,
    'rayWitness': ray_witness,
    'renderer': bpy.app.version_string,
    'material': 'fixed seeded per-face emission albedo, no specular or illumination estimation',
    'width': 160,
    'height': 120,
    'cameras': cameras,
    'vertices': vertices,
    'triangles': triangles,
    'settings': {
        'near': 3.0,
        'far': 7.0,
        'planes': 96,
        'stride': 2,
    },
    'units': 'arbitrary synthetic units',
    'imageOnlyArm': {'status': 'NOT_RUN', 'reason': 'No verified production-equivalent image-only host frontend in this baseline'},
}
(out / 'fixture.json').write_text(json.dumps(manifest, indent=2) + '\n')
