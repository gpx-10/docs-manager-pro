import Foundation
import ImageIO
// exif6 <in.jpg> <out.jpg>: re-saves a JPEG with EXIF orientation 6 ("turn 90° clockwise to view").
let a = CommandLine.arguments
let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: a[1]) as CFURL, nil)!
let img = CGImageSourceCreateImageAtIndex(src, 0, nil)!
let dst = CGImageDestinationCreateWithURL(URL(fileURLWithPath: a[2]) as CFURL, "public.jpeg" as CFString, 1, nil)!
CGImageDestinationAddImage(dst, img, [kCGImagePropertyOrientation: 6, kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
CGImageDestinationFinalize(dst)
