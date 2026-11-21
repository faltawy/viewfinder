package app.viewfinder.ptp

import app.viewfinder.ptp.FakePtpIpCamera.FakeObject
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Runs a pretend D5600 full of generated landscapes, for trying the app without the real camera:
 * `./gradlew :ptp:runFakeCamera`, then connect the emulator to 10.0.2.2.
 */
fun main() {
    val photos = mutableListOf<FakeObject>()
    var handle = 0x1000L
    val now = LocalDateTime.now()
    // Three outings: today, yesterday and a week ago, a dozen frames each, some shot RAW+JPEG.
    listOf(0L, 1L, 7L).forEach { daysAgo ->
        repeat(12) { i ->
            val taken = now.minusDays(daysAgo).withHour(9 + i / 2).withMinute((i * 7) % 60)
            val seed = (daysAgo * 100 + i).toInt()
            val n = photos.size + 1
            photos += landscape(++handle, "DSC_%04d.JPG".format(n), seed, taken)
            if (i % 4 == 0) {
                val jpg = photos.last()
                photos += jpg.copy(handle = ++handle, filename = "DSC_%04d.NEF".format(n), content = Random(seed).nextBytes(2_400_000), format = Format.Undefined)
            }
        }
    }
    photos.sortBy { it.captureDate }

    lateinit var cam: FakePtpIpCamera
    cam = FakePtpIpCamera(
        photos,
        makeShot = { h -> landscape(h, "DSC_%04d.JPG".format(h - 0x1000), h.toInt(), LocalDateTime.now()) },
        liveFrame = { liveFrame(cam) },
        // ./gradlew :ptp:runFakeCamera -Pkbps=300 slows transfers down to test progress and cancelling.
        bytesPerSecond = System.getProperty("fake.kbps")?.toLongOrNull()?.times(1000),
    ).start(port = 15740)
    println("Fake D5600 with ${photos.size} files on 127.0.0.1:15740 (emulator: 10.0.2.2). Ctrl-C to stop.")
    Thread.currentThread().join()
}

private val ptpDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val exifDate = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

private val liveScene by lazy { render(960, 640, 5) }

/** The live view: the same scene drifting slowly, brighter or darker with exposure compensation. */
private fun liveFrame(cam: FakePtpIpCamera): ByteArray {
    val t = (System.currentTimeMillis() % 20_000) / 20_000.0
    val ev = ((cam.props[0x5010]?.current as? PropValue.Num)?.value ?: 0) / 1000.0
    val img = BufferedImage(640, 427, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    val dx = (Math.sin(t * Math.PI * 2) * 120).toInt()
    g.drawImage(liveScene, -160 + dx, -100, 960, 640, null)
    val shade = (ev * 40).toInt().coerceIn(-160, 160)
    g.color = if (shade >= 0) Color(255, 255, 255, shade) else Color(0, 0, 0, -shade)
    g.fillRect(0, 0, 640, 427)
    g.dispose()
    Thread.sleep(60) // roughly the pace of a real camera over Wi-Fi
    return jpeg(img, 0.75f)
}

private fun landscape(handle: Long, name: String, seed: Int, taken: LocalDateTime): FakeObject {
    val full = render(3000, 2000, seed)
    // Settings vary like a real outing; every third frame has a location, as if a phone shared its GPS.
    val exif = FakeExif(
        model = "NIKON D5600",
        focalMm = listOf(18, 24, 35, 55)[Math.floorMod(seed, 4)],
        fNumber = listOf(3.5, 4.0, 5.6, 8.0)[Math.floorMod(seed / 2, 4)],
        exposureDenominator = listOf(60, 125, 250, 500, 1000)[Math.floorMod(seed, 5)],
        iso = listOf(100, 200, 400, 800)[Math.floorMod(seed / 3, 4)],
        taken = taken.format(exifDate),
        location = if (Math.floorMod(seed, 3) == 0) 46.5775 to 7.9844 else null,
    )
    return FakeObject(
        handle = handle,
        filename = name,
        content = withExif(jpeg(full, 0.9f), exif),
        thumb = jpeg(scale(full, 160, 107), 0.8f),
        preview = withExif(jpeg(scale(full, 1200, 800), 0.85f), exif.copy(location = null)),
        captureDate = taken.format(ptpDate),
        width = 3000,
        height = 2000,
    )
}

private fun render(w: Int, h: Int, seed: Int): BufferedImage {
    val rnd = Random(seed)
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val hue = rnd.nextFloat()
    val skyTop = Color.getHSBColor(hue, 0.55f, 0.35f + rnd.nextFloat() * 0.3f)
    val skyLow = Color.getHSBColor((hue + 0.08f) % 1f, 0.45f, 0.95f)
    g.paint = GradientPaint(0f, 0f, skyTop, 0f, h * 0.7f, skyLow)
    g.fillRect(0, 0, w, h)
    g.color = Color(255, 240, 200, 230)
    val sunR = 90 + rnd.nextInt(120)
    g.fillOval(rnd.nextInt(w - 2 * sunR), (h * 0.15f + rnd.nextFloat() * h * 0.3f).toInt(), sunR * 2, sunR * 2)
    for (layer in 0 until 4) {
        val base = h * (0.45f + layer * 0.13f)
        val path = Path2D.Float().apply {
            moveTo(0f, h.toFloat())
            var x = 0f
            lineTo(0f, base)
            while (x < w) {
                x += 120 + rnd.nextInt(260)
                lineTo(x, base - rnd.nextInt((260 - layer * 50).coerceAtLeast(40)))
            }
            lineTo(w.toFloat(), h.toFloat())
            closePath()
        }
        val shade = 0.55f - layer * 0.12f
        g.color = Color.getHSBColor((hue + 0.5f) % 1f, 0.35f, shade.coerceAtLeast(0.08f))
        g.fill(path)
    }
    g.color = Color(255, 255, 255, 40)
    g.stroke = BasicStroke(3f)
    g.drawRect(40, 40, w - 80, h - 80)
    g.dispose()
    return img
}

private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return out
}

private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    ImageIO.createImageOutputStream(out).use { ios ->
        writer.output = ios
        writer.write(null, javax.imageio.IIOImage(img, null, null), params)
    }
    writer.dispose()
    return out.toByteArray()
}
