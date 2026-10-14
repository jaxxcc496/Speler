package nl.speler

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

private val Bg = Color(0xFF090812)
private val Panel = Color(0xFF171426)
private val Panel2 = Color(0xFF211A35)
private val Purple = Color(0xFF9B5CFF)
private val Pink = Color(0xFFEF5DCE)
private val Muted = Color(0xFFAAA4BD)

data class Song(val title: String, val uri: String)
data class Playlist(val name: String, val songs: List<Song>)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { MaterialTheme(colorScheme = darkColorScheme(
            primary = Purple, secondary = Pink, background = Bg, surface = Panel
        )) { PlayerApp(this, player) } }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

private fun titleFor(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        else uri.lastPathSegment ?: "Audio"
    } ?: (uri.lastPathSegment ?: "Audio")
} catch (_: Exception) { uri.lastPathSegment ?: "Audio" }

private fun timeText(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun PlayerApp(context: Context, player: ExoPlayer) {
    val prefs = remember { context.getSharedPreferences("speler", Context.MODE_PRIVATE) }
    fun loadLists(): List<Playlist> = try {
        val a = JSONArray(prefs.getString("playlists", "[]"))
        (0 until a.length()).map { i ->
            val p = a.getJSONObject(i); val s = p.getJSONArray("songs")
            Playlist(p.getString("name"), (0 until s.length()).map { j ->
                val v = s.getJSONObject(j); Song(v.getString("title"), v.getString("uri"))
            })
        }
    } catch (_: Exception) { emptyList() }

    val playlists = remember { mutableStateListOf<Playlist>().apply { addAll(loadLists()) } }
    var selected by remember { mutableIntStateOf(-1) }
    var tab by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf("Kies een nummer") }
    var currentUri by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var youtubeUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Welkom terug") }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }

    fun save() {
        val a = JSONArray()
        playlists.forEach { p ->
            val songs = JSONArray()
            p.songs.forEach { songs.put(JSONObject().put("title", it.title).put("uri", it.uri)) }
            a.put(JSONObject().put("name", p.name).put("songs", songs))
        }
        prefs.edit().putString("playlists", a.toString()).apply()
    }
    fun add(songs: List<Song>) {
        if (selected !in playlists.indices) { message = "Maak eerst een playlist."; return }
        playlists[selected] = playlists[selected].copy(songs = playlists[selected].songs + songs)
        save(); message = "${songs.size} nummer(s) toegevoegd aan ${playlists[selected].name}"
    }
    fun play(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(songs.indices)
        player.setMediaItems(songs.map { song ->
            MediaItem.Builder().setUri(song.uri).setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(song.title).build()
            ).build()
        }, safeIndex, 0L)
        player.prepare()
        player.play()
        currentTitle = songs[safeIndex].title
        currentUri = songs[safeIndex].uri
        playing = true
        position = 0f
        duration = 0L
        tab = 1
    }
    fun downloadAudio(urlText: String) {
        val url = urlText.trim()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            message = "Plak eerst een geldige HTTP(S)-link."
            return
        }
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            message = "YouTube-video's kunnen niet rechtstreeks via deze knop als audiobestand worden gedownload. Gebruik een directe link naar een audio bestand waarvoor je toestemming hebt."
            return
        }
        try {
            val uri = Uri.parse(url)
            var filename = uri.lastPathSegment?.substringBefore('?')?.takeIf { it.isNotBlank() } ?: "speler-audio"
            filename = filename.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
            val request = DownloadManager.Request(uri)
                .setTitle(filename)
                .setDescription("Gedownload met Speler")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            message = "Download gestart. Je vindt het bestand straks in Mijn bestanden > Downloads."
        } catch (_: Exception) {
            message = "Download niet gestart. Controleer de link en probeer opnieuw."
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        add(uris.map { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Song(titleFor(context, uri), uri.toString())
        })
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151025), Bg, Color(0xFF08070E)))).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Purple, Pink))), contentAlignment = Alignment.Center) {
                Text("♫", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("S P E L E R", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text("YOUR SOUND. YOUR WORLD.", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
            }
            Box(Modifier.clip(CircleShape).background(Panel2).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("● ONLINE", color = Color(0xFF9BE7C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xAA171426)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("Bibliotheek", "Speler", "YouTube", "Downloads").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (tab == i) Brush.linearGradient(listOf(Purple, Color(0xFF7343D6))) else Brush.linearGradient(listOf(Panel, Panel)))
                        .clickable { tab = i }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (tab == i) Color.White else Muted, fontSize = 10.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }
            }
        }

        when (tab) {
            0 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("JOUW MUZIEK", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                        Text("Bibliotheek", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${playlists.sumOf { it.songs.size }} tracks", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, modifier = Modifier.weight(1f), label = { Text("Nieuwe playlist") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Button(onClick = {
                        if (newName.isNotBlank()) {
                            playlists.add(Playlist(newName.trim(), emptyList())); selected = playlists.lastIndex
                            newName = ""; save(); message = "Playlist aangemaakt"
                        }
                    }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 15.dp), shape = RoundedCornerShape(14.dp)) { Text("+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = selected in playlists.indices, shape = RoundedCornerShape(12.dp)) { Text("＋ Audio toevoegen", fontSize = 12.sp) }
                    OutlinedButton(onClick = { tab = 3 }, shape = RoundedCornerShape(12.dp)) { Text("↓ Downloads", fontSize = 12.sp) }
                }
                Text("JOUW PLAYLISTS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                if (playlists.isEmpty()) {
                    EmptyCard("Nog geen playlists", "Maak hierboven een playlist om te beginnen.")
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(playlists) { i, playlist ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                    .background(if (selected == i) Brush.horizontalGradient(listOf(Color(0xFF33204F), Panel)) else Brush.horizontalGradient(listOf(Panel, Panel)))
                                    .clickable { selected = i }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Purple.copy(alpha = .8f), Pink.copy(alpha = .6f)))), contentAlignment = Alignment.Center) {
                                    Text("♫", color = Color.White, fontSize = 27.sp)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("${playlist.songs.size} nummers", color = Muted, fontSize = 12.sp)
                                }
                                TextButton(onClick = { selected = i; play(playlist.songs, 0) }, enabled = playlist.songs.isNotEmpty()) { Text("▶", color = Purple, fontSize = 20.sp) }
                            }
                        }
                    }
                }
            }
            1 -> {
                Text("NU AAN HET SPELEN", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Jouw soundtrack.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF43236C), Color(0xFF211A3D), Color(0xFF151321)))),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(174.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(Purple, Color(0xFF4D2D91), Pink))), contentAlignment = Alignment.Center) {
                            Text("♫", color = Color.White, fontSize = 100.sp, fontWeight = FontWeight.Light)
                        }
                        Text("SPOTLIGHT", color = Color.White.copy(alpha = .6f), fontSize = 10.sp, letterSpacing = 4.sp)
                    }
                }
                Text(currentTitle, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (currentUri.startsWith("http")) "Online audio" else "Lokale muziekbibliotheek", color = Muted, fontSize = 12.sp)
                Slider(value = position.coerceIn(0f, 1f), onValueChange = { position = it; if (duration > 0) player.seekTo((duration * it).toLong()) }, colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Purple, inactiveTrackColor = Panel2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeText(player.currentPosition), color = Muted, fontSize = 10.sp)
                    Text(timeText(if (player.duration > 0) player.duration else duration), color = Muted, fontSize = 10.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle }) { Text("⤨", color = if (shuffle) Pink else Muted, fontSize = 22.sp) }
                    TextButton(onClick = { player.seekToPreviousMediaItem(); currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle }) { Text("◀◀", color = Color.White, fontSize = 19.sp) }
                    Box(Modifier.size(66.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Purple, Pink))).clickable {
                        if (player.isPlaying) { player.pause(); playing = false } else { player.play(); playing = true }
                        currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle
                    }, contentAlignment = Alignment.Center) { Text(if (playing) "Ⅱ" else "▶", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { player.seekToNextMediaItem(); currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle }) { Text("▶▶", color = Color.White, fontSize = 19.sp) }
                    TextButton(onClick = { repeat = !repeat; player.repeatMode = if (repeat) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF }) { Text("⟳", color = if (repeat) Pink else Muted, fontSize = 22.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Text("Snelheid", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        TextButton(onClick = { speed = value; player.setPlaybackSpeed(value) }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                            Text("${value}x", color = if (speed == value) Pink else Muted, fontSize = 11.sp, fontWeight = if (speed == value) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            2 -> {
                Text("ONTDEK MUZIEK", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("YouTube", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                EmptyCard("Zoek en open muziek", "Plak een YouTube-link hieronder. YouTube-video's worden niet rechtstreeks in deze speler afgespeeld.")
                OutlinedTextField(youtubeUrl, { youtubeUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Plak YouTube- of audio-link") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (youtubeUrl.startsWith("https://") || youtubeUrl.startsWith("http://")) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl))) }
                            catch (_: Exception) { message = "Kon de link niet openen." }
                        } else message = "Vul een geldige link in."
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Open link ↗") }
                    OutlinedButton(onClick = { downloadAudio(youtubeUrl) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("↓ Download") }
                }
                Spacer(Modifier.weight(1f))
            }
            else -> {
                Text("JOUW COLLECTIE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Downloads", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                EmptyCard("Download audio", "Plak een directe link naar een audiobestand. Het bestand wordt opgeslagen in de map Downloads van je telefoon. YouTube-links zijn geen directe audiobestanden.")
                OutlinedTextField(link, { link = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Link naar audiobestand") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Button(onClick = { downloadAudio(link); if (link.startsWith("http") && !link.contains("youtube.com") && !link.contains("youtu.be")) link = "" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("↓ Download naar Mijn bestanden") }
                Button(onClick = { tab = 0; picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Kies audio van telefoon") }
                Spacer(Modifier.weight(1f))
            }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tab != 1) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF2A1C43), Panel))).clickable { tab = 1 }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Purple, Pink))), contentAlignment = Alignment.Center) { Text("♫", color = Color.White, fontSize = 24.sp) }
                Column(Modifier.weight(1f)) {
                    Text(currentTitle, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Tik om speler te openen", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { if (player.isPlaying) { player.pause(); playing = false } else { player.play(); playing = true } }) { Text(if (playing) "Ⅱ" else "▶", color = Pink, fontSize = 21.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EmptyCard(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Panel2), contentAlignment = Alignment.Center) {
            Text("♫", color = Purple, fontSize = 30.sp)
        }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(description, color = Muted, fontSize = 12.sp)
    }
}
