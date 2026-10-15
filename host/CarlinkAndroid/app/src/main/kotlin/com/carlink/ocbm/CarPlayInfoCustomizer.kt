package com.carlink.ocbm

import com.carlink.ocbm.OemIcon.Image
import java.util.Base64

/** Adds this app's return-to-settings tile to the shared GM `/info` binary plist. */
internal object CarPlayInfoCustomizer {
    fun withReturnIcon(
        source: ByteArray,
        images: List<Image>,
        label: String,
    ): ByteArray {
        require(images.size == 3 && images.map { it.width to it.height }.toSet() == setOf(120 to 120, 180 to 180, 256 to 256)) {
            "CarPlay OEM icon must include 120, 180 and 256 pixel images"
        }
        require(label.isNotBlank()) { "CarPlay OEM icon label must not be blank" }

        val info = BinaryPlist.parseDict(source)?.toMutableMap()
            ?: error("shared /info asset is not a readable binary plist")
        info["oemIcons"] = images.map { image ->
            linkedMapOf<String, Any?>(
                "heightPixels" to image.height,
                "imageData" to Base64.getDecoder().decode(image.base64),
                "prerendered" to true,
                "widthPixels" to image.width,
            )
        }
        info["oemIconVisible"] = true
        info["oemIconLabel"] = label

        val encoded = BinaryPlist.encodeDict(info)
        val verify = BinaryPlist.parseDictOrThrow(encoded)
            ?: error("transformed /info plist could not be parsed after encoding")
        check(verify["oemIconVisible"] == true) {
            "transformed /info lost oemIconVisible (decoded as ${verify["oemIconVisible"]})"
        }
        check(verify["oemIconLabel"] == label) {
            "transformed /info lost oemIconLabel (decoded as ${verify["oemIconLabel"]})"
        }
        check((verify["oemIcons"] as? List<*>)?.size == images.size) {
            "transformed /info lost icon images (decoded count ${(verify["oemIcons"] as? List<*>)?.size})"
        }
        return encoded
    }
}
