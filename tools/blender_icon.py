"""PDM launcher icon: black void, orange >, white <, PDM wordmark.

blender --background --python tools/blender_icon.py
"""
from __future__ import annotations

import math
import os
import sys

import bpy
from mathutils import Euler

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon_out")
BLEND_OUT = os.path.join(OUT_DIR, "pdm_icon.blend")
RENDER_FG = os.path.join(OUT_DIR, "pdm_icon_fg.png")
FONT_PATHS = [
    r"C:\Windows\Fonts\ariblk.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
]
# Proxmox orange #E57000 as scene-linear
ORANGE = (0.787, 0.155, 0.0)
ORANGE_GLOW = (0.90, 0.18, 0.0)
WHITE = (1.0, 1.0, 1.0)


def load_font():
    for path in FONT_PATHS:
        if os.path.isfile(path):
            return bpy.data.fonts.load(path)
    return bpy.data.fonts[0]


def link_object(obj):
    bpy.context.scene.collection.objects.link(obj)
    return obj


def mat_paint(name, color, metal=0.08, rough=0.28, emit_mix=0.0, emit_color=None, emit_str=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    principled = nt.nodes.new("ShaderNodeBsdfPrincipled")
    principled.inputs["Base Color"].default_value = (*color, 1)
    principled.inputs["Metallic"].default_value = metal
    principled.inputs["Roughness"].default_value = rough
    if "Coat Weight" in principled.inputs:
        principled.inputs["Coat Weight"].default_value = 0.35
        principled.inputs["Coat Roughness"].default_value = 0.12
    if emit_mix > 0:
        mix = nt.nodes.new("ShaderNodeMixShader")
        emit = nt.nodes.new("ShaderNodeEmission")
        emit.inputs["Color"].default_value = (*(emit_color or color), 1)
        emit.inputs["Strength"].default_value = emit_str
        mix.inputs["Fac"].default_value = emit_mix
        nt.links.new(principled.outputs["BSDF"], mix.inputs[1])
        nt.links.new(emit.outputs["Emission"], mix.inputs[2])
        nt.links.new(mix.outputs["Shader"], out.inputs["Surface"])
    else:
        nt.links.new(principled.outputs["BSDF"], out.inputs["Surface"])
    return mat


def mat_emit(name, color, strength):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    emit = nt.nodes.new("ShaderNodeEmission")
    emit.inputs["Color"].default_value = (*color, 1)
    emit.inputs["Strength"].default_value = strength
    nt.links.new(emit.outputs["Emission"], out.inputs["Surface"])
    return mat


def prism(name, loop_xz, depth, mat, bevel_width=0.014):
    """Extrude an XZ loop along Y. loop_xz is CCW when z-up, x-right."""
    half = depth / 2
    n = len(loop_xz)
    verts = [(x, -half, z) for x, z in loop_xz] + [(x, half, z) for x, z in loop_xz]
    faces = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))]
    for i in range(n):
        j = (i + 1) % n
        faces.append((i, j, n + j, n + i))
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    link_object(obj)
    obj.data.materials.append(mat)
    if bevel_width > 0:
        bevel = obj.modifiers.new("Bevel", "BEVEL")
        bevel.width = bevel_width
        bevel.segments = 4
        bevel.limit_method = "ANGLE"
        bevel.angle_limit = math.radians(30)
    return obj


def outset(loop, amount):
    n = len(loop)
    out = []
    for i in range(n):
        x, z = loop[i]
        x0, z0 = loop[(i - 1) % n]
        x1, z1 = loop[(i + 1) % n]

        def outward(dx, dz):
            length = math.hypot(dx, dz) or 1.0
            dx, dz = dx / length, dz / length
            return (dz, -dx)

        n0 = outward(x - x0, z - z0)
        n1 = outward(x1 - x, z1 - z)
        nx, nz = n0[0] + n1[0], n0[1] + n1[1]
        length = math.hypot(nx, nz) or 1.0
        out.append((x + amount * nx / length, z + amount * nz / length))
    return out


def shift(loop, dx, dz):
    return [(x + dx, z + dz) for x, z in loop]


def chevron_gt(s):
    """Hollow > as one 6-gon. Blunt left ends, real hole, connected tip."""
    return [
        (-0.70 * s, 0.92 * s),
        (0.50 * s, 0.00),
        (-0.70 * s, -0.92 * s),
        (-0.70 * s, -0.42 * s),
        (0.16 * s, 0.00),
        (-0.70 * s, 0.42 * s),
    ]


def chevron_lt(s):
    return [(-x, z) for x, z in chevron_gt(s)]


