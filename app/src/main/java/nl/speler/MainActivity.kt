package nl.speler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

data class Song(val title: String, val uri: String)
data class Playlist(val name: String, val songs: List<Song>)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PlayerApp(this, player) } }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

private fun titleFor(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        else uri.lastPathSegment ?: "Audio"
    } ?: (uri.lastPathSegment ?: "Audio")
} catch (_: Exception) { uri.lastPathSegment ?: "Audio" }

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
    var currentTitle by remember { mutableStateOf("Kies een nummer") }
    var playing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var currentSongs by remember { mutableStateOf(emptyList<Song>()) }

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
        if (selected !in playlists.indices) { message = "Maak of open eerst een playlist."; return }
        playlists[selected] = playlists[selected].copy(songs = playlists[selected].songs + songs)
        save(); message = "${songs.size} bestand(en) toegevoegd."
    }
    fun play(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        currentSongs = songs
        player.setMediaItems(songs.map { MediaItem.Builder().setUri(it.uri).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(it.title).build()).build() }, index, 0L)
        player.prepare(); player.play(); currentTitle = songs[index].title; playing = true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        add(uris.map { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Song(titleFor(context, uri), uri.toString())
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("♫ Speler", style = MaterialTheme.typography.headlineLarge)
        Text("Playlists en muziek", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newName, { newName = it }, label = { Text("Naam playlist") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                if (newName.isNotBlank()) {
                    playlists.add(Playlist(newName.trim(), emptyList())); selected = playlists.lastIndex
                    newName = ""; save(); message = "Playlist aangemaakt."
                }
            }) { Text("Maken") }
        }
        if (playlists.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                playlists.forEachIndexed { i, p ->
                    FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text(p.name, maxLines = 1) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = selected in playlists.indices) { Text("+ Bestanden") }
            OutlinedButton(onClick = {
                val url = link.trim()
                if (selected !in playlists.indices) message = "Selecteer eerst een playlist."
                else if ((url.startsWith("https://") || url.startsWith("http://")) && !url.contains("youtube.com") && !url.contains("youtu.be")) {
                    add(listOf(Song(url.substringAfterLast('/').substringBefore('?').ifBlank { "Online audio" }, url))); link = ""
                } else message = "Vul een directe audio-URL in (geen YouTube-link)."
            }) { Text("Link toevoegen") }
        }
        OutlinedTextField(link, { link = it }, label = { Text("Directe audio-URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (selected in playlists.indices) {
            val songs = playlists[selected].songs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { play(songs, 0) }, enabled = songs.isNotEmpty()) { Text("▶ Alles afspelen") }
                OutlinedButton(onClick = { playlists.removeAt(selected); selected = -1; save() }) { Text("Playlist verwijderen") }
            }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(songs) { i, song ->
                    ListItem(
                        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("Tik om af te spelen") },
                        modifier = Modifier.clickable { play(songs, i) },
                        trailingContent = { TextButton(onClick = {
                            playlists[selected] = playlists[selected].copy(songs = playlists[selected].songs.filterIndexed { j, _ -> j != i }); save()
                        }) { Text("✕") } }
                    )
                }
            }
        } else {
            Text("Maak een playlist of selecteer er een.")
            Spacer(Modifier.weight(1f))
        }
        Divider()
        Text(currentTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (player.isPlaying) { player.pause(); playing = false }
                else { player.play(); playing = true }
            }) { Text(if (playing) "Ⅱ Pauze" else "▶ Afspelen") }
            OutlinedButton(onClick = { player.seekToPreviousMediaItem() }, enabled = player.hasPreviousMediaItem()) { Text("⏮ Vorige") }
            OutlinedButton(onClick = { player.seekToNextMediaItem() }, enabled = player.hasNextMediaItem()) { Text("Volgende ⏭") }
        }
    }
}
