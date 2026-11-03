// Render WoVoice's existing vector mark at the App Store's required size.
import AppKit
import Foundation

let size = 1024
let colorSpace = CGColorSpaceCreateDeviceRGB()
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                        space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
context.scaleBy(x: Double(size)/108, y: Double(size)/108)
context.setFillColor(NSColor(srgbRed: 37/255, green: 37/255, blue: 40/255, alpha: 1).cgColor)
context.fill(CGRect(x: 0, y: 0, width: 108, height: 108))
context.setFillColor(NSColor(srgbRed: 125/255, green: 228/255, blue: 182/255, alpha: 1).cgColor)
context.fillEllipse(in: CGRect(x: 20, y: 20, width: 68, height: 68))
context.setFillColor(NSColor(srgbRed: 28/255, green: 30/255, blue: 31/255, alpha: 1).cgColor)
for (x, y, height) in [(34.0,47.0,14.0), (42.5,39.0,30.0), (51.0,31.0,46.0), (59.5,39.0,30.0), (68.0,47.0,14.0)] {
    context.addPath(CGPath(roundedRect: CGRect(x: x, y: y, width: 7, height: height), cornerWidth: 3.5, cornerHeight: 3.5, transform: nil))
    context.fillPath()
}
let image = NSBitmapImageRep(cgImage: context.makeImage()!)
let output = URL(fileURLWithPath: CommandLine.arguments[1])
try image.representation(using: .png, properties: [:])!.write(to: output)