def add_letter(ch, x, z, size, mat, font):
    curve = bpy.data.curves.new(f"txt_{ch}", "FONT")
    curve.body = ch
    curve.font = font
    curve.size = size
    curve.extrude = 0.028
    curve.bevel_depth = 0.004
    curve.bevel_resolution = 2
    curve.align_x = "CENTER"
    curve.align_y = "CENTER"
    obj = bpy.data.objects.new(f"L_{ch}", curve)
    link_object(obj)
    obj.location = (x, 0.0, z)
    obj.rotation_euler = Euler((math.radians(90), 0, 0), "XYZ")
    obj.data.materials.append(mat)
    return obj


def setup_world():
    world = bpy.data.worlds.new("IconWorld")
    bpy.context.scene.world = world
    world.use_nodes = True
    nt = world.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    bg.inputs["Color"].default_value = (0, 0, 0, 1)
    bg.inputs["Strength"].default_value = 0.0
    nt.links.new(bg.outputs["Background"], out.inputs["Surface"])


def setup_camera():
    cam_data = bpy.data.cameras.new("IconCam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = 2.88
    cam = bpy.data.objects.new("IconCam", cam_data)
    link_object(cam)
    cam.location = (0.0, -7.2, 0.04)
    cam.rotation_euler = Euler((math.radians(90), 0, 0), "XYZ")
    bpy.context.scene.camera = cam


def setup_lights():
    def area(name, loc, rot, color, energy, size):
        light = bpy.data.lights.new(name, "AREA")
        light.energy = energy
        light.color = color
        light.size = size
        obj = bpy.data.objects.new(name, light)
        link_object(obj)
        obj.location = loc
        obj.rotation_euler = Euler(rot, "XYZ")

    area("Key", (-2.4, -4.0, 2.6), (math.radians(62), 0, math.radians(-28)), (1.0, 0.93, 0.84), 210, 2.6)
    area("Fill", (0.15, -6.2, 0.15), (math.radians(90), 0, 0), (1.0, 1.0, 1.0), 56, 5.0)
    area("Rim", (3.2, -3.6, -0.3), (math.radians(78), 0, math.radians(42)), (1.0, 0.98, 1.0), 120, 2.2)
    area("OrangeKick", (-3.4, -3.2, 0.4), (math.radians(88), 0, math.radians(-55)), (1.0, 0.32, 0.0), 70, 1.8)


def setup_render(scene):
    scene.render.engine = "CYCLES"
    scene.render.resolution_x = 1024
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.film_transparent = True
    scene.render.filepath = RENDER_FG
    scene.cycles.samples = 128
    scene.cycles.use_denoising = True
    scene.cycles.device = "CPU"
    scene.display_settings.display_device = "sRGB"
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.view_settings.exposure = 0.0
    scene.view_settings.gamma = 1.0


def build_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    scene = bpy.context.scene
    setup_world()
    setup_camera()
    setup_lights()

    orange = mat_paint(
        "OrangeBody",
        ORANGE,
        metal=0.12,
        rough=0.24,
        emit_mix=0.18,
        emit_color=ORANGE_GLOW,
        emit_str=1.8,
    )
    white = mat_paint(
        "WhiteBody",
        WHITE,
        metal=0.0,
        rough=0.18,
        emit_mix=0.10,
        emit_color=WHITE,
        emit_str=1.2,
    )
    glow = mat_emit("OrangeGlow", ORANGE_GLOW, 1.35)
    glow_soft = mat_emit("OrangeHalo", (0.787, 0.155, 0.0), 0.55)
    white_glow = mat_emit("WhiteGlow", (1.0, 0.98, 0.95), 0.7)
    font = load_font()

    s = 0.74
    oz = -0.18
    ox_o = -0.38
    ox_w = 0.38
    gt = shift(chevron_gt(s), ox_o, oz)
    lt = shift(chevron_lt(s), ox_w, oz)

    prism("Chevron", gt, 0.22, orange, bevel_width=0.015)
    prism("WhiteLt", lt, 0.22, white, bevel_width=0.015)
    # Loop winds clockwise around the solid, so negative outset grows the rim.
    prism("ChevronGlow", outset(gt, -0.026), 0.06, glow, bevel_width=0.0)
    prism("ChevronHalo", outset(gt, -0.055), 0.04, glow_soft, bevel_width=0.0)
    prism("WhiteGlow", outset(lt, -0.022), 0.06, white_glow, bevel_width=0.0)

    add_letter("P", -0.34, 0.70, 0.38, orange, font)
    add_letter("D", 0.04, 0.70, 0.38, orange, font)
    add_letter("M", 0.42, 0.70, 0.38, white, font)

    setup_render(scene)
    bpy.ops.wm.save_as_mainfile(filepath=BLEND_OUT)
    bpy.ops.render.render(write_still=True)
    print("WROTE", RENDER_FG)


if __name__ == "__main__":
    try:
        build_scene()
    except Exception as exc:
        print("ICON_FAIL", exc, file=sys.stderr)
        raise
