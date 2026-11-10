import AppKit
import SceneKit
import Metal

// Offline source-asset inspection, independent of Editor and its scene.
let input = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let scene = try SCNScene(url: input, options: [.checkConsistency: true])
let bounds = scene.rootNode.boundingBox
let lo = bounds.min
let hi = bounds.max
let center = SCNVector3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)
let radius = max(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)
scene.background.contents = NSColor(calibratedRed: 0.10, green: 0.13, blue: 0.17, alpha: 1)
let ambient = SCNNode()
ambient.light = SCNLight()
ambient.light!.type = .ambient
ambient.light!.intensity = 550
scene.rootNode.addChildNode(ambient)
let key = SCNNode()
key.light = SCNLight()
key.light!.type = .directional
key.light!.intensity = 850
key.eulerAngles = SCNVector3(-0.65, -0.45, 0)
scene.rootNode.addChildNode(key)
let camera = SCNNode()
camera.camera = SCNCamera()
camera.camera!.usesOrthographicProjection = true
camera.camera!.orthographicScale = Double(radius * 0.62)
camera.camera!.zNear = 0.001
camera.camera!.zFar = Double(radius * 15)
scene.rootNode.addChildNode(camera)
let renderer = SCNRenderer(device: MTLCreateSystemDefaultDevice(), options: nil)
renderer.scene = scene
renderer.pointOfView = camera
let views: [(String, SCNVector3)] = [
    ("plusZ", SCNVector3(0, 0, 1)), ("minusZ", SCNVector3(0, 0, -1)),
    ("plusX", SCNVector3(1, 0, 0)), ("minusX", SCNVector3(-1, 0, 0)),
    ("plusY", SCNVector3(0, 1, 0)), ("minusY", SCNVector3(0, -1, 0)),
    ("frontThreeQuarter", SCNVector3(0.8, 0.3, 1)),
    ("rearThreeQuarter", SCNVector3(-0.8, 0.3, -1)),
]
for (name, direction) in views {
    SCNTransaction.begin()
    SCNTransaction.disableActions = true
    camera.position = SCNVector3(center.x + direction.x * radius * 3,
        center.y + direction.y * radius * 3, center.z + direction.z * radius * 3)
    let viewUp = abs(direction.y) == 1
        ? SCNVector3(0, 0, -1) : SCNVector3(0, 1, 0)
    camera.look(at: center, up: viewUp, localFront: SCNVector3(0, 0, -1))
    SCNTransaction.commit()
    _ = renderer.snapshot(atTime: 0, with: NSSize(width: 64, height: 64),
        antialiasingMode: .none)
    let image = renderer.snapshot(atTime: 0, with: NSSize(width: 900, height: 900),
        antialiasingMode: .multisampling4X)
    let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
    try bitmap.representation(using: .png, properties: [:])!.write(
        to: output.appendingPathComponent("\(name).png"))
}
print("Rendered \(input.lastPathComponent): bounds \(lo) .. \(hi)")
