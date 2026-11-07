import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Generates adaptive launcher icon resources from the repository-root icon.png.
 *
 * Policy ("Fit"): the whole image is centre-cropped to a square (only if it is not
 * square already), scaled into the 66dp adaptive-icon safe zone and placed on a
 * solid background taken from the image's own edge colour. No white or black
 * border is added. Output goes to the build directory, never into the repository.
 *
 * minSdk is 28, so adaptive icons are always available and no legacy PNG
 * launcher icons are needed.
 */
abstract class GenIcons : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun generate() {
        System.setProperty("java.awt.headless", "true")

        val input = ImageIO.read(source.get().asFile)
            ?: throw GradleException("icon.png could not be read as an image. Use a valid PNG file.")

        val out = outDir.get().asFile
        out.deleteRecursively()

        // Centre-crop to a square and normalise to ARGB in one step.
        val side = minOf(input.width, input.height)
        val square = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        square.createGraphics().also { g ->
            g.drawImage(input, -(input.width - side) / 2, -(input.height - side) / 2, null)
            g.dispose()
        }

        // Background colour: alpha-weighted average of the image's outer edge ring.
        val ring = maxOf(1, side / 50)
        var weight = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (x >= ring && y >= ring && x < side - ring && y < side - ring) continue
                val p = square.getRGB(x, y)
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue
                weight += a
                sumR += ((p shr 16) and 0xFF) * a
                sumG += ((p shr 8) and 0xFF) * a
                sumB += (p and 0xFF) * a
            }
        }
        val bgHex = if (weight < 1.0) {
            "#171717" // fully transparent edges: neutral dark fallback
        } else {
            String.format(
                "#%02X%02X%02X",
                (sumR / weight).toInt().coerceIn(0, 255),
                (sumG / weight).toInt().coerceIn(0, 255),
                (sumB / weight).toInt().coerceIn(0, 255)
            )
        }

        // Foreground layers: 108dp canvas, image scaled to the 66dp safe zone.
        val densities = listOf(
            "mdpi" to 100, "hdpi" to 150, "xhdpi" to 200, "xxhdpi" to 300, "xxxhdpi" to 400
        )
        for ((name, pct) in densities) {
            val canvasPx = 108 * pct / 100
            val iconPx = 66 * pct / 100
            val scaled = scaleTo(square, iconPx)
            val canvas = BufferedImage(canvasPx, canvasPx, BufferedImage.TYPE_INT_ARGB)
            canvas.createGraphics().also { g ->
                g.drawImage(scaled, (canvasPx - iconPx) / 2, (canvasPx - iconPx) / 2, null)
                g.dispose()
            }
            val dir = File(out, "mipmap-$name").also { it.mkdirs() }
            ImageIO.write(canvas, "png", File(dir, "ic_launcher_foreground.png"))
        }

        File(out, "mipmap-anydpi").mkdirs()
        File(out, "mipmap-anydpi/ic_launcher.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/flit_icon_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
        )

        File(out, "values").mkdirs()
        File(out, "values/flit_icon_bg.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="flit_icon_bg">$bgHex</color>
</resources>
"""
        )
    }

    /** Halves repeatedly while possible, then a final resize: avoids aliasing on big downscales. */
    private fun scaleTo(img: BufferedImage, target: Int): BufferedImage {
        var cur = img
        while (cur.width / 2 >= target) cur = resize(cur, cur.width / 2)
        return if (cur.width == target) cur else resize(cur, target)
    }

    private fun resize(img: BufferedImage, size: Int): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(img, 0, 0, size, size, null)
        g.dispose()
        return out
    }
}

val generateLauncherIcons = tasks.register<GenIcons>("generateLauncherIcons") {
    source.set(rootProject.layout.projectDirectory.file("icon.png"))
}

android {
    namespace = "app.flit"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.flit"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Only the two supported languages are packaged (also strips library locales).
    androidResources {
        localeFilters += listOf("en", "es")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Development stage: standard debug signing, no keystore, no secrets.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    // Smaller APK: no dependency-metadata block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Wire generated launcher resources into every variant.
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateLauncherIcons, GenIcons::outDir)
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Native "Turn on Location" popup (SettingsClient / ResolvableApiException).
    // Used only on Android 9-12L and only when Google Play services is available.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Built-in video and audio player (pure Java/Kotlin, no native code, Apache 2.0).
    implementation("androidx.media3:media3-exoplayer:1.8.0")

    // Markdown to HTML (shown in the viewer's WebView). Pure Java, BSD-2.
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.commonmark:commonmark-ext-footnotes:0.24.0")

    // SVG images (pure Java, Apache 2.0).
    implementation("com.caverock:androidsvg-aar:1.4")

    // Archive extraction: 7z (pure Java) and RAR (pure Java). ZIP uses java.util.zip.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
}