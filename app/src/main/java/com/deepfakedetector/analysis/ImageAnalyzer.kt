package com.deepfakedetector.analysis

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import com.deepfakedetector.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class ImageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiOrNotService: AiOrNotService
) {

    suspend fun analyze(uri: Uri): ImageAnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val imageId   = uri.toString().hashCode().toString()
        val imageName = uri.lastPathSegment ?: "image"

        val bitmap = loadBitmap(uri) ?: return@withContext errorResult(imageId, imageName)
        val metadata = extractMetadata(uri, bitmap)

        val pixelScore    = analyzePixels(bitmap)
        val statsScore    = analyzeStatistics(bitmap)
        val artifactScore = analyzeArtifacts(bitmap)
        val metaScore     = analyzeMetadata(metadata)

        val aiOrNotResult = try { aiOrNotService.analyze(bitmap) } catch (_: Exception) { null }

        val moduleScores = ImageModuleScores(
            pixelAnalysis=pixelScore, statistics=statsScore,
            artifactDetection=artifactScore, metadataAnalysis=metaScore
        )
        val fused = fuseScores(moduleScores, aiOrNotResult)

        ImageAnalysisResult(
            imageId=imageId, imagePath=uri.toString(), imageName=imageName,
            overallScore=fused.first, confidenceScore=fused.second,
            reliabilityLevel=AnalysisResult.computeReliabilityLevel(fused.second),
            verdictLevel=AnalysisResult.computeVerdictLevel(fused.first),
            moduleScores=moduleScores,
            explanation=buildExplanation(fused.first, moduleScores, aiOrNotResult),
            keyFindings=buildKeyFindings(moduleScores, aiOrNotResult),
            warnings=buildWarnings(moduleScores, metadata, aiOrNotResult),
            processingTimeMs=System.currentTimeMillis()-startTime, metadata=metadata
        )
    }

    private fun analyzePixels(bitmap: Bitmap): ModuleScore {
        val anomalies = mutableListOf<Anomaly>()
        val details   = mutableListOf<String>()
        var scoreSum  = 0f; var weightSum = 0f

        val noiseScore = analyzeNaturalNoise(bitmap)
        scoreSum += noiseScore*0.35f; weightSum += 0.35f
        if (noiseScore > 0.6f) {
            anomalies.add(Anomaly("noise_absence", AnomalySeverity.HIGH, "Absence de bruit capteur naturel", "σ bruit = %.3f".format(1f-noiseScore)))
            details.add("Bruit capteur : anormalement lisse (${(noiseScore*100).toInt()}%)")
        } else details.add("Bruit capteur : naturel (${(noiseScore*100).toInt()}%)")

        val fftScore = analyzeFFTPatterns(bitmap)
        scoreSum += fftScore*0.30f; weightSum += 0.30f
        if (fftScore > 0.55f) {
            anomalies.add(Anomaly("fft_periodicity", AnomalySeverity.MEDIUM, "Motifs périodiques détectés", "Énergie spectrale concentrée"))
            details.add("FFT : motifs suspects (${(fftScore*100).toInt()}%)")
        } else details.add("FFT : naturelle")

        val smoothScore = detectOverSmoothing(bitmap)
        scoreSum += smoothScore*0.20f; weightSum += 0.20f
        if (smoothScore > 0.6f) {
            anomalies.add(Anomaly("over_smoothing", AnomalySeverity.MEDIUM, "Sur-lissage artificiel", "Gradient moyen trop faible"))
            details.add("Lissage : artificiel (${(smoothScore*100).toInt()}%)")
        }

        val edgeScore = analyzeEdgeCoherence(bitmap)
        scoreSum += edgeScore*0.15f; weightSum += 0.15f
        if (edgeScore > 0.65f)
            anomalies.add(Anomaly("edge_incoherence", AnomalySeverity.LOW, "Incohérences aux contours", "Bords nets/flous alternés"))

        val finalScore = if (weightSum > 0) scoreSum/weightSum else 0f
        return ModuleScore(finalScore.coerceIn(0f,1f), if (anomalies.size>=2) 0.82f else 0.65f, details, anomalies, 0L)
    }

    private fun analyzeNaturalNoise(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        val pixels = IntArray(128*128); small.getPixels(pixels, 0, 128, 0, 0, 128, 128)
        var sum = 0.0; var count = 0
        for (y in 1 until 127) for (x in 1 until 127) {
            var bs = 0.0; var bsq = 0.0
            for (dy in -1..1) for (dx in -1..1) { val v = grayValue(pixels[(y+dy)*128+(x+dx)]).toDouble(); bs+=v; bsq+=v*v }
            val m = bs/9; sum += bsq/9-m*m; count++
        }
        val avg = if (count>0) sum/count else 0.0
        return when { avg<5.0->0.85f; avg<15.0->0.65f; avg<40.0->0.35f; else->0.20f }
    }

    private fun analyzeFFTPatterns(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64*64); small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        var hP = 0; var vP = 0
        for (y in 0 until 64) { var p=0; for (x in 1 until 63) { val d=grayValue(pixels[y*64+x])-grayValue(pixels[y*64+x-1]); if(d>0&&p<0||d<0&&p>0) hP++; p=d } }
        for (x in 0 until 64) { var p=0; for (y in 1 until 63) { val d=grayValue(pixels[y*64+x])-grayValue(pixels[(y-1)*64+x]); if(d>0&&p<0||d<0&&p>0) vP++; p=d } }
        return ((hP.toFloat()/(64*62)+vP.toFloat()/(64*62))/2f).coerceIn(0f,1f)
    }

    private fun detectOverSmoothing(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        val pixels = IntArray(96*96); small.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        var gs = 0.0; var count = 0
        for (y in 0 until 95) for (x in 0 until 95) { val gx=abs(grayValue(pixels[y*96+x+1])-grayValue(pixels[y*96+x])); val gy=abs(grayValue(pixels[(y+1)*96+x])-grayValue(pixels[y*96+x])); gs+=sqrt((gx*gx+gy*gy).toDouble()); count++ }
        val avg = if (count>0) gs/count else 0.0
        return when { avg<3.0->0.90f; avg<8.0->0.70f; avg<15.0->0.45f; avg<25.0->0.30f; else->0.15f }
    }

    private fun analyzeEdgeCoherence(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
        val pixels = IntArray(80*80); small.getPixels(pixels, 0, 80, 0, 0, 80, 80)
        var sharp=0; var blurry=0
        for (y in 1 until 79) for (x in 1 until 79) { val g=abs(grayValue(pixels[y*80+x+1])-grayValue(pixels[y*80+x]))+abs(grayValue(pixels[(y+1)*80+x])-grayValue(pixels[y*80+x])); if(g>30) sharp++ else if(g>5) blurry++ }
        val total=sharp+blurry; if(total==0) return 0.3f
        return if(sharp.toFloat()/total<0.15f) 0.75f else 0.3f
    }

    private fun analyzeStatistics(bitmap: Bitmap): ModuleScore {
        val anomalies = mutableListOf<Anomaly>(); val details = mutableListOf<String>()
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64*64); small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        val histogram = IntArray(256); pixels.forEach { histogram[grayValue(it)]++ }
        val total = pixels.size.toFloat()
        var entropy = 0.0; histogram.forEach { c -> if(c>0) { val p=c/total; entropy-=p*ln(p) } }
        val ne = (entropy/ln(256.0)).toFloat()
        val es = when { ne<0.55f->0.80f; ne<0.70f->0.55f; ne<0.85f->0.25f; else->0.15f }
        if (es>0.6f) anomalies.add(Anomaly("low_entropy", AnomalySeverity.MEDIUM, "Entropie anormalement basse", "Entropie: ${"%.3f".format(ne)}"))
        details.add("Entropie : ${"%.2f".format(ne)}")
        var rSq=0L; var gSq=0L; var bSq=0L; var rS=0L; var gS=0L; var bS=0L
        pixels.forEach { px -> val r=Color.red(px); val g=Color.green(px); val b=Color.blue(px); rS+=r; gS+=g; bS+=b; rSq+=r*r; gSq+=g*g; bSq+=b*b }
        val n=pixels.size.toDouble()
        val acv = (((rSq/n-(rS/n).pow(2))+(gSq/n-(gS/n).pow(2))+(bSq/n-(bS/n).pow(2)))/3).toFloat()
        val cs = when { acv<200f->0.75f; acv<500f->0.45f; else->0.20f }
        if (cs>0.6f) anomalies.add(Anomaly("uniform_color", AnomalySeverity.LOW, "Distribution couleurs uniforme", "Variance: ${"%.1f".format(acv)}"))
        return ModuleScore((es*0.6f+cs*0.4f).coerceIn(0f,1f), if(anomalies.isNotEmpty()) 0.72f else 0.60f, details, anomalies)
    }

    private fun analyzeArtifacts(bitmap: Bitmap): ModuleScore {
        val anomalies = mutableListOf<Anomaly>(); val details = mutableListOf<String>()
        val rs = detectTextureRepetition(bitmap)
        if (rs>0.6f) { anomalies.add(Anomaly("texture_repetition", AnomalySeverity.HIGH, "Répétition de motifs", "Blocs similaires")); details.add("Répétition : suspecte (${(rs*100).toInt()}%)") } else details.add("Répétition : non détectée")
        val hs = detectCompositionHalo(bitmap)
        if (hs>0.55f) { anomalies.add(Anomaly("composition_halo", AnomalySeverity.MEDIUM, "Halo de composition", "Transition brusque centre/bords")); details.add("Halo : détecté") }
        val ss = detectExcessiveSymmetry(bitmap)
        if (ss>0.85f) { anomalies.add(Anomaly("excessive_symmetry", AnomalySeverity.MEDIUM, "Symétrie excessive", "Symétrie: ${"%.2f".format(ss)}")); details.add("Symétrie : excessive") } else details.add("Symétrie : naturelle")
        val st = detectImpossibleStructures(bitmap)
        if (st>0.5f) anomalies.add(Anomaly("impossible_structure", AnomalySeverity.LOW, "Incohérences structurelles", "Perspectives anormales"))
        val finalScore = floatArrayOf(rs,hs,ss,st).toList().zip(listOf(0.40f,0.25f,0.20f,0.15f)).sumOf { (s,w) -> (s*w).toDouble() }.toFloat()
        return ModuleScore(finalScore.coerceIn(0f,1f), if(anomalies.size>=2) 0.78f else 0.60f, details, anomalies)
    }

    private fun detectTextureRepetition(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32*32); small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        val q1=histogramOf(pixels,0,0,16,16,32); val q2=histogramOf(pixels,16,0,32,16,32)
        val q3=histogramOf(pixels,0,16,16,32,32); val q4=histogramOf(pixels,16,16,32,32,32)
        val avg=(histogramSimilarity(q1,q2)+histogramSimilarity(q3,q4)+histogramSimilarity(q1,q3))/3f
        return when { avg>0.92f->0.85f; avg>0.82f->0.60f; avg>0.70f->0.35f; else->0.15f }
    }

    private fun histogramOf(pixels: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, width: Int): FloatArray {
        val hist=FloatArray(32); var count=0
        for (y in y0 until y1) for (x in x0 until x1) { hist[grayValue(pixels[y*width+x])/8]+=1f; count++ }
        if (count>0) for (i in hist.indices) hist[i]/=count.toFloat()
        return hist
    }

    private fun histogramSimilarity(h1: FloatArray, h2: FloatArray): Float { var s=0f; for(i in h1.indices) s+=min(h1[i],h2[i]); return s }

    private fun detectCompositionHalo(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64*64); small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        val c=sharpnessOf(pixels,24,24,40,40,64)
        val e=(sharpnessOf(pixels,0,0,10,64,64)+sharpnessOf(pixels,54,0,64,64,64)+sharpnessOf(pixels,0,0,64,10,64)+sharpnessOf(pixels,0,54,64,64,64))/4f
        val r=if(e>0) c/e else 1f
        return when { r>5f->0.80f; r>3f->0.60f; r>2f->0.40f; else->0.20f }
    }

    private fun sharpnessOf(pixels: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, width: Int): Float {
        var sum=0f; var count=0
        for (y in y0 until y1-1) for (x in x0 until x1-1) { sum+=abs(grayValue(pixels[y*width+x+1])-grayValue(pixels[y*width+x]))+abs(grayValue(pixels[(y+1)*width+x])-grayValue(pixels[y*width+x])); count++ }
        return if(count>0) sum/count else 0f
    }

    private fun detectExcessiveSymmetry(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64*64); small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        var ds=0L; var count=0
        for (y in 0 until 64) for (x in 0 until 32) { ds+=abs(grayValue(pixels[y*64+x])-grayValue(pixels[y*64+(63-x)])); count++ }
        return (1f-(if(count>0) ds.toFloat()/count else 128f)/50f).coerceIn(0f,1f)
    }

    private fun detectImpossibleStructures(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val pixels = IntArray(48*48); small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        var inc=0
        for (y in 1 until 47) for (x in 1 until 47) { val h=abs(grayValue(pixels[y*48+x+1])-grayValue(pixels[y*48+x-1])); val v=abs(grayValue(pixels[(y+1)*48+x])-grayValue(pixels[(y-1)*48+x])); if(h>40&&v<5||v>40&&h<5) inc++ }
        val ratio=inc.toFloat()/(46*46)
        return when { ratio>0.15f->0.65f; ratio>0.08f->0.40f; else->0.20f }
    }

    private fun analyzeMetadata(meta: ImageMetadata): ModuleScore {
        val anomalies=mutableListOf<Anomaly>(); val details=mutableListOf<String>(); var score=0f
        if (!meta.hasExif) {
            score+=0.40f; anomalies.add(Anomaly("exif_absent", AnomalySeverity.HIGH, "Métadonnées EXIF absentes", "Photo réelle = toujours des EXIF")); details.add("EXIF : absent ⚠️")
        } else {
            details.add("EXIF : présent ✓")
            if (meta.cameraModel==null) { score+=0.25f; anomalies.add(Anomaly("no_camera_model", AnomalySeverity.MEDIUM, "Modèle caméra absent", "EXIF Make/Model vide")); details.add("Caméra : non renseignée ⚠️") } else details.add("Caméra : ${meta.cameraModel} ✓")
            val aiSigs=listOf("stable diffusion","midjourney","dalle","generative","diffusion","gan","runway","adobe firefly","imagen")
            val sw=meta.software?.lowercase()?:""
            if (aiSigs.any{sw.contains(it)}) { score+=0.80f; anomalies.add(Anomaly("ai_software_signature", AnomalySeverity.CRITICAL, "Logiciel IA dans les métadonnées", "Software: \"${meta.software}\"")); details.add("Logiciel : signature IA 🚨") } else if (meta.software!=null) details.add("Logiciel : ${meta.software}")
        }
        return ModuleScore(score.coerceIn(0f,1f), if(meta.hasExif) 0.85f else 0.70f, details, anomalies)
    }

    private fun extractMetadata(uri: Uri, bitmap: Bitmap): ImageMetadata {
        var hasExif=false; var cameraMake:String?=null; var cameraModel:String?=null
        var software:String?=null; var dateTime:String?=null; var colorSpace:String?=null; var gpsPresent=false
        try {
            context.contentResolver.openInputStream(uri)?.use { val exif=ExifInterface(it); hasExif=true; cameraMake=exif.getAttribute(ExifInterface.TAG_MAKE); cameraModel=exif.getAttribute(ExifInterface.TAG_MODEL); software=exif.getAttribute(ExifInterface.TAG_SOFTWARE); dateTime=exif.getAttribute(ExifInterface.TAG_DATETIME); colorSpace=exif.getAttribute(ExifInterface.TAG_COLOR_SPACE); gpsPresent=exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)!=null }
        } catch (_: Exception) {}
        val fileSize=try { context.contentResolver.openFileDescriptor(uri,"r")?.use{it.statSize}?:0L } catch (_: Exception) { 0L }
        return ImageMetadata(width=bitmap.width, height=bitmap.height, fileSizeBytes=fileSize, mimeType=context.contentResolver.getType(uri)?:"", hasExif=hasExif, cameraMake=cameraMake, cameraModel=cameraModel, software=software, dateTime=dateTime, colorSpace=colorSpace, gpsPresent=gpsPresent)
    }

    private fun fuseScores(scores: ImageModuleScores, apiResult: AiOrNotResult?): Pair<Float, Float> {
        val weights=mapOf("pixel" to 0.35f,"stats" to 0.20f,"artifact" to 0.30f,"meta" to 0.15f)
        var scoreSum=0f; var weightSum=0f; var confidenceSum=0f
        scores.pixelAnalysis?.let { scoreSum+=it.score*weights["pixel"]!!*it.confidence; confidenceSum+=it.confidence*weights["pixel"]!!; weightSum+=weights["pixel"]!!*it.confidence }
        scores.statistics?.let { scoreSum+=it.score*weights["stats"]!!*it.confidence; confidenceSum+=it.confidence*weights["stats"]!!; weightSum+=weights["stats"]!!*it.confidence }
        scores.artifactDetection?.let { scoreSum+=it.score*weights["artifact"]!!*it.confidence; confidenceSum+=it.confidence*weights["artifact"]!!; weightSum+=weights["artifact"]!!*it.confidence }
        scores.metadataAnalysis?.let { val b=if(it.score>0.7f) 2.0f else 1.0f; scoreSum+=it.score*weights["meta"]!!*it.confidence*b; confidenceSum+=it.confidence*weights["meta"]!!; weightSum+=weights["meta"]!!*it.confidence*b }
        val localScore=if(weightSum>0) (scoreSum/weightSum).coerceIn(0f,1f) else 0.5f
        val finalScore=if(apiResult!=null) (apiResult.aiScore*0.60f+localScore*0.40f).coerceIn(0f,1f) else localScore
        val finalConfidence=if(apiResult!=null) 0.92f else (confidenceSum/maxOf(weightSum,0.01f)).coerceIn(0.4f,0.75f)
        return finalScore to finalConfidence
    }

    private fun buildExplanation(score: Float, scores: ImageModuleScores, api: AiOrNotResult?): String {
        val pct=(score*100).toInt(); val sb=StringBuilder()
        if (api!=null) { sb.appendLine("🤖 AI or Not : ${if(api.isAiDetected) "IA détectée" else "Réelle"} (${(api.aiScore*100).toInt()}% IA)"); sb.appendLine() }
        sb.appendLine(when { score>0.65f->"🔴 Image très probablement générée par IA ($pct%)."; score>0.45f->"🟡 Anomalies suspectes détectées ($pct% probabilité IA)."; score>0.30f->"🟡 Résultat inconclusif ($pct%)."; score>0.15f->"🟢 Principalement réelle ($pct% IA)."; else->"🟢 Image authentique ($pct% IA)." })
        return sb.toString().trim()
    }

    private fun buildKeyFindings(scores: ImageModuleScores, api: AiOrNotResult?): List<String> {
        val f=mutableListOf<String>()
        if (api!=null) f.add("API AI or Not : ${(api.aiScore*100).toInt()}% IA (${api.verdict})")
        scores.allScores().forEach { (name, score) -> score.anomalies.filter{it.severity>=AnomalySeverity.MEDIUM}.forEach{f.add("${it.description} [$name]")} }
        if (f.isEmpty()) f.add("Aucune anomalie significative")
        return f.take(6)
    }

    private fun buildWarnings(scores: ImageModuleScores, meta: ImageMetadata, api: AiOrNotResult?): List<String> {
        val w=mutableListOf<String>()
        if (api==null) w.add("ℹ️ Analyse externe indisponible — algorithmes locaux uniquement")
        if (!meta.hasExif) w.add("⚠️ Métadonnées EXIF absentes")
        if (meta.software?.lowercase()?.contains("diffusion")==true) w.add("🚨 Signature logiciel IA dans les métadonnées")
        scores.allScores().forEach { (_,score) -> score.anomalies.filter{it.severity==AnomalySeverity.CRITICAL}.forEach{w.add("🚨 ${it.description}")} }
        return w
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try { val opts=BitmapFactory.Options().apply{inSampleSize=2}; context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)} } catch (_: Exception) { null }

    private fun grayValue(pixel: Int): Int = (0.299*Color.red(pixel)+0.587*Color.green(pixel)+0.114*Color.blue(pixel)).toInt()

    private fun errorResult(id: String, name: String) = ImageAnalysisResult(
        imageId=id, imagePath="", imageName=name, overallScore=0.5f, confidenceScore=0.1f,
        reliabilityLevel=ReliabilityLevel.SUSPICIOUS, verdictLevel=VerdictLevel.UNCERTAIN,
        moduleScores=ImageModuleScores(), explanation="Impossible de charger l'image.",
        keyFindings=listOf("Erreur de chargement"), warnings=listOf("⚠️ Fichier illisible"),
        processingTimeMs=0L, metadata=ImageMetadata()
    )
}
