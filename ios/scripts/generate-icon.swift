import AppKit
import CoreGraphics
import Foundation

func srgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1.0) -> CGColor {
    CGColor(srgbRed: r / 255.0, green: g / 255.0, blue: b / 255.0, alpha: a)
}

let size = 1024
let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(
    data: nil,
    width: size,
    height: size,
    bitsPerComponent: 8,
    bytesPerRow: 0,
    space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
)!

let fullRect = CGRect(x: 0, y: 0, width: size, height: size)
let scale = CGFloat(size) / 108.0

func svgX(_ x: CGFloat) -> CGFloat { x * scale }
func svgY(_ y: CGFloat) -> CGFloat { (108.0 - y) * scale }

// Studio dark gradient backdrop
let bgGrad = CGGradient(
    colorsSpace: colorSpace,
    colors: [srgb(38, 40, 50), srgb(26, 27, 34), srgb(18, 19, 24)] as CFArray,
    locations: [0.0, 0.55, 1.0]
)!
let bgCenter = CGPoint(x: fullRect.midX, y: fullRect.midY + fullRect.height * 0.12)
ctx.drawRadialGradient(
    bgGrad,
    startCenter: bgCenter,
    startRadius: 0,
    endCenter: bgCenter,
    endRadius: fullRect.width * 0.85,
    options: [.drawsAfterEndLocation]
)

// Subtle ambient mint + lilac aura
let mintGlow = CGGradient(
    colorsSpace: colorSpace,
    colors: [srgb(125, 228, 182, 0.14), srgb(125, 228, 182, 0.0)] as CFArray,
    locations: [0.0, 1.0]
)!
let mintCenter = CGPoint(x: svgX(47.0), y: svgY(58.0))
ctx.drawRadialGradient(mintGlow, startCenter: mintCenter, startRadius: 0, endCenter: mintCenter, endRadius: 44.0 * scale, options: [])

let lilacGlow = CGGradient(
    colorsSpace: colorSpace,
    colors: [srgb(175, 164, 255, 0.15), srgb(175, 164, 255, 0.0)] as CFArray,
    locations: [0.0, 1.0]
)!
let lilacCenter = CGPoint(x: svgX(64.0), y: svgY(37.0))
ctx.drawRadialGradient(lilacGlow, startCenter: lilacCenter, startRadius: 0, endCenter: lilacCenter, endRadius: 34.0 * scale, options: [])

// Canonical LiVoice mark
let mint = srgb(125, 228, 182)
let lilac = srgb(175, 164, 255)

ctx.setLineCap(.round)
ctx.setLineJoin(.round)

// 'L' stroke
let lPath = CGMutablePath()
lPath.move(to: CGPoint(x: svgX(33.5), y: svgY(39.5)))
lPath.addLine(to: CGPoint(x: svgX(33.5), y: svgY(65.5)))
lPath.addArc(
    center: CGPoint(x: svgX(41.0), y: svgY(65.5)),
    radius: 7.5 * scale,
    startAngle: .pi,
    endAngle: 1.5 * .pi,
    clockwise: false
)
lPath.addLine(to: CGPoint(x: svgX(46.0), y: svgY(73.0)))
ctx.addPath(lPath)
ctx.setStrokeColor(mint)
ctx.setLineWidth(8.4 * scale)
ctx.strokePath()

// 'i' dot
let dotCenter = CGPoint(x: svgX(58.5), y: svgY(41.5))
let dotRadius = 4.6 * scale
ctx.setFillColor(lilac)
ctx.fillEllipse(in: CGRect(x: dotCenter.x - dotRadius, y: dotCenter.y - dotRadius, width: dotRadius * 2, height: dotRadius * 2))

// 'i' stem
let iStem = CGMutablePath()
iStem.move(to: CGPoint(x: svgX(58.5), y: svgY(53.5)))
iStem.addLine(to: CGPoint(x: svgX(58.5), y: svgY(73.0)))
ctx.addPath(iStem)
ctx.setStrokeColor(mint)
ctx.setLineWidth(8.2 * scale)
ctx.strokePath()

// Inner broadcast arc (-78 deg to +4 deg in SVG -> +78 deg to -4 deg in CG)
let arc1 = CGMutablePath()
arc1.addArc(center: dotCenter, radius: 11.0 * scale, startAngle: -4.0 * .pi / 180.0, endAngle: 78.0 * .pi / 180.0, clockwise: false)
ctx.addPath(arc1)
ctx.setStrokeColor(lilac)
ctx.setLineWidth(4.8 * scale)
ctx.strokePath()

// Outer broadcast arc (-68 deg to +2 deg in SVG -> +68 deg to -2 deg in CG)
let arc2 = CGMutablePath()
arc2.addArc(center: dotCenter, radius: 17.5 * scale, startAngle: -2.0 * .pi / 180.0, endAngle: 68.0 * .pi / 180.0, clockwise: false)
ctx.addPath(arc2)
ctx.setStrokeColor(srgb(125, 228, 182, 0.90))
ctx.setLineWidth(4.3 * scale)
ctx.strokePath()

let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
let png = rep.representation(using: .png, properties: [:])!
let scriptURL = URL(fileURLWithPath: #filePath)
let iosRoot = scriptURL.deletingLastPathComponent().deletingLastPathComponent()
let outURL = iosRoot.appendingPathComponent("App/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try png.write(to: outURL)
print("Generated \(outURL.path) (\(size)x\(size) RGB)")
