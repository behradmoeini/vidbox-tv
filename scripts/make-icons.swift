// Draws the TV home tile and the launcher icon in the look the owner's other TV apps share:
// the app's name in white Helvetica Bold on a navy gradient (#041023 bottom-left to #084367
// top-right) over a cyan bar (#3DBEEC).
//
//   swift scripts/make-icons.swift            (run from the repo root)
import AppKit

func rgb(_ hex: Int) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}

func draw(_ name: String, width: Int, height: Int, fontSize: CGFloat, textY: CGFloat, barY: CGFloat, to path: String) {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let w = CGFloat(width), h = CGFloat(height)

    // Core Graphics has its origin at the bottom-left, so (0,0) -> (w,h) is bottom-left to top-right.
    let gradient = CGGradient(colorsSpace: space, colors: [rgb(0x041023), rgb(0x084367)] as CFArray,
                              locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 0), end: CGPoint(x: w, y: h), options: [])

    // The bar: 40% of the width, centred, `barY` of the height up from the bottom.
    let barH = max(2, (h * 0.017).rounded())
    ctx.setFillColor(rgb(0x3DBEEC))
    ctx.fill(CGRect(x: w * 0.30, y: h * barY - barH / 2, width: w * 0.40, height: barH))

    // The name, centred on `textY` of the height (from the bottom).
    let font = NSFont(name: "Helvetica-Bold", size: fontSize)!
    let text = NSAttributedString(string: name, attributes: [.font: font, .foregroundColor: NSColor.white])
    let line = CTLineCreateWithAttributedString(text)
    let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
    ctx.textPosition = CGPoint(x: (w - bounds.width) / 2 - bounds.minX,
                               y: h * textY - bounds.height / 2 - bounds.minY)
    CTLineDraw(line, ctx)

    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
    print("wrote \(path) (\(width)x\(height))")
}

draw("Watch", width: 640, height: 360, fontSize: 104, textY: 0.56, barY: 0.10, to: "app/res/drawable-xhdpi/banner.png")
// The home screen crops the icon to a circle, so the bar sits just under the name, well inside it
// (at the square's bottom edge it would land on the circle's rim).
draw("Watch", width: 192, height: 192, fontSize: 43, textY: 0.53, barY: 0.27, to: "app/res/mipmap-xxhdpi/icon.png")
