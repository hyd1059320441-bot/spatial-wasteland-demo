import spatial_editor as se
import json

se.set_output("start: fit and separate three accepted gun prototypes")
nodes = []
for root in se.scene.roots():
    nodes.append(root)
    nodes.extend(root.children())
data = [
    ("arc_smg", 0.65 / 0.7484041452407837, -1.2, -0.0003010481595993042, -0.0002072639763355255),
    ("rail_sniper", 0.9 / 0.8039760589599609, 0.0, -0.00022841989994049072, -0.0006692800670862198),
    ("plasma_cannon", 0.75 / 0.3675000071525574, 1.2, -0.00009867548942565918, -0.00015978515148162842),
]
with se.scene.batch_edit("V12 accepted arsenal bounds fit", refresh_ui=False):
    for name, scale, x, cx, cz in data:
        matches = [node for node in nodes if node.path.strip("/") == "Root/" + name]
        if len(matches) != 1:
            raise Exception("Missing or duplicated accepted gun: " + name)
        node = matches[0]
        node.set_scale(scale, scale, scale)
        node.set_position(x - cx * scale, 0.0, -cz * scale)
        print(name, json.dumps(node.info()))
if not se.scene.save_current():
    raise Exception("Failed to save current V12 scene")
se.set_output("done: isolated V12 scene saved; authored rotations and textures preserved")
