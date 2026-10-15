package nl.speler

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

@Composable
fun DownloaderTab(context: Context) {
    var url by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Plak een link naar media die je mag downloaden.") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DOWNLOADER", color = Color(0xFFAAA4BD), fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Download media", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(
            "Download audio als MP3 of video als MP4. Bestanden komen in Download/Speler.",
            color = Color(0xFFAAA4BD), fontSize = 12.sp
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video- of medialink") },
            placeholder = { Text("https://…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            enabled = !downloading
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = audioOnly,
                onClick = { if (!downloading) audioOnly = true },
                label = { Text("Audio · MP3") },
                enabled = !downloading
            )
            FilterChip(
                selected = !audioOnly,
                onClick = { if (!downloading) audioOnly = false },
                label = { Text("Video · MP4") },
                enabled = !downloading
            )
        }
        Button(
            onClick = {
                val cleanUrl = url.trim()
                if (cleanUrl.isBlank()) {
                    status = "Vul eerst een link in."
                } else {
                    downloading = true
                    progress = 0f
                    status = "Downloader starten…"
                    Thread {
                        try {
                            val downloadFolder = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "Speler"
                            )
                            if (!downloadFolder.exists() && !downloadFolder.mkdirs()) {
                                throw IllegalStateException("De downloadmap kon niet worden aangemaakt.")
                            }
                            val request = YoutubeDLRequest(cleanUrl)
                            request.addOption("--no-playlist")
                            request.addOption("-o", File(downloadFolder, "%(title)s.%(ext)s").absolutePath)
                            if (audioOnly) {
                                request.addOption("-x")
                                request.addOption("--audio-format", "mp3")
                                request.addOption("--audio-quality", "192K")
                            } else {
                                request.addOption("-f", "best[ext=mp4]/best")
                            }
                            YoutubeDL.getInstance().execute(request, "speler-downloader") { percent, eta, outputLine ->
                                mainHandler.post {
                                    progress = (percent / 100f).coerceIn(0f, 1f)
                                    status = if (percent >= 0f) {
                                        "Downloaden… ${percent.toInt()}%  •  resterend: ${eta}s"
                                    } else {
                                        outputLine.takeLast(180).ifBlank { "Downloaden…" }
                                    }
                                }
                            }
                            mainHandler.post {
                                progress = 1f
                                status = "Klaar! Je bestand staat in Download/Speler."
                                downloading = false
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                status = "Download mislukt: ${e.message ?: "controleer de link en internetverbinding"}"
                                downloading = false
                            }
                        }
                    }.start()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !downloading,
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (downloading) "Bezig…" else "Download starten")
        }
        if (downloading) {
            CircularProgressIndicator(progress = progress)
            Button(
                onClick = {
                    Thread {
                        try { YoutubeDL.getInstance().destroyProcessById("speler-downloader") } catch (_: Exception) {}
                    }.start()
                    status = "Download annuleren…"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Annuleren") }
        }
        Text(status, color = Color(0xFFAAA4BD), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Gebruik dit alleen voor media die je zelf bezit of waarvoor je toestemming hebt. Houd je aan de voorwaarden van de website.",
            color = Color(0xFFAAA4BD), fontSize = 11.sp
        )
    }
}
