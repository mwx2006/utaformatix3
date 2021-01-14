package io

import external.Resources
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import model.DEFAULT_LYRIC
import model.ExportResult
import model.Feature
import model.Format
import model.ImportWarning
import model.Pitch
import model.TimeSignature
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import process.pitch.getRelativeData
import process.pitch.processSvpInputPitchData
import process.validateNotes
import util.nameWithoutExtension
import util.readText
import kotlin.math.roundToLong

object S5p {
    suspend fun parse(file: File): model.Project {
        val text = file.readText().let {
            val index = it.lastIndexOf('}')
            it.take(index + 1)
        }
        val project = jsonSerializer.parse(Project.serializer(), text)
        val warnings = mutableListOf<ImportWarning>()
        val timeSignatures = project.meter.map {
            TimeSignature(
                measurePosition = it.measure,
                numerator = it.beatPerMeasure,
                denominator = it.beatGranularity
            )
        }.takeIf { it.isNotEmpty() } ?: listOf(TimeSignature.default).also {
            warnings.add(ImportWarning.TimeSignatureNotFound)
        }
        val tempos = project.tempo.map {
            model.Tempo(
                tickPosition = it.position / TICK_RATE,
                bpm = it.beatPerMinute
            )
        }.takeIf { it.isNotEmpty() } ?: listOf(model.Tempo.default).also {
            warnings.add(ImportWarning.TempoNotFound)
        }
        val tracks = parseTracks(project)
        return model.Project(
            format = Format.S5P,
            inputFiles = listOf(file),
            name = file.nameWithoutExtension,
            tracks = tracks,
            timeSignatures = timeSignatures,
            tempos = tempos,
            measurePrefix = 0,
            importWarnings = warnings
        )
    }

    private fun parseTracks(project: Project): List<model.Track> = project.tracks.mapIndexed { index, track ->
        model.Track(
            id = index,
            name = track.name ?: "Track ${index + 1}",
            notes = parseNotes(track),
            pitch = parsePitch(track)
        ).validateNotes()
    }

    private fun parsePitch(track: Track): Pitch? {
        val pitchDelta = track.parameters?.pitchDelta ?: return Pitch(emptyList(), isAbsolute = false)
        val convertedPoints = pitchDelta.asSequence()
            .withIndex()
            .groupBy { it.index / 2 }
            .map { it.value }
            .map { it.map { indexedValue -> indexedValue.value } }
            .mapNotNull {
                val rawTick = it.getOrNull(0) ?: return@mapNotNull null
                val centValue = it.getOrNull(1) ?: return@mapNotNull null

                val tick = rawTick * 3.75
                val value = centValue / 100

                tick.roundToLong() to value
            }
            .toList()
        return Pitch(convertedPoints, isAbsolute = false).takeIf { it.data.isNotEmpty() }
    }

    private fun parseNotes(track: Track): List<model.Note> = track.notes.map { note ->
        val tickOn = note.onset / TICK_RATE
        model.Note(
            id = 0,
            key = note.pitch,
            tickOn = tickOn,
            tickOff = tickOn + note.duration / TICK_RATE,
            lyric = note.lyric.takeUnless { it.isNullOrBlank() } ?: DEFAULT_LYRIC
        )
    }

    fun generate(project: model.Project, features: List<Feature>): ExportResult {
        val jsonText = generateContent(project, features)
        val blob = Blob(arrayOf(jsonText), BlobPropertyBag("application/octet-stream"))
        val name = project.name + Format.S5P.extension
        return ExportResult(blob, name, listOf())
    }

    private fun generateContent(project: model.Project, features: List<Feature>): String {
        val template = Resources.s5pTemplate
        val s5p = jsonSerializer.parse(Project.serializer(), template)
        s5p.meter = project.timeSignatures.map {
            Meter(
                measure = it.measurePosition,
                beatPerMeasure = it.numerator,
                beatGranularity = it.denominator
            )
        }
        s5p.tempo = project.tempos.map {
            Tempo(
                position = it.tickPosition * TICK_RATE,
                beatPerMinute = it.bpm
            )
        }
        val emptyTrack = s5p.tracks.first()
        s5p.tracks = project.tracks.map {
            generateTrack(it, emptyTrack, features)
        }
        return jsonSerializer.stringify(Project.serializer(), s5p)
    }

    private fun generateTrack(track: model.Track, emptyTrack: Track, features: List<Feature>): Track {
        return emptyTrack.copy(
            name = track.name,
            displayOrder = track.id,
            notes = track.notes.map {
                Note(
                    onset = it.tickOn * TICK_RATE,
                    duration = it.length * TICK_RATE,
                    lyric = it.lyric,
                    pitch = it.key
                )
            },
            parameters = emptyTrack.parameters!!.copy(
                pitchDelta = generatePitchData(track, features)
            )
        )
    }

    private fun generatePitchData(track: model.Track, features: List<Feature>) : List<Double> {
        if (!features.contains(Feature.CONVERT_PITCH)) return emptyList()
        val data = track.pitch?.getRelativeData(track.notes)
            ?.map { (it.first / 3.75) to (it.second * 100) }
            ?: return emptyList()
        return data.flatMap { listOf(it.first, it.second) }
    }

    private const val TICK_RATE = 1470000L

    private val jsonSerializer = Json(
        JsonConfiguration.Stable.copy(
            isLenient = true,
            ignoreUnknownKeys = true
        )
    )

    @Serializable
    private data class Project(
        var instrumental: Instrumental? = null,
        var meter: List<Meter> = listOf(),
        var mixer: Mixer? = null,
        var tempo: List<Tempo> = listOf(),
        var tracks: List<Track> = listOf(),
        var version: Int? = null
    )

    @Serializable
    private data class Instrumental(
        var filename: String? = null,
        var offset: Double? = null
    )

    @Serializable
    private data class Meter(
        var beatGranularity: Int,
        var beatPerMeasure: Int,
        var measure: Int
    )

    @Serializable
    private data class Mixer(
        var gainInstrumentalDecibel: Double? = null,
        var gainVocalMasterDecibel: Double? = null,
        var instrumentalMuted: Boolean? = null,
        var vocalMasterMuted: Boolean? = null
    )

    @Serializable
    private data class Tempo(
        var beatPerMinute: Double,
        var position: Long
    )

    @Serializable
    private data class Track(
        var color: String? = null,
        var dbDefaults: DbDefaults? = null,
        var dbName: String? = null,
        var displayOrder: Int? = null,
        var mixer: MixerX? = null,
        var name: String? = null,
        var notes: List<Note> = listOf(),
        var parameters: Parameters? = null
    )

    @Serializable
    private class DbDefaults

    @Serializable
    private data class MixerX(
        var display: Boolean? = null,
        var engineOn: Boolean? = null,
        var gainDecibel: Double? = null,
        var muted: Boolean? = null,
        var pan: Double? = null,
        var solo: Boolean? = null
    )

    @Serializable
    private data class Note(
        var comment: String? = null,
        var dF0Jitter: Double? = null,
        var duration: Long,
        var lyric: String? = null,
        var onset: Long,
        var pitch: Int
    )

    @Serializable
    private data class Parameters(
        var breathiness: List<Double>? = null,
        var gender: List<Double>? = null,
        var interval: Long? = null,
        var loudness: List<Double>? = null,
        var pitchDelta: List<Double>? = null,
        var tension: List<Double>? = null,
        var vibratoEnv: List<Double>? = null,
        var voicing: List<Double>? = null
    )

    @Serializable
    private data class PitchDelta(
        var interval: Long? = null,
        var points: List<Double>? = null
    )
}
