package com.carlink.ocbm

import com.carlink.ocbm.OemIcon.Image
import java.util.Base64

/** Adds this app's return-to-settings tile to the shared GM `/info` binary plist. */
internal object CarPlayInfoCustomizer {
    /** Keep the wire display and both touchscreen contacts in the same panel coordinate space. */
    fun withGeometry(source: ByteArray, geometry: com.carlink.util.PanelGeometry): ByteArray {
        require(geometry.width in 1..32767 && geometry.height in 1..32767 && geometry.dpi > 0)
        val info = BinaryPlist.parseDict(source)?.toMutableMap() ?: error("invalid /info plist")
        val displays = (info["displays"] as List<*>).map { (it as Map<*, *>).entries.associate { e -> e.key as String to e.value }.toMutableMap() }
        val main = displays.first { (it["type"] as? Number)?.toInt() == 110 }
        main["widthPixels"] = geometry.width
        main["heightPixels"] = geometry.height
        main["maxFPS"] = geometry.maxFps
        main["widthPhysical"] = (geometry.width * 25.4 / geometry.dpi).toInt()
        main["heightPhysical"] = (geometry.height * 25.4 / geometry.dpi).toInt()
        val areas = (main["viewAreas"] as List<*>).map { (it as Map<*, *>).entries.associate { e -> e.key as String to e.value }.toMutableMap() }
        val full = areas.first()
        full["widthPixels"] = geometry.width
        full["heightPixels"] = geometry.height
        full["safeArea"] = linkedMapOf(
            "originXPixels" to geometry.safe.x, "originYPixels" to geometry.safe.y,
            "widthPixels" to geometry.safe.width, "heightPixels" to geometry.safe.height,
            "drawUIOutsideSafeArea" to geometry.drawUiOutsideSafeArea,
        )
        main["viewAreas"] = areas
        info["displays"] = displays
        info["hidDevices"] = (info["hidDevices"] as List<*>).map { value ->
            val device = (value as Map<*, *>).entries.associate { e -> e.key as String to e.value }.toMutableMap()
            if (device["uuid"].toString() == "1") {
                val descriptor = (device["hidDescriptor"] as ByteArray).copyOf()
                // This bundled descriptor declares X and Y identically for each of two contacts.
                // Match the complete usage/minimum/maximum item sequence, not arbitrary bytes.
                for ((usage, maximum) in listOf(0x30 to geometry.width, 0x31 to geometry.height)) {
                    var count = 0
                    for (i in 0 until descriptor.size - 6) {
                        if (descriptor[i] == 0x09.toByte() && descriptor[i+1] == usage.toByte() &&
                            descriptor[i+2] == 0x15.toByte() && descriptor[i+3] == 0.toByte() && descriptor[i+4] == 0x26.toByte()) {
                            descriptor[i+5] = maximum.toByte()
                            descriptor[i+6] = (maximum ushr 8).toByte()
                            count++
                        }
                    }
                    check(count == 2) { "unexpected touchscreen descriptor axis=$usage contacts=$count" }
                }
                device["hidDescriptor"] = descriptor
            }
            device
        }
        return BinaryPlist.encodeDict(info)
    }

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
